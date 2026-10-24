package app.floatphone.shell

import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Origin-scoped, main-frame-only transport for phone-local AI endpoints. No cloud proxy. */
class LocalApiBridge(private val webView: WebView, siteUrl: String) {
    private val origin = Uri.parse(siteUrl).buildUpon().path(null).query(null).fragment(null).build().toString().trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        // A local service must never redirect a key or prompt to another host.
        .followRedirects(false).followSslRedirects(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .build()
    private class Transfer(val call: Call, val reply: JavaScriptReplyProxy) {
        val demand = Semaphore(0)
        var pulling = false
    }
    // Accessed on the UI thread, except demand/call which are thread-safe.
    private val transfers = mutableMapOf<String, Transfer>()
    private val mcpUrls = mutableSetOf<String>()

    fun attach() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, "FloatLocalApi", setOf(origin)) { _, message, source, isMainFrame, reply ->
            if (!isMainFrame || source.toString().trimEnd('/') != origin) return@addWebMessageListener
            val json = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            val id = json.optString("id")
            if (!id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) return@addWebMessageListener
            when (json.optString("type")) {
                "mcp-configure" -> {
                    mcpUrls.clear()
                    val urls = json.optJSONArray("urls")
                    if (urls != null) for (i in 0 until minOf(urls.length(), 64)) {
                        val target = urls.optString(i)
                        val u = Uri.parse(target)
                        if (u.scheme in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null && u.fragment == null) mcpUrls.add(target)
                    }
                }
                "cancel" -> transfers.remove(id)?.let { it.call.cancel(); it.demand.release() }
                "pull" -> transfers[id]?.let {
                    if (!it.pulling) { it.pulling = true; it.demand.release() }
                }
                "start" -> start(id, json, reply)
            }
        }
    }

    private fun post(reply: JavaScriptReplyProxy, json: JSONObject) {
        runCatching { reply.postMessage(json.toString()) }
    }

    private fun start(id: String, json: JSONObject, reply: JavaScriptReplyProxy) {
        if (transfers.containsKey(id)) return
        val request = runCatching {
            require(transfers.size < 8)
            val uri = Uri.parse(json.getString("url"))
            require(uri.scheme == "http" || uri.scheme == "https")
            require(uri.userInfo == null && uri.fragment == null)
            // No DNS for localhost: map it to a numeric loopback address.
            require(!uri.host.isNullOrBlank())
            require(uri.port == -1 || uri.port in 1..65535)
            val path = uri.path ?: ""
            val isMcp = json.optBoolean("mcp", false)
            if (isMcp) require(mcpUrls.contains(uri.toString()))
            require(isMcp || Regex("/(models|chat/completions|embeddings|messages)$").containsMatchIn(path) ||
                Regex("/models/[^/]+:(streamGenerateContent|generateContent)$").containsMatchIn(path) ||
                Regex("/(images/generations|audio/speech|t2a_v2)$").containsMatchIn(path))
            val target = if (uri.host == "localhost") uri.buildUpon().encodedAuthority(
                "127.0.0.1" + if (uri.port > 0) ":${uri.port}" else ""
            ).build().toString() else uri.toString()
            val method = json.optString("method", "GET").uppercase()
            require(method == "GET" || method == "POST")
            val body = if (json.isNull("body")) "" else json.getString("body")
            require(body.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024)
            val builder = Request.Builder().url(target)
            val headers = json.optJSONObject("headers") ?: JSONObject()
            headers.keys().forEach { name ->
                if ((isMcp && name.lowercase() !in setOf("host", "cookie", "origin", "referer", "content-length", "connection", "proxy-authorization")) || name.lowercase() in setOf("authorization", "content-type", "x-api-key", "anthropic-version", "accept")) {
                    builder.header(name, headers.getString(name))
                }
            }
            builder.method(method, if (method == "POST") body.toRequestBody("application/json".toMediaType()) else null).build()
        }.getOrNull()
        if (request == null) {
            post(reply, JSONObject().put("id", id).put("type", "error"))
            return
        }
        val requestClient = if (request.url.encodedPath.endsWith("/images/generations")) client.newBuilder().readTimeout(240, TimeUnit.SECONDS).build() else client
        val call = requestClient.newCall(request)
        val transfer = Transfer(call, reply)
        transfers[id] = transfer
        fun deliver(type: String, fill: (JSONObject) -> Unit = {}) {
            val event = JSONObject().put("id", id).put("type", type).also(fill)
            webView.post {
                if (transfers[id] !== transfer) return@post
                if (type == "chunk") transfer.pulling = false
                post(reply, event)
                if (type == "error" || type == "end") transfers.remove(id)
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { deliver("error") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        deliver("headers") {
                            it.put("status", response.code)
                                .put("contentType", response.header("Content-Type") ?: "application/json")
                                .put("sessionId", response.header("Mcp-Session-Id") ?: "")
                        }
                        val input = response.body?.byteStream()
                        if (input == null) { deliver("end"); return }
                        val buffer = ByteArray(16 * 1024)
                        while (!call.isCanceled()) {
                            // Backpressure: only read when JavaScript has capacity.
                            if (!transfer.demand.tryAcquire(90, TimeUnit.SECONDS)) {
                                call.cancel(); deliver("error"); return
                            }
                            if (call.isCanceled()) return
                            val count = input.read(buffer)
                            if (count < 0) { deliver("end"); return }
                            val data = Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)
                            deliver("chunk") { it.put("data", data) }
                        }
                    } catch (_: Exception) { deliver("error") }
                }
            }
        })
    }

    fun cancelAll() {
        transfers.values.forEach { it.call.cancel(); it.demand.release() }
        transfers.clear()
    }
}

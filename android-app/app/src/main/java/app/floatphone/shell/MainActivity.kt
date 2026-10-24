package app.floatphone.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private var localApiBridge: LocalApiBridge? = null

    companion object {
        val SITE_URL: String = BuildConfig.SITE_URL
        val VERSION: String = BuildConfig.VERSION_NAME
        const val EXTRA_OPEN_URL = "open_url"
        const val DEEP_LINK_SCHEME = "meixinji"
        const val CH_MESSAGES = "shell_messages"
    }

    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    @Volatile
    private var statusBarHeightCssPx: Int = 0

    private var imeInsetPx: Int = 0
    private var webNotifId = 500

    private val realtime by lazy { RealtimeBridge(this) }
    private val perception by lazy { PerceptionBridge(this) }
    private val deviceAction by lazy { DeviceActionBridge(this) }
    private val calendar by lazy { CalendarBridge(this) }
    private val locationBridge by lazy { LocationBridge(this) }
    private val healthBridge by lazy { HealthBridge(this) }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        healthPermissionGranted = grantedPermissions.contains(HealthBridge.PERM_SLEEP)
    }

    @Volatile
    private var healthPermissionGranted = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    @Volatile
    private var stepPermissionGranted = false

    private val stepPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        stepPermissionGranted = granted
        if (granted) PerceptionBridge.attachStepSensor(this)
    }

    private val avatarClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        callback.onReceiveValue(collectChosenUris(result))
    }

    private fun collectChosenUris(result: ActivityResult): Array<Uri> {
        if (result.resultCode != android.app.Activity.RESULT_OK) return emptyArray()
        val data = result.data ?: return emptyArray()
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let { uris.add(it) }
            }
        }
        data.data?.let { uris.add(it) }
        return uris.distinct().toTypedArray()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ensureMessageChannel()
    }

    private var pendingWebPermissionRequest: android.webkit.PermissionRequest? = null

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val request = pendingWebPermissionRequest ?: return@registerForActivityResult
        pendingWebPermissionRequest = null
        val granted = request.resources.filter { resource ->
            webResourcePermissions(resource).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun webResourcePermissions(resource: String): List<String> = when (resource) {
        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        else -> emptyList()
    }

    private fun safeDownloadName(fileName: String): String {
        val cleaned = fileName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "download" }
        val dot = cleaned.lastIndexOf('.')
        val stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val ext = if (dot > 0) cleaned.substring(dot) else ""
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var candidate = stem.take(100) + ext
        var index = 1
        while (File(dir, candidate).exists() && index < 100) {
            candidate = stem.take(100) + "($index)" + ext
            index++
        }
        return candidate
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableDisplayCutoutCover()
        enableHighRefreshRate()
        hideSystemStatusBar()
        volumeControlStream = AudioManager.STREAM_MUSIC

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        // 关闭远程调试钩子，释放 WebView 渲染帧率
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        rootContainer = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(rootContainer)
        observeWindowInsets()
        ViewCompat.requestApplyInsets(rootContainer)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "$userAgentString FloatShell/$VERSION"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        localApiBridge = LocalApiBridge(webView, SITE_URL).also { it.attach() }
        webView.addJavascriptInterface(ShellBridge(), "AndroidShell")
        webView.addJavascriptInterface(PhoneWidgetBridge(), "PhoneWidget")
        PerceptionBridge.attachStepSensor(this)
        ShellBus.webView = webView

        val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
                    ?: if (request.url.host == "appassets.androidplatform.net")
                        android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream("Not found".toByteArray()))
                    else super.shouldInterceptRequest(view, request)
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                localApiBridge?.cancelAll()
                super.onPageStarted(view, url, favicon)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                if (scheme == "http" || scheme == "https") {
                    val allowed = Uri.parse(SITE_URL)
                    if (url.scheme == allowed.scheme && url.host == allowed.host && url.port == allowed.port) return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url)); true
                    }.getOrDefault(true)
                }
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                }.getOrDefault(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                injectStatusBarHeight()
                injectNativeEnhancer()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                if (request.origin.toString().trimEnd('/') != SITE_URL) { request.deny(); return }
                val supported = request.resources.filter { webResourcePermissions(it).isNotEmpty() }
                if (supported.isEmpty()) { request.deny(); return }
                val missing = supported.flatMap { webResourcePermissions(it) }
                    .distinct()
                    .filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { request.grant(supported.toTypedArray()); return }
                if (pendingWebPermissionRequest != null) { request.deny(); return }
                pendingWebPermissionRequest = request
                webPermissionLauncher.launch(missing.toTypedArray())
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(emptyArray())
                filePathCallback = callback
                return runCatching {
                    val intent = params.createIntent()
                    if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    fileChooserLauncher.launch(intent); true
                }.getOrElse {
                    filePathCallback = null; false
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        safeDownloadName(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)),
                    )
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "已开始下载到「下载」目录", Toast.LENGTH_SHORT).show()
            }
        })

        // 接管 vivo 等系统的左右边缘侧滑返回手势：优先触发小手机内部返回按钮，不直接退回手机桌面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requestWebBack()
            }
        })

        realtime.consumeIntent(intent)
        webView.loadUrl(consumeOpenUrl(intent) ?: "$SITE_URL/assets/www/index.html")
        ensureMessageChannel()
    }

    /** 向系统申请最高屏幕刷新率（如 vivo S17 的 120Hz 模式），解除系统对自编译 WebView 默认 60Hz 锁帧 */
    private fun enableHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    this.display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val modes = display?.supportedModes ?: return
                val bestMode = modes.maxByOrNull { it.refreshRate } ?: return
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = bestMode.modeId
                }
            }
        }
    }

    // Edge touch fallback for devices whose system gesture does not reach Back.
    // Once horizontal intent is clear, cancel WebView's touch stream before acting.
    private var edgeSwipeStartX = 0f
    private var edgeSwipeStartY = 0f
    private var edgeSwipeDirection = 1f
    private var edgeSwipeTracking = false
    private var edgeSwipeClaimed = false
    private var edgeGestureId = 0
    private var lastBackAt = 0L
    private var backPending = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeSwipeStartX = event.x
                edgeSwipeDirection = if (event.x <= resources.displayMetrics.widthPixels / 2f) 1f else -1f
                edgeSwipeStartY = event.y
                edgeGestureId++
                val gestureId = edgeGestureId
                edgeSwipeTracking = false
                edgeSwipeClaimed = false
                if (event.pointerCount == 1 && (event.x <= 20f * density || event.x >= resources.displayMetrics.widthPixels - 20f * density) && imeInsetPx == 0 && ::webView.isInitialized) {
                    val x = event.x / density
                    val y = event.y / density
                    webView.evaluateJavascript("(typeof window.floatShellCanStartBack==='function' && window.floatShellCanStartBack($x,$y))") { eligible ->
                        if (gestureId == edgeGestureId) edgeSwipeTracking = eligible == "true"
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { edgeGestureId++; edgeSwipeTracking = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.x - edgeSwipeStartX) * edgeSwipeDirection
                val dy = kotlin.math.abs(event.y - edgeSwipeStartY)
                if (edgeSwipeTracking && !edgeSwipeClaimed) {
                    if (dy > 12f * density && dy > dx * 0.5f || dx < -12f * density) edgeSwipeTracking = false
                    else if (dx > 24f * density && dy < dx * 0.4f) {
                        edgeSwipeClaimed = true
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (edgeSwipeClaimed) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                edgeGestureId++
                val claimed = edgeSwipeClaimed
                val back = event.actionMasked == MotionEvent.ACTION_UP && claimed &&
                    (event.x - edgeSwipeStartX) * edgeSwipeDirection >= 80f * density &&
                    kotlin.math.abs(event.y - edgeSwipeStartY) < (event.x - edgeSwipeStartX) * edgeSwipeDirection * 0.4f
                edgeSwipeTracking = false
                edgeSwipeClaimed = false
                if (back) requestWebBack()
                if (claimed) return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun requestWebBack() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (backPending || now - lastBackAt < 400L) return
        lastBackAt = now
        if (imeInsetPx > 0) {
            WindowInsetsControllerCompat(window, rootContainer).hide(WindowInsetsCompat.Type.ime())
            return
        }
        backPending = true
        webView.evaluateJavascript("(function(){try{return typeof window.floatShellBack==='function' ? window.floatShellBack() : null;}catch(e){return null;}})()") { result ->
            backPending = false
            // No JS handler (loading/login) is deliberately a no-op. Never exit.
            if (result == "false" && webView.canGoBack()) {
                val list = webView.copyBackForwardList()
                val previous = list.getItemAtIndex(list.currentIndex - 1)?.url
                val current = Uri.parse(SITE_URL)
                val target = previous?.let { Uri.parse(it) }
                if (target != null && target.scheme == current.scheme && target.host == current.host && target.port == current.port) webView.goBack()
            }
        }
    }

    /** 原生层直接注入性能优化与小红书解析脚本，无视 sw.js 离线缓存拦截 */
    private fun injectNativeEnhancer() {
        if (!::webView.isInitialized) return
        val js = "window.dispatchEvent(new Event('meixinji-native-ready'));"
        webView.evaluateJavascript(js, null)
    }

    private fun syncNetworkObservation() {
        val p = getSharedPreferences("realtime_features", MODE_PRIVATE)
        if (p.getBoolean("enabled", false) && p.getBoolean("network", false)) perception.startNetwork() else perception.stopNetwork()
    }
    override fun onResume() { super.onResume(); realtime.resumeNfc(); syncNetworkObservation() }
    override fun onPause() { realtime.pauseNfc(); perception.stopNetwork(); super.onPause() }

    override fun onNewIntent(intent: Intent) {
        realtime.consumeIntent(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        val target = consumeOpenUrl(intent) ?: return
        webView.loadUrl(target)
    }

    private fun consumeOpenUrl(intent: Intent?): String? {
        if (intent == null) return null
        val extra = intent.getStringExtra(EXTRA_OPEN_URL)
        intent.removeExtra(EXTRA_OPEN_URL)
        if (extra != null) return extra.takeIf { it.startsWith(SITE_URL) }
        val data = intent.data
        if (data != null && data.scheme == DEEP_LINK_SCHEME) {
            intent.data = null
            return data.getQueryParameter("url").orEmpty().takeIf { it.startsWith(SITE_URL) }
        }
        return null
    }

    private fun enableDisplayCutoutCover() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun hideSystemStatusBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun observeWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val statusBarPx = insets
                .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top
            val density = resources.displayMetrics.density
            val cssPx = (statusBarPx / density).toInt()
            if (cssPx > 0 && cssPx != statusBarHeightCssPx) {
                statusBarHeightCssPx = cssPx
                injectStatusBarHeight()
            }

            val imePx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (imePx != imeInsetPx) {
                imeInsetPx = imePx
                if (::rootContainer.isInitialized) rootContainer.setPadding(0, 0, 0, imePx)
            }
            insets
        }
    }

    private fun injectStatusBarHeight() {
        if (!::webView.isInitialized || statusBarHeightCssPx <= 0) return
        val js = "document.documentElement.style.setProperty('--android-shell-status-bar-height', '${statusBarHeightCssPx}px');" +
            "window.dispatchEvent(new CustomEvent('floatshell-statusbarheight', { detail: $statusBarHeightCssPx }));"
        webView.evaluateJavascript(js, null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemStatusBar()
    }

    private fun contentIntent(sessionId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val id = sessionId?.trim().orEmpty()
        if (id.isNotEmpty()) {
            intent.putExtra(EXTRA_OPEN_URL, "${SITE_URL.trimEnd('/')}/#open-chat=${Uri.encode(id)}")
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onStop() {
        super.onStop()
        CallOverlayService.onHostBackground()
        CheckInService.onHostBackground()
    }

    override fun onStart() {
        super.onStart()
        CallOverlayService.onHostForeground()
        CheckInService.onHostForeground()
    }

    override fun onDestroy() {
        localApiBridge?.cancelAll()
        if (ShellBus.webView === webView) ShellBus.webView = null
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    inner class ShellBridge {
        @JavascriptInterface
        fun getVersion(): String = VERSION

        @JavascriptInterface
        fun getStatusBarHeightPx(): Int = statusBarHeightCssPx

        @JavascriptInterface
        fun configureRealtime(json: String): Boolean { val ok = realtime.configure(json); runOnUiThread { syncNetworkObservation() }; return ok }
        @JavascriptInterface fun getRealtimeSnapshot(): String = realtime.snapshot()
        @JavascriptInterface fun requestRealtimePermission(key: String) { realtime.permission(key) }
        @JavascriptInterface fun clearRealtime() { realtime.clear() }
        @JavascriptInterface fun requestCharacterVibration(): Boolean = realtime.vibrate()
        @JavascriptInterface fun requestContactsPermission() { runOnUiThread { androidx.core.app.ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.READ_CONTACTS), 410) } }
        @JavascriptInterface fun lookupContact(number: String): String {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
            val cleaned = number.filter { it.isDigit() || it == '+' }.take(30)
            if (cleaned.length < 3) return ""
            return runCatching {
                val uri = Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(cleaned))
                contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).take(80) else "" } ?: ""
            }.getOrDefault("")
        }

        @JavascriptInterface
        fun getPerceptionSnapshot(): String = perception.snapshotJson()

        @JavascriptInterface
        fun getAppLabel(packageName: String): String = perception.appLabel(packageName)

        @JavascriptInterface
        fun getPerceptionCapabilities(): String = perception.capabilitiesJson()

        @JavascriptInterface
        fun readUsageStats(maxEntries: Int): String = perception.usageStatsJson(maxEntries)

        @JavascriptInterface
        fun hasStepPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            || ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

        @JavascriptInterface
        fun requestStepPermission(): Boolean {
            if (hasStepPermission()) {
                PerceptionBridge.attachStepSensor(this@MainActivity)
                return true
            }
            runCatching { stepPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
            return false
        }

        @JavascriptInterface
        fun setTorch(on: Boolean): String = deviceAction.setTorch(on).toString()

        @JavascriptInterface
        fun isTorchOn(): Boolean = deviceAction.isTorchOn()

        @JavascriptInterface
        fun setVolume(stream: String, action: String, level: Int): String =
            deviceAction.setVolume(stream, action, level).toString()

        @JavascriptInterface
        fun setBrightness(level: Int): String = deviceAction.setBrightness(level).toString()

        @JavascriptInterface
        fun setDnd(on: Boolean): String = deviceAction.setDnd(on).toString()

        @JavascriptInterface
        fun openApp(packageName: String): String = deviceAction.openApp(packageName).toString()

        @JavascriptInterface
        fun findPackageByLabel(label: String): String = deviceAction.findPackageByLabel(label)

        @JavascriptInterface
        fun setAlarm(hour: Int, minute: Int, message: String): String =
            deviceAction.setAlarm(hour, minute, message).toString()

        @JavascriptInterface
        fun setTimer(seconds: Int, message: String): String =
            deviceAction.setTimer(seconds, message).toString()

        @JavascriptInterface
        fun mediaControl(action: String): String = deviceAction.mediaControl(action).toString()

        @JavascriptInterface
        fun openWebUrl(url: String): String = deviceAction.openUrl(url).toString()

        @JavascriptInterface
        fun hasUsageStatsAccess(): Boolean = deviceAction.hasUsageStatsAccess()

        @JavascriptInterface
        fun hasCalendarReadPermission(): Boolean = calendar.hasReadPermission()

        @JavascriptInterface
        fun hasCalendarWritePermission(): Boolean = calendar.hasWritePermission()

        @JavascriptInterface
        fun requestCalendarPermission(read: Boolean, write: Boolean): Boolean {
            val needed = mutableListOf<String>()
            if (read && !calendar.hasReadPermission()) needed.add(Manifest.permission.READ_CALENDAR)
            if (write && !calendar.hasWritePermission()) needed.add(Manifest.permission.WRITE_CALENDAR)
            if (needed.isEmpty()) return true
            runCatching { calendarPermissionLauncher.launch(needed.toTypedArray()) }
            return false
        }

        @JavascriptInterface
        fun readCalendarEvents(daysAhead: Int, daysBack: Int): String =
            calendar.readEventsJson(daysAhead, daysBack)

        @JavascriptInterface
        fun insertCalendarEvent(
            title: String,
            allDay: Boolean,
            year: Int,
            month: Int,
            day: Int,
            startMillis: Long,
            endMillis: Long,
            description: String,
        ): String = calendar.insertEvent(
            title, allDay, year, month, day, startMillis, endMillis, description,
        ).toString()

        @JavascriptInterface
        fun deleteCalendarEvent(eventId: Long): String = calendar.deleteOwnEvent(eventId).toString()

        @JavascriptInterface
        fun getCalendarCapabilities(): String = calendar.capabilitiesJson()

        @JavascriptInterface
        fun getLocationCapabilities(): String = locationBridge.capabilitiesJson()

        @JavascriptInterface
        fun hasLocationPermission(): Boolean = locationBridge.hasAny()

        @JavascriptInterface
        fun requestLocationPermission(): Boolean {
            if (locationBridge.hasAny()) return true
            val needed = mutableListOf<String>()
            if (!locationBridge.hasFine()) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!locationBridge.hasCoarse()) needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (needed.isEmpty()) return true
            runCatching { locationPermissionLauncher.launch(needed.toTypedArray()) }
            return false
        }

        @JavascriptInterface
        fun readLocation(): String = locationBridge.readJson().toString()

        @JavascriptInterface
        fun refreshLocation() {
            locationBridge.refresh()
        }

        @JavascriptInterface
        fun getLocationDiagnostics(): String = locationBridge.diagnosticsJson()

        @JavascriptInterface
        fun getHealthCapabilities(): String = healthBridge.capabilitiesJson()

        @JavascriptInterface
        fun hasHealthPermission(): Boolean = healthBridge.hasSleepPermission() || healthPermissionGranted

        @JavascriptInterface
        fun requestHealthPermission(): Boolean {
            if (healthBridge.hasSleepPermission()) return true
            if (!healthBridge.isAvailable()) return false
            return runCatching {
                healthPermissionLauncher.launch(setOf(HealthBridge.PERM_SLEEP))
                false
            }.getOrDefault(false)
        }

        @JavascriptInterface
        fun readSleep(): String = healthBridge.readJson().toString()

        @JavascriptInterface
        fun refreshHealth() {
            healthBridge.refresh()
        }

        @JavascriptInterface
        fun openHealthSettings(): Boolean = healthBridge.openHealthSettings()

        @JavascriptInterface
        fun getHealthDiagnostics(): String = healthBridge.diagnosticsJson()

        @JavascriptInterface
        fun getDeviceActionCapabilities(): String = deviceAction.capabilitiesJson()

        @JavascriptInterface
        fun openSystemSettings(which: String): Boolean = deviceAction.openSystemSettings(which)

        @JavascriptInterface
        fun notify(title: String, body: String, avatarUrl: String, sessionId: String?): Boolean =
            notifyForIdentity(title, body, avatarUrl, sessionId, "")

        @JavascriptInterface
        fun notifyForIdentity(title: String, body: String, avatarUrl: String, sessionId: String?, identityId: String): Boolean = runCatching {
            ensureMessageChannel()
            val manager = getSystemService(NotificationManager::class.java)
            val icon = loadAvatarBitmap(avatarUrl)
            val builder = NotificationCompat.Builder(this@MainActivity, CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.ifBlank { "小手机" })
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(sessionId))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            if (icon != null) builder.setLargeIcon(icon)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setColor(0xFF5B7CFA.toInt())
            }
            val sid = sessionId?.trim().orEmpty()
            if (sid.isNotEmpty()) {
                val remoteInput = androidx.core.app.RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY_TEXT)
                    .setLabel("回复")
                    .build()
                val replyIntent = Intent(this@MainActivity, NotificationReplyReceiver::class.java).apply {
                    action = NotificationReplyReceiver.ACTION_REPLY
                    putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sid)
                    putExtra(NotificationReplyReceiver.EXTRA_IDENTITY_ID, identityId)
                    // Intent data, unlike extras, participates in PendingIntent identity.
                    data = Uri.parse("floatshell://reply/${Uri.encode(identityId)}/${Uri.encode(sid)}")
                }
                val replyPending = PendingIntent.getBroadcast(
                    this@MainActivity,
                    sid.hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(R.drawable.ic_stat, "回复", replyPending)
                        .addRemoteInput(remoteInput)
                        .setAllowGeneratedReplies(true)
                        .build(),
                )
            }
            manager.notify(webNotifId++, builder.build())
            if (webNotifId > 900) webNotifId = 500
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun downloadUrl(url: String, fileName: String): Boolean = runCatching {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@runCatching false
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeDownloadName(fileName))
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun startCallOverlay(name: String, avatar: String, meta: String, callId: String, elapsedSeconds: Int): Boolean {
            if (!CallOverlayService.canDraw(this@MainActivity)) return false
            val avatarRef = CallOverlayService.prepareAvatarRef(this@MainActivity, avatar)
            return CallOverlayService.start(this@MainActivity, name, avatarRef, meta, callId, elapsedSeconds)
        }

        @JavascriptInterface
        fun getOverlayDebugInfo(): String = CallOverlayService.debugInfo(this@MainActivity)

        @JavascriptInterface
        fun showCallOverlay() {
            runCatching { CallOverlayService.show(this@MainActivity) }
        }

        @JavascriptInterface
        fun hideCallOverlay() {
            runCatching { CallOverlayService.hide(this@MainActivity) }
        }

        @JavascriptInterface
        fun updateCallOverlay(name: String, avatar: String, meta: String, elapsedSeconds: Int) {
            val avatarRef = CallOverlayService.prepareAvatarRef(this@MainActivity, avatar)
            runCatching { CallOverlayService.update(this@MainActivity, name, avatarRef, meta, elapsedSeconds) }
        }

        @JavascriptInterface
        fun updateCallOverlayPending(count: Int) {
            runCatching { CallOverlayService.updatePending(this@MainActivity, count) }
        }

        @JavascriptInterface
        fun stopCallOverlay() {
            runCatching { CallOverlayService.stop(this@MainActivity) }
        }

        @JavascriptInterface
        fun getOverlayThemeJson(): String = CallOverlayService.currentThemeJson(this@MainActivity)

        @JavascriptInterface
        fun setOverlayThemeJson(json: String) {
            runCatching { CallOverlayService.setThemeJson(this@MainActivity, json) }
        }

        @JavascriptInterface
        fun canDrawOverlay(): Boolean = CallOverlayService.canDraw(this@MainActivity)

        @JavascriptInterface
        fun requestOverlayPermission(): Boolean = launchPermissionSettings("overlay")

        @JavascriptInterface
        fun isAccessibilityConnected(): Boolean = CallControlService.connected

        @JavascriptInterface
        fun openAccessibilitySettings(): Boolean = launchPermissionSettings("accessibility")

        @JavascriptInterface
        fun openPermissionAppInfo(): Boolean = launchPermissionSettings("app")

        @JavascriptInterface
        fun getScreenshotCapabilities(): String = ScreenshotBridge.capabilitiesJson()

        @JavascriptInterface
        fun captureScreenshot(): String = ScreenshotBridge.capture().toString()

        @JavascriptInterface
        fun getCheckInCapabilities(): String = CheckInService.capabilitiesJson(this@MainActivity)

        @JavascriptInterface
        fun getScreenshotDiagnostics(): String = ScreenshotBridge.diagnosticsJson()

        @JavascriptInterface
        fun getCheckInDiagnostics(): String = CheckInService.diagnosticsJson(this@MainActivity)

        @JavascriptInterface
        fun consumePendingUserShot(): String = CheckInService.consumePendingUserShot()

        @JavascriptInterface
        fun getScreenshotBallCapabilities(): String = ScreenshotBallService.capabilitiesJson()

        @JavascriptInterface
        fun setScreenshotBallStyle(json: String): Boolean =
            ScreenshotBallService.setStyle(this@MainActivity, json)

        @JavascriptInterface
        fun getScreenshotBallStyle(): String =
            ScreenshotBallService.styleJson(this@MainActivity)

        @JavascriptInterface
        fun setScreenshotShareTargets(json: String): Boolean =
            CheckInService.setShareTargets(this@MainActivity, json)

        @JavascriptInterface
        fun getScreenshotShareTargets(): String =
            CheckInService.shareTargetsJson(this@MainActivity)

        @JavascriptInterface
        fun prepareScreenshotBall(): Boolean = ScreenshotBallService.prepare(this@MainActivity)

        @JavascriptInterface
        fun stopScreenshotBall() {
            ScreenshotBallService.stop(this@MainActivity)
        }

        @JavascriptInterface
        fun prepareCheckIn(): Boolean = CheckInService.prepare(this@MainActivity)

        @JavascriptInterface
        fun stopCheckIn() {
            CheckInService.stop(this@MainActivity)
        }

        @JavascriptInterface
        fun consumePendingReplies(): String = NotificationReplyReceiver.consumePendingReplies(this@MainActivity)

        @JavascriptInterface
        fun requestCheckIn(characterId: String, name: String, avatar: String) {
            CheckInService.show(this@MainActivity, characterId, name, avatar)
        }

        @JavascriptInterface
        fun getCheckInTheme(): String = CheckInOverlayTheme.themeJson(this@MainActivity)

        @JavascriptInterface
        fun setCheckInTheme(json: String): Boolean =
            CheckInOverlayTheme.setTheme(this@MainActivity, json)

        @JavascriptInterface
        fun requestNotificationPermission() {
            runOnUiThread { if (Build.VERSION.SDK_INT >= 33) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }

        @JavascriptInterface
        fun getExtendedSnapshot(): String {
            val result = org.json.JSONObject()
            val opts = getSharedPreferences("realtime_features", Context.MODE_PRIVATE)
            result.put("nfcSupported", android.nfc.NfcAdapter.getDefaultAdapter(this@MainActivity) != null)
            val contactsGranted = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            result.put("contactsGranted", contactsGranted)
            if (opts.getBoolean("enabled", false) && opts.getBoolean("thermal", false)) runCatching {
                val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val temp = battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                if (temp >= 0) result.put("batteryTemperature", temp / 10.0)
            }
            if (contactsGranted && opts.getBoolean("enabled", false) && opts.getBoolean("contacts", false)) runCatching {
                val contacts = org.json.JSONArray()
                contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null,
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
                    while (contacts.length() < 20 && cursor.moveToNext()) {
                        contacts.put(org.json.JSONObject().put("name", cursor.getString(0)).put("number", cursor.getString(1)))
                    }
                }
                result.put("contacts", contacts)
            }
            return result.toString()
        }

        @JavascriptInterface
        fun testOverlay(): Boolean {
            if (!Settings.canDrawOverlays(this@MainActivity)) return false
            runOnUiThread {
                runCatching {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val message = android.widget.TextView(this@MainActivity).apply {
                        text = "没心机 · 感知测试\n悬浮窗已接通（3 秒后关闭）"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 14f
                        setPadding(28, 22, 28, 22)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.rgb(63, 93, 77)); cornerRadius = 24f
                        }
                    }
                    val params = android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT
                    ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; y = 110 }
                    wm.addView(message, params)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { wm.removeView(message) } }, 3000)
                }
            }
            return true
        }

        @JavascriptInterface
        fun getDeviceInfo(): String = org.json.JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE).toString()

        @JavascriptInterface
        fun openAppSettings() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun requestIgnoreBatteryOptimization() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        @JavascriptInterface
        fun saveBase64File(fileName: String, base64: String): Boolean = runCatching {
            val safeName = fileName
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
                .ifBlank { "download" }
                .take(120)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, guessMimeType(safeName))
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return@runCatching false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, safeName)).use { it.write(bytes) }
            }
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun launchExternalApp(packageName: String, dataUrl: String): Boolean = runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return@runCatching false
            if (dataUrl.isNotBlank()) intent.data = Uri.parse(dataUrl)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun openUrl(url: String): Boolean = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    private fun ensureMessageChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "角色消息", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "角色发来的消息"
                },
            )
        }
    }

    private fun loadAvatarBitmap(avatarUrl: String): Bitmap? {
        val value = avatarUrl.trim()
        if (value.isEmpty()) return null
        return runCatching {
            if (value.startsWith("data:image/")) decodeDataUrlBitmap(value) else fetchRemoteAvatar(value)
        }.getOrNull()
    }

    private fun decodeDataUrlBitmap(dataUrl: String): Bitmap? = runCatching {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return@runCatching null
        val header = dataUrl.substring(0, comma)
        if (!header.contains("base64", ignoreCase = true)) return@runCatching null
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private fun fetchRemoteAvatar(url: String): Bitmap? = runCatching {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@runCatching null
        val builder = Request.Builder().url(url)
        val cookie = CookieManager.getInstance().getCookie(url)
        if (!cookie.isNullOrEmpty()) builder.header("Cookie", cookie)
        avatarClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body?.bytes() ?: return@runCatching null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    private fun guessMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun launchPermissionSettings(which: String): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        runOnUiThread {
            val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            val routes = when (which) {
                "overlay" -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), appInfo)
                "accessibility" -> listOf(
                    Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName(this, CallControlService::class.java).flattenToString()),
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appInfo)
                else -> listOf(appInfo)
            }
            try {
                for (route in routes) {
                    try { startActivity(route.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); opened.set(true); break }
                    catch (_: Exception) { }
                }
                if (!opened.get()) Toast.makeText(this, "请从系统设置 → 应用 → 没心机·本地版手动授权", Toast.LENGTH_LONG).show()
            } finally { done.countDown() }
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return opened.get()
    }

    private inner class PhoneWidgetBridge {

        @JavascriptInterface
        fun isSupported(): Boolean = android.appwidget.AppWidgetManager.getInstance(this@MainActivity).isRequestPinAppWidgetSupported

        @JavascriptInterface
        fun update(html: String, widthDp: Int, heightDp: Int, scale: Int): Boolean = runCatching {
            PhoneWidgetProvider.saveSize(this@MainActivity, widthDp, heightDp, scale)
            PhoneWidgetRenderer.renderAndPush(this@MainActivity, html, widthDp, heightDp, scale)
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun refresh(): Boolean = runCatching {
            val (w, h, scale) = PhoneWidgetProvider.loadSize(this@MainActivity)
            PhoneWidgetRenderer.renderCached(this@MainActivity, w, h, scale)
            true
        }.getOrDefault(false)

        @JavascriptInterface
        fun isPlaced(): Boolean = PhoneWidgetProvider.isPlaced(this@MainActivity)

        @JavascriptInterface
        fun diagnostics(): String = PhoneWidgetRenderer.diagnosticsJson(this@MainActivity)

        @JavascriptInterface
        fun requestPin(): Boolean = runCatching {
            PhoneWidgetProvider.requestPin(this@MainActivity)
            true
        }.getOrDefault(false)
    }
}

package app.floatphone.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Only observes radio display information while the opt-in page/app is visible. */
@Suppress("DEPRECATION")
class NetworkDetails(private val context: Context) {
    private val displays = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val listeners = mutableMapOf<Int, Pair<TelephonyManager, PhoneStateListener>>()
    private fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (!granted() || Build.VERSION.SDK_INT < 30) return
        try {
            val subs = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            for (sub in subs) if (!listeners.containsKey(sub.subscriptionId)) {
                val tm = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(sub.subscriptionId)
                val listener = object : PhoneStateListener() {
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) { displays[sub.subscriptionId] = info.overrideNetworkType }
                }
                tm.listen(listener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
                listeners[sub.subscriptionId] = tm to listener
            }
        } catch (_: SecurityException) { stop() } catch (_: Exception) { stop() }
    }
    fun stop() { for ((tm,l) in listeners.values) runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) }; listeners.clear(); displays.clear() }
    private fun label(type: Int, override: Int?): String = when {
        type == TelephonyManager.NETWORK_TYPE_NR || (Build.VERSION.SDK_INT >= 30 && override in setOf(TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)) -> "5G"
        type == TelephonyManager.NETWORK_TYPE_LTE || override == 1 || override == 2 -> "4G"
        type in setOf(3,5,6,8,9,10,12,14,15,17) -> "3G"
        type in setOf(1,2,4,7,11,16) -> "2G"
        else -> "未知（系统未提供）"
    }
    @android.annotation.SuppressLint("MissingPermission")
    fun snapshot(): JSONObject {
        val out = JSONObject().put("phoneGranted", granted())
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val all = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            val wifi = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            val vpn = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            val type = when { caps == null -> "none"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"; vpn && wifi -> "wifi"; vpn -> "vpn"; else -> "other" }
            out.put("type",type).put("wifi",wifi).put("vpn",vpn).put("metered",cm.isActiveNetworkMetered)
            val sims = JSONArray()
            if (granted()) {
                val manager = context.getSystemService(TelephonyManager::class.java)
                val active = SubscriptionManager.getDefaultDataSubscriptionId()
                val subs = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
                for (sub in subs) {
                    val tm = manager.createForSubscriptionId(sub.subscriptionId)
                    val dataType = runCatching { tm.dataNetworkType }.getOrDefault(0)
                    val voiceType = runCatching { tm.voiceNetworkType }.getOrDefault(0)
                    sims.put(JSONObject().put("slot",sub.simSlotIndex+1).put("carrier",sub.carrierName?.toString().orEmpty()).put("generation",label(if(dataType != 0) dataType else voiceType,displays[sub.subscriptionId])).put("dataActive",sub.subscriptionId==active))
                }
            }
            out.put("sims",sims)
        } catch (_: SecurityException) { out.put("limited",true) } catch (_: Exception) { out.put("unavailable",true) }
        return out
    }
}

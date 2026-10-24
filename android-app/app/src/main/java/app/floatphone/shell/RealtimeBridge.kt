package app.floatphone.shell

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Additional opt-in signals. Never runs a background polling service. */
class RealtimeBridge(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("realtime_features", Context.MODE_PRIVATE)
    companion object {
        @Volatile var notificationSummary = JSONArray()
        @Volatile var lastShared = ""
        @Volatile var lastNfc = ""
    }
    private fun enabled(key: String): Boolean = prefs.getBoolean("enabled", false) && prefs.getBoolean(key, false)
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    fun configure(json: String): Boolean = runCatching {
        val obj = JSONObject(json)
        val editor = prefs.edit().putBoolean("enabled", obj.optBoolean("enabled", false))
        for (key in listOf("screen", "thermal", "storage", "media", "audio", "notifications", "notificationCount", "phone", "volume", "bluetooth", "nfc", "share", "nextAlarm", "vibrate", "contacts")) editor.putBoolean(key, obj.optBoolean(key, false))
        editor.apply()
        activity.runOnUiThread { pauseNfc(); resumeNfc() }
        MediaControlListenerService.current()?.refreshRealtimeSummaries()
        if (!enabled("notifications")) notificationSummary = JSONArray()
        if (!enabled("share")) lastShared = ""
        if (!enabled("nfc")) lastNfc = ""
        true
    }.getOrDefault(false)
    fun permission(key: String) {
        activity.runOnUiThread {
            when (key) {
                "bluetooth" -> if (Build.VERSION.SDK_INT >= 31) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 408)
                "phone" -> ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_PHONE_STATE), 409)
                "notifications", "notificationCount", "media" -> activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "nfc" -> activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                "background" -> activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}")))
            }
        }
    }
    fun consumeIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && enabled("share")) lastShared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(2000).orEmpty()
    }
    fun resumeNfc() {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        if (!enabled("nfc") || !adapter.isEnabled) return
        // Read only while this app is visible, only the tag ID, no writes.
        adapter.enableReaderMode(activity, { tag -> lastNfc = tag.id.joinToString("") { "%02x".format(it) } }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }
    fun pauseNfc() { runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) } }
    fun clear() { notificationSummary = JSONArray(); lastShared = ""; lastNfc = "" }
    fun vibrate(): Boolean {
        if (!enabled("vibrate")) return false
        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity).setTitle("角色想让手机震动一下")
                .setMessage("允许短震动 200 毫秒？").setNegativeButton("取消", null)
                .setPositiveButton("允许") { _, _ ->
                    @Suppress("DEPRECATION") val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                }.show()
        }
        return true
    }
    // HEADSET is the documented profile id. SDK 36 annotates this parameter as ConnectionState.
    @android.annotation.SuppressLint("WrongConstant")
    private fun bluetoothState(): JSONObject? {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            @Suppress("DEPRECATION") val adapter = BluetoothAdapter.getDefaultAdapter()
            JSONObject().put("supported", adapter != null).put("enabled", adapter?.isEnabled == true)
                .put("headsetConnected", adapter?.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothProfile.STATE_CONNECTED)
        } catch (_: SecurityException) { null }
    }

    fun snapshot(): String {
        val result = JSONObject().put("native", true).put("sdk", Build.VERSION.SDK_INT)
        val notificationAccess = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)
        result.put("grants", JSONObject().put("notifications", notificationAccess)
            .put("bluetooth", Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT))
            .put("phone", granted(Manifest.permission.READ_PHONE_STATE))
            .put("nfc", NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true))
        if (!prefs.getBoolean("enabled", false)) return result.toString()
        fun read(key: String, block: () -> Any?) { if (enabled(key)) runCatching { block()?.let { result.put(key, it) } } }
        read("screen") { JSONObject().put("locked", (activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked).put("interactive", (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) }
        read("thermal") { if (Build.VERSION.SDK_INT >= 29) (activity.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else null }
        read("storage") { val stat = StatFs(Environment.getDataDirectory().absolutePath); JSONObject().put("freeBytes", stat.availableBytes).put("totalBytes", stat.totalBytes) }
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        read("volume") { JSONObject().put("media", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).put("max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).put("ringer", audio.ringerMode) }
        read("audio") { JSONArray(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device -> when (device.type) { AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙耳机"; AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "有线耳机"; AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"; else -> "其他输出" } }.distinct()) }
        read("bluetooth") { bluetoothState() }
        read("media") { if (!notificationAccess) null else { val manager = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager; val controller = manager.getActiveSessions(MediaControlListenerService.tokenFor(activity)).firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }; controller?.metadata?.let { JSONObject().put("title", it.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().take(160)).put("artist", it.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().take(100)) } } }
        read("notificationCount") { if (notificationAccess) MediaControlListenerService.current()?.activeNotifications?.count { it.packageName != activity.packageName } else null }
        read("notifications") { if (notificationAccess) notificationSummary else null }
        read("phone") { if (!granted(Manifest.permission.READ_PHONE_STATE)) null else { @Suppress("DEPRECATION") val state = (activity.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState; when (state) { TelephonyManager.CALL_STATE_RINGING -> "来电中"; TelephonyManager.CALL_STATE_OFFHOOK -> "通话中"; else -> "空闲" } } }
        read("nextAlarm") { (activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime }
        read("share") { lastShared.takeIf { it.isNotBlank() } }
        read("nfc") { lastNfc.takeIf { it.isNotBlank() } }
        return result.toString()
    }
}

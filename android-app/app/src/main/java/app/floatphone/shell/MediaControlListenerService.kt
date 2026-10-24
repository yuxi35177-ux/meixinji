package app.floatphone.shell

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * 一个「什么都不做」的通知监听服务。
 *
 * 为什么需要它：Android 只允许两类应用读取活跃媒体会话（MediaSessionManager.getActiveSessions）——
 * 系统签名的应用，和持有「通知使用权」的应用。拿到活跃会话之后才能：
 *   · 通过 MediaController 直接下发播放/暂停，比盲发媒体按键可靠得多；
 *   · 读回当前播放状态，确认指令到底有没有生效。
 *
 * 默认不读取通知内容。用户在「实时感知」里明确开启通知感知后，才读取最多 10 条
 * 活跃通知的应用名、标题与正文供本机角色参考；关闭后立即清空摘要。
 * 同一服务也让 MediaSessionManager 允许读取活跃媒体会话。
 */
class MediaControlListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: MediaControlListenerService? = null

        /** 当前连接实例；用户没授权、或系统还没绑定时为 null。 */
        fun current(): MediaControlListenerService? = instance

        /** 传给 MediaSessionManager.getActiveSessions 的调用标识。 */
        fun tokenFor(context: Context): ComponentName =
            ComponentName(context, MediaControlListenerService::class.java)
    }

    override fun onListenerConnected() {
        instance = this
        refreshRealtimeSummaries()
    }

    override fun onListenerDisconnected() {
        // 只清自己：断开回调可能来自另一个实例，无脑置 null 会把新连接抹掉
        if (instance === this) instance = null
    }
    fun refreshRealtimeSummaries() {
        runCatching {
            val prefs = getSharedPreferences("realtime_features", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean("notifications", false)) {
                RealtimeBridge.notificationSummary = org.json.JSONArray()
                return@runCatching
            }
            val rows = activeNotifications.orEmpty().asSequence()
                .filter { it.packageName != packageName }
                .toList()
                .takeLast(10)
                .map { sbn ->
                    org.json.JSONObject().put("app", sbn.packageName)
                        .put("title", sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty().take(160))
                        .put("text", sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty().take(240))
                }.toList()
            RealtimeBridge.notificationSummary = org.json.JSONArray(rows)
        }
    }
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) { refreshRealtimeSummaries() }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) { refreshRealtimeSummaries() }

}

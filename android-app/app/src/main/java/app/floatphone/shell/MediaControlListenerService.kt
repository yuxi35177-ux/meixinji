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
                .sortedByDescending { it.postTime }.take(20)
                .map { sbn ->
                    val n = sbn.notification
                    val e = n.extras
                    fun field(key: String) = e.getCharSequence(key)?.toString().orEmpty()
                    val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                    val lines = e.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
                    val messages = e.getParcelableArray("android.messages").orEmpty().mapNotNull { it as? android.os.Bundle }.takeLast(10).map { msg ->
                        val sender = if (android.os.Build.VERSION.SDK_INT >= 28) msg.getParcelable<android.app.Person>("sender_person")?.name?.toString().orEmpty().ifBlank { msg.getCharSequence("sender")?.toString().orEmpty() } else msg.getCharSequence("sender")?.toString().orEmpty()
                        org.json.JSONObject().put("sender",sender).put("text",msg.getCharSequence("text")?.toString().orEmpty().take(1200)).put("at",msg.getLong("time",0))
                    }
                    org.json.JSONObject().put("app",sbn.packageName).put("appName",appName).put("at",sbn.postTime)
                        .put("title",field(android.app.Notification.EXTRA_TITLE).take(300))
                        .put("text",field(android.app.Notification.EXTRA_TEXT).take(1200))
                        .put("bigText",field(android.app.Notification.EXTRA_BIG_TEXT).take(4000))
                        .put("subText",field(android.app.Notification.EXTRA_SUB_TEXT).take(300))
                        .put("summary",field(android.app.Notification.EXTRA_SUMMARY_TEXT).take(600))
                        .put("lines",org.json.JSONArray(lines.takeLast(12).map { it.take(1200) }))
                        .put("messages",org.json.JSONArray(messages))
                        .put("ongoing",sbn.isOngoing).put("groupSummary", n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
                }.toList()
            RealtimeBridge.notificationSummary = org.json.JSONArray(rows)
        }
    }
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) { refreshRealtimeSummaries() }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) { refreshRealtimeSummaries() }

}

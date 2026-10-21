package com.uxspace.system

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.uxspace.privileged.PrivilegedService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's notifications, as the taskbar tray and its notification panel show them.
 *
 * Fed by [UxNotificationListener]. Notification access is normally a manual opt-in in
 * Settings; UxSpace has its privileged helper grant it instead (`cmd notification
 * allow_listener`) as soon as the helper is up, so the tray just works.
 */
object NotificationCenter {

    /** One notification, flattened to what the tray needs. */
    data class Item(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postTime: Long,
        val smallIcon: Icon?,
        val largeIcon: Icon?,
        val clearable: Boolean,
        val autoCancel: Boolean,
        val contentIntent: PendingIntent?,
    )

    /** Notified on the main thread when the list or the connection state changes. */
    fun interface Listener {
        fun onNotificationsChanged()
    }

    private const val TAG = "UxSpace/Notifications"

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile
    private var service: UxNotificationListener? = null

    /** Newest first. */
    @Volatile
    var items: List<Item> = emptyList()
        private set

    /** Whether the listener is bound, i.e. notification access is granted. */
    val connected: Boolean get() = service != null

    /** Call once from the Application. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PrivilegedService.addListener {
            if (PrivilegedService.state == PrivilegedService.State.READY && !connected) {
                PrivilegedService.grantNotificationListener()
            }
        }
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    /**
     * The notification's small icon tinted [tint] (small icons are monochrome masks), else its
     * app's full-colour icon, else null. Small icons often live in packages UxSpace can't see
     * (Android package visibility); those fail quietly here.
     */
    fun iconFor(item: Item, context: Context, tint: Int): Drawable? =
        runCatching { item.smallIcon?.loadDrawable(context) }.getOrNull()
            ?.mutate()?.apply { setTint(tint) }
            ?: runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull()

    /** Dismiss one notification (no-op for ongoing ones the system won't clear). */
    fun dismiss(key: String) {
        runCatching { service?.cancelNotification(key) }
    }

    /** Dismiss every clearable notification. */
    fun dismissAll() {
        runCatching { service?.cancelAllNotifications() }
    }

    internal fun onConnected(listener: UxNotificationListener) {
        service = listener
        Log.i(TAG, "notification listener connected")
        refresh()
    }

    internal fun onDisconnected() {
        service = null
        items = emptyList()
        Log.i(TAG, "notification listener disconnected")
        notifyChanged()
    }

    internal fun refresh() {
        val svc = service ?: return
        val active = runCatching { svc.activeNotifications }.getOrNull().orEmpty()
        items = active
            .filter { show(it) }
            .sortedByDescending { it.postTime }
            .map { it.toItem() }
        notifyChanged()
    }

    /** Skip our own, group summaries and anything without text. */
    private fun show(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == appContext?.packageName) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = n.extras
        return !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    private fun StatusBarNotification.toItem(): Item {
        val n = notification
        val extras = n.extras
        val pm = appContext?.packageManager
        val label = runCatching {
            pm?.let { it.getApplicationLabel(it.getApplicationInfo(packageName, 0)).toString() }
        }.getOrNull() ?: packageName
        return Item(
            key = key,
            packageName = packageName,
            appLabel = label,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                )?.toString().orEmpty(),
            postTime = postTime,
            smallIcon = n.smallIcon,
            largeIcon = n.getLargeIcon(),
            clearable = isClearable,
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0,
            contentIntent = n.contentIntent,
        )
    }

    private fun notifyChanged() {
        mainHandler.post { listeners.forEach { runCatching { it.onNotificationsChanged() } } }
    }
}

/**
 * Bound by the system once notification access is granted; forwards everything to
 * [NotificationCenter].
 */
class UxNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() = NotificationCenter.onConnected(this)

    override fun onListenerDisconnected() = NotificationCenter.onDisconnected()

    override fun onNotificationPosted(sbn: StatusBarNotification?) = NotificationCenter.refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = NotificationCenter.refresh()
}

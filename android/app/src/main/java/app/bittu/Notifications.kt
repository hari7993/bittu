package app.bittu

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets Bittu see the phone's notifications: who messaged, in which app, and what it says.
 * The owner switches this on in Android's "Notification access" settings. Nothing here leaves the phone
 * unless the owner asks Bittu about their notifications.
 */
class Notifications : NotificationListenerService() {
    private var listed = listOf<StatusBarNotification>()
    private val told = HashSet<String>()

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { instance = null }

    private fun appName(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
    private fun body(n: Notification) = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""

    /** A new message from a person: tell the assistant, which decides whether to say so. */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName !in MESSAGING || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val who = title(n)
        if (who.isBlank() || body(n).isBlank()) return
        if (!told.add(sbn.key + body(n).hashCode())) return
        if (told.size > 300) told.clear()
        WakeService.instance?.announce(appName(sbn.packageName), who)
    }

    /** Current notifications, newest first, numbered so they can be replied to. */
    fun list(app: String): String {
        val want = app.trim().lowercase()
        listed = (runCatching { activeNotifications.toList() }.getOrDefault(emptyList()))
            .filter { !it.isOngoing && it.packageName != packageName && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { title(it.notification).isNotBlank() || body(it.notification).isNotBlank() }
            .filter { want.isEmpty() || appName(it.packageName).lowercase().contains(want) || it.packageName.contains(want) }
            .sortedByDescending { it.postTime }.take(25)
        if (listed.isEmpty()) return if (want.isEmpty()) "There are no notifications right now." else "There are no notifications from $app right now."
        val now = System.currentTimeMillis()
        return listed.mapIndexed { i, s ->
            val mins = (now - s.postTime) / 60000
            val age = if (mins < 1) "just now" else if (mins < 60) "$mins min ago" else "${mins / 60} h ago"
            "#${i + 1} ${appName(s.packageName)} | ${title(s.notification)}: ${body(s.notification).take(300)} ($age)${if (replyAction(s) != null) " [can reply]" else ""}"
        }.joinToString("\n")
    }

    private fun replyAction(s: StatusBarNotification) = s.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    /** Sends a reply through the notification's own reply box, the way the owner would from the shade. */
    fun reply(number: Int, text: String): String {
        val s = listed.getOrNull(number - 1) ?: return "There is no notification number $number. Read the notifications again first."
        val action = replyAction(s) ?: return "That notification from ${appName(s.packageName)} cannot be replied to directly."
        val inputs = action.remoteInputs ?: return "That notification cannot be replied to."
        val intent = Intent()
        val bundle = Bundle()
        for (r in inputs) bundle.putCharSequence(r.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, bundle)
        return try { action.actionIntent.send(this, 0, intent); "Replied to ${title(s.notification)} on ${appName(s.packageName)}." } catch (e: Exception) { "The reply could not be sent: ${e.message}" }
    }

    companion object {
        @Volatile var instance: Notifications? = null
        private val MESSAGING = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.instagram.android", "com.google.android.apps.messaging", "com.android.mms", "com.facebook.orca", "org.thoughtcrime.securesms")
    }
}

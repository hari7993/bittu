package app.bittu

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Screen control: lets Bittu read what is on screen and tap or type in other apps, the way a person would.
 * The owner switches it on in Android's Accessibility settings. Bittu uses it only while carrying out a command.
 */
class ScreenControl : AccessibilityService() {
    private val watch = android.os.Handler(android.os.Looper.getMainLooper())
    // This service is always alive, so it also keeps an eye on the helper: if it has stopped, Bittu starts it again.
    private val check = object : Runnable {
        override fun run() {
            ShellLink.init(applicationContext)
            if (!ShellLink.connected) { ShellLink.connect(); AdbStarter.ensure(applicationContext) }
            watch.postDelayed(this, 20_000)
        }
    }

    override fun onServiceConnected() { instance = this; WakeService.startIfWanted(this); watch.removeCallbacks(check); watch.postDelayed(check, 5000) }

    /** Removing Bittu from the recent apps list must not stop it: listening starts again here. */
    override fun onTaskRemoved(rootIntent: Intent?) { WakeService.startIfWanted(this) }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; watch.removeCallbacks(check); return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun walk(node: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (visit(node)) return node
        for (i in 0 until node.childCount) walk(node.getChild(i), visit)?.let { return it }
        return null
    }

    private fun label(n: AccessibilityNodeInfo) = (n.text?.toString() ?: "").ifEmpty { n.contentDescription?.toString() ?: "" }

    /** Taps the first thing on screen whose words match. Keeps looking for a short while, because screens take time to open. */
    /** The package of the app on screen right now. */
    fun front(): String = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull().orEmpty()

    /**
     * Taps the item whose words sound most like what was said, when no item matches exactly. Returns the words of the
     * item that was tapped, or null. Only a clearly close item is tapped; a wrong tap is worse than none.
     */
    fun tapLike(said: String, waitMs: Int): String? {
        val until = System.currentTimeMillis() + waitMs
        do {
            if (tap(said, 0)) return said
            var best: AccessibilityNodeInfo? = null; var bestScore = 0.0; var second = 0.0; var bestLabel = ""
            walk(rootInActiveWindow) { n ->
                val l = label(n)
                if (l.length in 2..60 && n.isVisibleToUser) { val sc = Tools.alike(said, l); if (sc > bestScore) { second = bestScore; bestScore = sc; best = n; bestLabel = l } else if (sc > second) second = sc }
                false
            }
            if (best != null && bestScore >= 0.62 && bestScore - second >= 0.08) {
                var target = best
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return bestLabel
            }
            Thread.sleep(250)
        } while (System.currentTimeMillis() < until)
        return null
    }

    fun tap(words: String, waitMs: Int): Boolean {
        val until = System.currentTimeMillis() + waitMs
        do {
            val hit = walk(rootInActiveWindow) { label(it).equals(words, true) } ?: walk(rootInActiveWindow) { label(it).contains(words, true) && it.isVisibleToUser }
            var target = hit
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            Thread.sleep(250)
        } while (System.currentTimeMillis() < until)
        return false
    }

    /** Types into the box that has the cursor, or the first text box on screen. */
    fun type(text: String): Boolean {
        val box = walk(rootInActiveWindow) { it.isEditable && it.isFocused } ?: walk(rootInActiveWindow) { it.isEditable } ?: return false
        return box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
    }

    /** The words currently on screen, one item per line, marked when they can be tapped or typed into. */
    fun read(): String {
        val out = StringBuilder()
        walk(rootInActiveWindow) { n ->
            val l = label(n)
            if (l.isNotBlank() && n.isVisibleToUser && out.length < 6000) out.append(if (n.isEditable) "[box] " else if (n.isClickable) "[tap] " else "").append(l.take(120)).append('\n')
            false
        }
        return out.toString()
    }

    /** Scrolls the list on screen. Falls back to a swipe, which is what moves reels and stories. */
    fun scroll(down: Boolean): Boolean {
        val list = walk(rootInActiveWindow) { it.isScrollable && it.isVisibleToUser }
        if (list != null && list.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        val m = resources.displayMetrics
        val path = android.graphics.Path().apply { moveTo(m.widthPixels / 2f, m.heightPixels * (if (down) 0.72f else 0.30f)); lineTo(m.widthPixels / 2f, m.heightPixels * (if (down) 0.28f else 0.70f)) }
        return dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 220)).build(), null, null)
    }

    fun press(what: String) = performGlobalAction(when (what) { "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS; "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; else -> GLOBAL_ACTION_BACK })

    companion object {
        @Volatile var instance: ScreenControl? = null
    }
}

package app.bittu

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.net.URLEncoder

/**
 * Direct ways into apps. Where an app can be told what to do with one link or one system request, Bittu uses that
 * instead of opening the app and tapping through it: it is done in under a second and cannot tap the wrong thing.
 */
object Links {
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    private fun quote(s: String) = s.replace("'", "%27")

    /** Starts turn-by-turn directions in Google Maps. */
    fun navigate(c: Context, place: String): String {
        if (place.isBlank()) return "Tell me where to go."
        val out = Tools.shell("am start -a android.intent.action.VIEW -d '${quote("google.navigation:q=" + enc(place))}' -p com.google.android.apps.maps") ?: return "The helper is not running, so Maps could not be started."
        return if (out.contains("Error", true)) "Maps could not start directions: ${out.lines().lastOrNull { it.isNotBlank() }?.take(100)}" else "Directions to $place are open in Maps."
    }

    /** Opens YouTube on the results for a search. The link works with whichever YouTube app is installed. */
    fun youtube(c: Context, query: String): String {
        if (query.isBlank()) return "Tell me what to look for."
        val q = enc(query)
        var out = Tools.shell("am start -a android.intent.action.VIEW -d '${quote("vnd.youtube://results?search_query=$q")}'") ?: return "The helper is not running, so YouTube could not be opened."
        // No YouTube app took the link: open the results in the browser instead.
        if (out.contains("Error", true)) out = Tools.shell("am start -a android.intent.action.VIEW -d '${quote("https://www.youtube.com/results?search_query=$q")}'") ?: out
        if (out.contains("Error", true)) return "YouTube could not be opened: ${out.lines().lastOrNull { it.isNotBlank() }?.take(100)}"
        return "YouTube is showing results for $query."
    }

    /** Sets an alarm in the phone's clock app, without opening it. */
    fun alarm(c: Context, hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return "That is not a time I can set an alarm for."
        val before = runCatching { c.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
        val started = runCatching { c.startActivity(i) }.isSuccess
        if (!started) return "The phone's clock app did not accept the alarm."
        Thread.sleep(1200)
        val after = runCatching { c.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }.getOrNull()
        val at = "%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")
        return if (after != null && after != before) "Alarm set for $at." else "I asked the clock for an alarm at $at, but I cannot see it in the phone's alarm list. Please check the Clock app."
    }

    /** Starts a countdown timer in the phone's clock app. */
    fun timer(c: Context, seconds: Int, label: String): String {
        if (seconds !in 1..86_400) return "That is not a length I can set a timer for."
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
        if (!runCatching { c.startActivity(i) }.isSuccess) return "The phone's clock app did not accept the timer."
        val mins = seconds / 60; val secs = seconds % 60
        return "Timer started for " + listOfNotNull(if (mins > 0) "$mins minute${if (mins == 1) "" else "s"}" else null, if (secs > 0) "$secs second${if (secs == 1) "" else "s"}" else null).joinToString(" and ") + "."
    }
}

package app.bittu

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Keeps Bittu's helper alive without anyone touching the phone.
 *
 * The helper is started through the phone's debugging service and lives inside it. Whenever that service restarts
 * (a USB cable or charger going in or out is the usual cause), Android stops the helper with it. Nothing short of
 * root can prevent that, so Bittu does the next best thing: it notices at once and starts the helper again, also
 * while the phone is locked and asleep. Triggers:
 *   - the connection to the helper drops                       (within seconds)
 *   - the screen turns on, the phone is unlocked, power changes (within seconds)
 *   - a call starts ringing                                     (so a call that arrives after a silent stop is still answered)
 *   - a timer that fires even in deep sleep                     (about every ten minutes)
 *   - the phone finishes starting up
 * Every attempt is written to a small diary, so a failure in the night can be read in the morning.
 */
object Keeper {
    private const val EVERY = 10 * 60 * 1000L
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun log(c: Context, text: String) = runCatching {
        val f = File(c.filesDir, "keeper.log")
        if (f.length() > 60_000) f.writeText(f.readLines().takeLast(250).joinToString("\n") + "\n")
        f.appendText("${stamp.format(Date())}  $text\n")
    }

    fun diary(c: Context): String = runCatching { File(c.filesDir, "keeper.log").readLines().takeLast(60).joinToString("\n") }.getOrDefault("")

    /** Checks the helper and starts it if it is gone. Holds the phone awake for the few seconds that takes. */
    fun kick(c: Context, why: String, delayMs: Long = 0) {
        val app = c.applicationContext
        thread(name = "bittu-keeper") {
            val lock = runCatching { app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bittu:keeper").apply { acquire(60_000) } }.getOrNull()
            try {
                if (delayMs > 0) Thread.sleep(delayMs)
                Modes.expire(app)
                ShellLink.init(app)
                if (ShellLink.connected) return@thread
                ShellLink.connect(); Thread.sleep(1500)
                if (ShellLink.connected) { log(app, "$why: the helper was running, connected again"); return@thread }
                if (!AdbStarter.paired(app)) { log(app, "$why: helper is not running and Bittu is not paired yet"); return@thread }
                val result = AdbStarter.start(app)
                log(app, "$why: helper was not running, start gave: $result")
                if (result != "running") { Thread.sleep(8000); if (!ShellLink.connected) log(app, "$why: second try gave: ${AdbStarter.start(app)}") }
            } catch (e: Throwable) { log(app, "$why: failed with $e") } finally { runCatching { lock?.release() } }
        }
    }

    /** Arms the timer that still fires when the phone is asleep. */
    fun schedule(c: Context) = runCatching {
        val pi = PendingIntent.getBroadcast(c, 91, Intent(c, KeeperAlarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + EVERY, pi)
    }
}

/** The sleep-proof timer. */
class KeeperAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Keeper.schedule(context)
        Keeper.kick(context, "timer")
        WakeService.startIfWanted(context)
    }
}

/** A call is ringing, or the phone has just started: make sure the helper is there. */
class KeeperEvents : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Keeper.schedule(context)
        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) == TelephonyManager.EXTRA_STATE_RINGING) Keeper.kick(context, "a call is ringing")
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Keeper.kick(context, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "the phone started" else "Bittu was updated", 8000)
        }
    }
}

/** Modes that were switched on "for 1 hour" or "until 7 AM" really end then, whether or not the app is open. */
object Modes {
    /** Turns the mode back to normal when its time is up. Returns true when it did. */
    @Synchronized fun expire(c: Context): Boolean {
        val p = c.getSharedPreferences("bittu", Context.MODE_PRIVATE)
        val cfg = runCatching { JSONObject(p.getString("config", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val ends = cfg.optLong("modeEnds")
        if (ends <= 0 || System.currentTimeMillis() < ends || cfg.optString("mode", "normal") in setOf("normal", "off")) return false
        val was = cfg.optString("mode")
        cfg.put("mode", "normal").put("until", "").put("modeEnds", 0).put("modeSay", "").put("modeAct", "")
        p.edit().putString("config", cfg.toString()).apply()
        ShellLink.pushConfig()
        TalkService.sink?.invoke(JSONObject().put("ev", "mode").put("mode", "normal").put("until", ""))
        Keeper.log(c, "$was mode ended on time")
        return true
    }

    /** Wakes Bittu at the moment a timed mode ends. */
    fun arm(c: Context, endsAt: Long) = runCatching {
        val pi = PendingIntent.getBroadcast(c, 92, Intent(c, KeeperAlarm::class.java).setAction("mode-end"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = c.getSystemService(AlarmManager::class.java)
        // An exact alarm ends the mode on the minute. The helper allows Bittu to set one; without that, Android may run it some minutes late.
        if (endsAt <= System.currentTimeMillis()) am.cancel(pi)
        else if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt + 1000, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt + 1000, pi)
    }
}

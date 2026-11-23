package app.bittu

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The app's connection to Bittu's helper (the part that runs as the shell user). */
object ShellLink {
    private const val PORT = 47611
    @Volatile private var out: PrintWriter? = null
    @Volatile var connected = false
        private set
    private val waiting = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val ids = AtomicInteger()
    @Volatile var onEvent: ((JSONObject) -> Unit)? = null
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }
    private fun prefs() = app.getSharedPreferences("shell", Context.MODE_PRIVATE)

    /** Connects if the helper is running. With a stale token the helper re-sends a fresh one, and we try again. */
    @Synchronized fun connect() {
        if (connected) return
        val token = prefs().getString("token", "") ?: ""
        thread(name = "bittu-link") {
            try {
                val s = Socket("127.0.0.1", PORT)
                val w = PrintWriter(s.getOutputStream().bufferedWriter())
                w.println(token.ifEmpty { "hello" }); w.flush()
                val reader = s.getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    val msg = JSONObject(line)
                    if (msg.has("ev")) {
                        if (msg.optString("ev") == "ready") { out = w; connected = true; pushConfig(); collectNotes() }
                        if (msg.optString("ev") == "note") collectNotes()
                        // A call is ringing: send where the owner is and what is in their calendar. The helper only passes it to callers with full access.
                        if (msg.optString("ev") == "call" && msg.optString("state") == "ringing") kotlin.concurrent.thread(name = "bittu-context") {
                            runCatching { request("context", JSONObject().put("data", JSONObject().put("place", Where.now(app).let { if (it.contains("Latitude")) it.substringBefore(" Latitude").trim().trimEnd('.') else "" }).put("calendar", Cal.coming(app)))) }
                        }
                        onEvent?.invoke(msg)
                    } else waiting.remove(msg.optInt("id"))?.invoke(msg)
                }
            } catch (_: Exception) {
            } finally {
                val was = connected
                connected = false; out = null
                waiting.values.forEach { it(JSONObject().put("ok", false).put("error", "The helper is not running.")) }
                waiting.clear()
                if (was) onEvent?.invoke(JSONObject().put("ev", "gone"))
                // The helper has just stopped (usually the phone's debugging service restarted). Give that service a moment, then start the helper again.
                if (was) Keeper.kick(app, "the helper stopped", 4000)
            }
        }
    }

    fun request(op: String, extra: JSONObject = JSONObject(), reply: (JSONObject) -> Unit = {}) {
        val w = out
        if (w == null) { connect(); reply(JSONObject().put("ok", false).put("error", "The helper is not running.")); return }
        val id = ids.incrementAndGet()
        waiting[id] = reply
        thread { try { synchronized(w) { w.println(extra.put("id", id).put("op", op).toString()); w.flush() } } catch (e: Exception) { waiting.remove(id)?.invoke(JSONObject().put("ok", false).put("error", e.toString())) } }
    }

    /** The same request, waiting for the answer. For the always-alive part of the app, never the screen. */
    fun requestSync(op: String, extra: JSONObject = JSONObject(), timeoutMs: Long = 8000): JSONObject {
        val done = java.util.concurrent.CountDownLatch(1)
        var answer = JSONObject().put("ok", false).put("error", "The helper did not answer in time.")
        request(op, extra) { answer = it; done.countDown() }
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return answer
    }

    /** The owner's settings, plus the Gemini key from the app's private storage, go to the helper. */
    fun pushConfig() {
        val raw = app.getSharedPreferences("bittu", Context.MODE_PRIVATE).getString("config", null) ?: return
        val cfg = JSONObject(raw)
        val keys = app.getSharedPreferences("keys", Context.MODE_PRIVATE)
        cfg.put("geminiKey", keys.getString("gemini", "") ?: "")
        // The backup for calls uses these when Gemini Live cannot be reached.
        cfg.put("anthropicKey", keys.getString("anthropic", "") ?: "").put("elevenKey", keys.getString("elevenlabs", "") ?: "")
            .put("fishKey", keys.getString("fish", "") ?: "").put("assemblyKey", keys.getString("assemblyai", "") ?: "")
        request("config", JSONObject().put("data", cfg))
    }

    /** Call notes wait in the helper until the app collects them. They are kept here until the interface reads them. */
    private fun collectNotes() = request("notes") { res ->
        val got = res.optJSONArray("notes") ?: return@request
        if (got.length() == 0) return@request
        synchronized(this) {
            val p = app.getSharedPreferences("bittu", Context.MODE_PRIVATE)
            val all = JSONArray(p.getString("pendingNotes", "[]"))
            for (i in 0 until got.length()) all.put(got.get(i))
            p.edit().putString("pendingNotes", all.toString()).apply()
        }
        onEvent?.invoke(JSONObject().put("ev", "notes"))
    }

    fun gotToken(token: String) { prefs().edit().putString("token", token).apply(); connect() }
}

/** Receives messages from the helper. Only the shell user can send to it (see the manifest). */
class ShellHello : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ShellLink.init(context)
        intent.getStringExtra("token")?.let { ShellLink.gotToken(it) }
        intent.getStringExtra("alert_who")?.let { who ->
            // Bittu is on a call and the caller needs the owner: a loud, buzzing notification that opens the live call screen.
            runCatching {
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(android.app.NotificationChannel("urgent", "Someone needs you on a call", android.app.NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800) })
                val open = android.app.PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
                nm.notify(77, android.app.Notification.Builder(context, "urgent").setSmallIcon(R.drawable.ic_bittu).setContentTitle("$who needs you now")
                    .setContentText(intent.getStringExtra("alert_why").orEmpty().ifEmpty { "Bittu is on the call. Open to take over." }).setCategory(android.app.Notification.CATEGORY_CALL).setAutoCancel(true).setContentIntent(open).build())
                context.getSystemService(android.os.Vibrator::class.java)?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500, 250, 800), -1))
            }
        }
        intent.getStringExtra("sum_who")?.let { who ->
            // A call Bittu answered has ended: who called and why, in a few words.
            runCatching {
                val text = intent.getStringExtra("sum_text").orEmpty()
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(android.app.NotificationChannel("calls", "Calls Bittu answered", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                val open = android.app.PendingIntent.getActivity(context, 8, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
                nm.notify((System.currentTimeMillis() % 100000).toInt() + 1000, android.app.Notification.Builder(context, "calls").setSmallIcon(R.drawable.ic_bittu)
                    .setContentTitle("Bittu answered $who").setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build())
            }
        }
        val to = intent.getStringExtra("sms_to")
        val text = intent.getStringExtra("sms_text")
        if (!to.isNullOrEmpty() && !text.isNullOrEmpty() && context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            runCatching { context.getSystemService(SmsManager::class.java).sendTextMessage(to, null, text, null, null) }
        }
    }
}

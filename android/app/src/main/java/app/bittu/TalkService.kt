package app.bittu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.json.JSONObject

/**
 * Keeps a conversation with Bittu alive while another app is in front, for example right after Bittu opens WhatsApp.
 * Android requires a visible notification while the microphone is used this way; it carries a Stop button.
 */
class TalkService : Service() {
    val talk by lazy {
        Talk(this) { ev ->
            sink?.invoke(ev)
            if (ev.optString("ev") == "talk" && ev.optString("state") == "closed") stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            sink?.invoke(JSONObject().put("ev", "talk").put("state", "closed"))
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        WakeService.instance?.pause() // the conversation needs the microphone
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("talk", "Talking with Bittu", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, TalkService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "talk")
            .setSmallIcon(R.drawable.ic_bittu)
            .setContentTitle("Bittu is listening")
            .setContentText("Talk any time. Tap Stop to end the conversation.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        talk.start(runCatching { JSONObject(intent?.getStringExtra("cfg") ?: "{}") }.getOrDefault(JSONObject()))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        talk.stop()
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: TalkService? = null
        /** Where conversation events go: the interface, while it is alive. */
        @Volatile var sink: ((JSONObject) -> Unit)? = null
    }
}

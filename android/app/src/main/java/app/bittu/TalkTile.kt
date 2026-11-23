package app.bittu

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService

/** A Quick Settings tile: one tap from the notification shade starts a conversation with Bittu. */
class TalkTile : TileService() {
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.TALK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
}

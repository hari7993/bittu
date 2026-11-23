package app.bittu

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The owner's calendar for the rest of today and tomorrow, in plain lines. Only ever given to callers with full access. */
object Cal {
    fun coming(c: Context): String {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return ""
        return runCatching {
            val from = System.currentTimeMillis() - 30 * 60 * 1000L // an event that started half an hour ago is still "now"
            val to = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 2); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            val day = SimpleDateFormat("EEEE", Locale.ENGLISH); val time = SimpleDateFormat("h:mm a", Locale.ENGLISH)
            val today = day.format(Date())
            val out = ArrayList<String>()
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { q ->
                while (q.moveToNext() && out.size < 8) {
                    val title = q.getString(0)?.trim().orEmpty(); if (title.isEmpty()) continue
                    val begin = q.getLong(1); val end = q.getLong(2); val allDay = q.getInt(3) == 1
                    if (end < System.currentTimeMillis()) continue
                    val d = day.format(Date(begin)).let { if (it == today) "Today" else "Tomorrow" }
                    val place = q.getString(4)?.trim().orEmpty()
                    out.add("$d ${if (allDay) "all day" else time.format(Date(begin)) + " to " + time.format(Date(end))}: $title" + if (place.isNotEmpty()) " at $place" else "")
                }
            }
            out.joinToString("\n")
        }.getOrDefault("")
    }
}

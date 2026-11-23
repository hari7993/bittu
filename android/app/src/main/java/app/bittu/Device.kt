package app.bittu

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What this phone is, so Bittu can answer questions about it without guessing. */
object Device {
    private fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    /** One line that never changes while the app runs: make, model, Android and HyperOS versions. */
    val summary: String by lazy {
        val name = prop("ro.product.marketname").ifEmpty { "${Build.MANUFACTURER} ${Build.MODEL}" }
        val skin = prop("ro.mi.os.version.name").let { if (it.isNotEmpty()) "HyperOS ${it.removePrefix("OS")} (${prop("ro.mi.os.version.incremental")})" else prop("ro.miui.ui.version.name").let { m -> if (m.isNotEmpty()) "MIUI $m" else "" } }
        "$name (${Build.MANUFACTURER} ${Build.MODEL}, code name ${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            (if (skin.isNotEmpty()) ", $skin" else "") + ", security patch ${Build.VERSION.SECURITY_PATCH}, chip ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
    }

    /** The short part of the prompt that changes: the time now. */
    fun now(): String = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())

    /** Everything that changes from minute to minute: battery, storage, memory, network. */
    fun details(c: Context): String {
        val out = StringBuilder("Phone: $summary.\n")
        runCatching {
            val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
            val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            out.append("Battery: $pct percent, ${if (charging) "charging" else "not charging"}, ${b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10} degrees Celsius.\n")
        }
        runCatching {
            val s = StatFs(Environment.getDataDirectory().path)
            out.append("Storage: %.1f GB free of %.0f GB.\n".format(Locale.US, s.availableBytes / 1e9, s.totalBytes / 1e9))
        }
        runCatching {
            val m = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            out.append("Memory: %.1f GB free of %.1f GB.\n".format(Locale.US, m.availMem / 1e9, m.totalMem / 1e9))
        }
        runCatching {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            out.append("Internet: " + when {
                caps == null -> "not connected"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "on Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "on mobile data"
                else -> "connected"
            } + ".\n")
        }
        out.append("Time: ${now()}.")
        return out.toString()
    }
}

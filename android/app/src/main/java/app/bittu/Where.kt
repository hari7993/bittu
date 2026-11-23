package app.bittu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where the phone is right now: the place name when it can be found, and the coordinates. */
object Where {
    fun now(c: Context): String {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "Bittu is not allowed to see the phone's location. The owner must allow Location for Bittu (all the time) in Settings, Apps, Bittu, Permissions."
        val lm = c.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) return "Location is switched off on the phone, so Bittu cannot tell where it is."
        var best: Location? = null
        for (p in lm.getProviders(true)) runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { if (best == null || it.time > best!!.time) best = it }
        // A fix older than ten minutes may be a different place. Ask for a fresh one, but do not keep the owner waiting long.
        if (best == null || System.currentTimeMillis() - best!!.time > 600_000) {
            val latch = CountDownLatch(1)
            val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.isProviderEnabled(it) }
            if (provider != null) runCatching {
                lm.getCurrentLocation(provider, null, c.mainExecutor) { fresh -> if (fresh != null) best = fresh; latch.countDown() }
                latch.await(6, TimeUnit.SECONDS)
            }
        }
        val at = best ?: return "The phone could not find its location just now."
        val place = runCatching {
            @Suppress("DEPRECATION") Geocoder(c, Locale.ENGLISH).getFromLocation(at.latitude, at.longitude, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea, a.adminArea, a.countryName).distinct().joinToString(", ")
            }
        }.getOrNull().orEmpty()
        val age = (System.currentTimeMillis() - at.time) / 60000
        return (if (place.isNotEmpty()) "$place. " else "") + "Latitude %.4f, longitude %.4f, accurate to about %d metres, found %s.".format(Locale.US, at.latitude, at.longitude, at.accuracy.toInt(), if (age < 2) "just now" else "$age minutes ago")
    }
}

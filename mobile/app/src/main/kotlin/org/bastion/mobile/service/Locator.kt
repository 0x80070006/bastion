package org.bastion.mobile.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.bastion.protocol.v1.Location as ProtoLocation

/**
 * Location without Google Play Services: platform `LocationManager` (GPS and network
 * providers). Every call checks the permission first.
 */
class Locator(private val context: Context, private val device: DeviceState) {
    private val manager: LocationManager? = context.getSystemService()
    private var listener: LocationListenerCompat? = null

    private fun providers(highAccuracy: Boolean): List<String> {
        val lm = manager ?: return emptyList()
        val ordered = if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return ordered.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /** A fresh fix within [timeoutMs], falling back to the best last known location. */
    @SuppressLint("MissingPermission")
    suspend fun current(highAccuracy: Boolean, timeoutMs: Long = FIX_TIMEOUT_MS): Location? {
        val lm = manager ?: return null
        if (!device.hasLocation()) return null
        for (provider in providers(highAccuracy)) {
            val fix = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        lm,
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location -> if (continuation.isActive) continuation.resume(location) }
                }
            }
            if (fix != null) return fix
        }
        return lastKnown()
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        val lm = manager ?: return null
        if (!device.hasLocation()) return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** Continuous updates every [intervalMs] (tracking modes). */
    @SuppressLint("MissingPermission")
    fun track(intervalMs: Long, highAccuracy: Boolean, onFix: (Location) -> Unit) {
        stopTracking()
        val lm = manager ?: return
        if (!device.hasLocation()) return
        val provider = providers(highAccuracy).firstOrNull() ?: return
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(
                if (highAccuracy) {
                    LocationRequestCompat.QUALITY_HIGH_ACCURACY
                } else {
                    LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                },
            )
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = LocationListenerCompat { onFix(it) }
        listener = callback
        LocationManagerCompat.requestLocationUpdates(
            lm,
            provider,
            request,
            ContextCompat.getMainExecutor(context),
            callback,
        )
    }

    @SuppressLint("MissingPermission")
    fun stopTracking() {
        val lm = manager ?: return
        try {
            listener?.let { LocationManagerCompat.removeUpdates(lm, it) }
        } catch (e: SecurityException) {
            // Permission revoked while tracking: the updates are already stopped by the OS.
            android.util.Log.d("Locator", "removeUpdates: ${e.javaClass.simpleName}")
        }
        listener = null
    }

    companion object {
        private const val FIX_TIMEOUT_MS = 30_000L

        fun toProto(location: Location): ProtoLocation = ProtoLocation.newBuilder()
            .setLatitude(location.latitude)
            .setLongitude(location.longitude)
            .setAccuracyM(if (location.hasAccuracy()) location.accuracy else 0f)
            .apply {
                if (location.hasAltitude()) altitudeM = location.altitude
                if (location.hasSpeed()) speedMps = location.speed
                if (location.hasBearing()) bearingDeg = location.bearing
            }
            .setProvider(
                when (location.provider) {
                    LocationManager.GPS_PROVIDER -> ProtoLocation.Provider.PROVIDER_GPS

                    LocationManager.NETWORK_PROVIDER -> ProtoLocation.Provider.PROVIDER_NETWORK

                    LocationManager.PASSIVE_PROVIDER -> ProtoLocation.Provider.PROVIDER_PASSIVE

                    else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        location.provider == LocationManager.FUSED_PROVIDER
                    ) {
                        ProtoLocation.Provider.PROVIDER_NETWORK
                    } else {
                        ProtoLocation.Provider.PROVIDER_UNSPECIFIED
                    }
                },
            )
            .setFixTimeMs(location.time)
            .build()
    }
}

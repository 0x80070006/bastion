package org.bastion.core.agent

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.bastion.core.agent.state.Geofence

/**
 * Circular geofence evaluation (PROTOCOL.md: TYPE_GEOFENCE_ENTER / TYPE_GEOFENCE_EXIT). Pure and
 * deterministic, so it is unit-tested without Android. A small hysteresis avoids flapping at the
 * boundary when fixes are noisy.
 */
public object Geofencing {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val MIN_RADIUS_M = 50f
    private const val HYSTERESIS_M = 30.0

    /** A crossing to report to the controller. */
    public data class Transition(val id: String, val entered: Boolean)

    /** Converts wire geofences to local state (inside unknown until the first fix). */
    public fun fromWire(zones: List<org.bastion.protocol.v1.Geofence>): List<Geofence> = zones.map {
        Geofence.newBuilder()
            .setId(it.id.take(MAX_ID))
            .setLatitude(it.latitude)
            .setLongitude(it.longitude)
            .setRadiusM(it.radiusM.coerceAtLeast(MIN_RADIUS_M))
            .setInside(false)
            .build()
    }

    /**
     * Updates each zone's `inside` flag for the current position and returns the crossings. Entry
     * uses the radius; exit requires leaving radius + hysteresis, so jitter does not flap.
     */
    public fun evaluate(
        zones: List<Geofence>,
        latitude: Double,
        longitude: Double,
    ): Pair<List<Geofence>, List<Transition>> {
        val transitions = mutableListOf<Transition>()
        val updated = zones.map { zone ->
            val distance = haversineMeters(latitude, longitude, zone.latitude, zone.longitude)
            val nowInside = if (zone.inside) {
                distance <= zone.radiusM + HYSTERESIS_M
            } else {
                distance <= zone.radiusM
            }
            if (nowInside != zone.inside) transitions.add(Transition(zone.id, nowInside))
            zone.toBuilder().setInside(nowInside).build()
        }
        return updated to transitions
    }

    /** Great-circle distance in metres between two WGS84 points. */
    public fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    private const val MAX_ID = 64
}

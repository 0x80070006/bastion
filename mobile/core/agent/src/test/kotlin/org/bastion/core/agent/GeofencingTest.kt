package org.bastion.core.agent

import com.google.common.truth.Truth.assertThat
import org.bastion.core.agent.state.Geofence
import org.junit.jupiter.api.Test

internal class GeofencingTest {
    private fun zone(id: String, lat: Double, lon: Double, radius: Float, inside: Boolean) =
        Geofence.newBuilder().setId(id).setLatitude(lat).setLongitude(lon).setRadiusM(radius).setInside(inside).build()

    @Test
    fun `haversine matches a known distance`() {
        // Paris Notre-Dame to the Eiffel Tower is about 4.2 km.
        val d = Geofencing.haversineMeters(48.8530, 2.3499, 48.8584, 2.2945)
        assertThat(d).isWithin(150.0).of(4_200.0)
    }

    @Test
    fun `entry and exit are reported once, with hysteresis`() {
        val zones = listOf(zone("home", 48.8566, 2.3522, 100f, inside = false))
        // 2 km away: outside, no transition.
        var (state, transitions) = Geofencing.evaluate(zones, 48.87, 2.35)
        assertThat(transitions).isEmpty()
        assertThat(state[0].inside).isFalse()

        // At the centre: entered.
        val entered = Geofencing.evaluate(state, 48.8566, 2.3522)
        assertThat(entered.second).containsExactly(Geofencing.Transition("home", true))
        state = entered.first

        // Just outside the radius but within hysteresis: still considered inside, no exit.
        val nudged = Geofencing.evaluate(state, 48.85665 + 0.0001, 2.3522)
        assertThat(nudged.second).isEmpty()

        // Clearly outside (300 m): exit reported once.
        val exited = Geofencing.evaluate(nudged.first, 48.8593, 2.3522)
        assertThat(exited.second).containsExactly(Geofencing.Transition("home", false))
        assertThat(Geofencing.evaluate(exited.first, 48.8593, 2.3522).second).isEmpty()
    }

    @Test
    fun `wire zones get a minimum radius`() {
        val wire = org.bastion.protocol.v1.Geofence.newBuilder()
            .setId("z").setLatitude(1.0).setLongitude(2.0).setRadiusM(5f).build()
        assertThat(Geofencing.fromWire(listOf(wire)).single().radiusM).isAtLeast(50f)
    }
}

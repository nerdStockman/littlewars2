package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetMovementTest {

    @Test
    fun fleet_progress_advances_and_fleet_is_removed_on_arrival() {
        // Simple 2-planet line, edge length = 10 world units.
        // With speed = 10 units/sec, travel time = 1.0s.
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))
        val p2 = Planet(2, Vec2(10.0, 0.0), Owner.P2, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))
        val e = Edge(7, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 10.0)

        var s = GameState(
            planets = listOf(p1, p2),
            edges = listOf(e),
            fleets = emptyList(),
            nextFleetId = 0
        )

        s = s.spawnFleet(
            owner = Owner.P1,
            unitsInt = 5,
            edgeId = 7,
            fromPlanetId = 1,
            toPlanetId = 2,
            progress = 0.0
        )

        assertEquals(1, s.fleets.size)
        assertEquals(0.0, s.fleets[0].progress, 0.0)

        // Step 0.5s: should be at progress 0.5
        s = s.step(dtMicros = 500_000L)
        assertEquals(1, s.fleets.size)
        assertEquals(0.5, s.fleets[0].progress, 1e-12)

        // Step another 0.5s: should arrive and be removed
        s = s.step(dtMicros = 500_000L)
        assertTrue("Fleet should have arrived and been removed", s.fleets.isEmpty())
    }

    @Test
    fun zero_length_edge_causes_instant_arrival() {
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(1))
        val p2 = Planet(2, Vec2(0.0, 0.0), Owner.P2, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(1))
        val e = Edge(1, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 0.0)

        var s = GameState(
            planets = listOf(p1, p2),
            edges = listOf(e),
            fleets = emptyList(),
            nextFleetId = 0
        ).spawnFleet(
            owner = Owner.P1,
            unitsInt = 1,
            edgeId = 1,
            fromPlanetId = 1,
            toPlanetId = 2
        )

        // Any dt should remove it immediately
        s = s.step(dtMicros = 1L)
        assertTrue(s.fleets.isEmpty())
    }
}

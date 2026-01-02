package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolleyCommandTest {

    @Test
    fun volleySend_subtractsImmediately_and_spawnsFleet_unitsAtLeast1() {
        // Two planets connected by edge 7, length = 10.
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 5.9, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))
        val p2 = Planet(2, Vec2(10.0, 0.0), Owner.P2, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))
        val e = Edge(7, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 10.0)

        var s = GameState(
            planets = listOf(p1, p2),
            edges = listOf(e)
        )

        // Queue a 50% volley at time 0.
        // available = floor(5.9) = 5 => send = 2
        s = s.enqueueVolleySend(
            simTimeMicros = 0L,
            playerId = Owner.P1,
            sourcePlanetId = 1,
            targetPlanetId = 2,
            fraction = SendFraction.PCT_50
        )

        // Step with dt=0: commands apply at start; no production/movement.
        s = s.step(dtMicros = 0L)

        assertEquals("Planet should subtract immediately", 3.9, s.planets.first { it.id == 1 }.unitsFloat, 1e-9)
        assertEquals(1, s.fleets.size)
        assertEquals(2, s.fleets[0].unitsInt)
        assertEquals(7, s.fleets[0].edgeId)
        assertEquals(1, s.fleets[0].fromPlanetId)
        assertEquals(2, s.fleets[0].toPlanetId)
        assertEquals(0.0, s.fleets[0].progress, 0.0)
        assertTrue("Command should be consumed", s.pendingCommands.isEmpty())
    }

    @Test
    fun volleySend_invalid_whenNotAdjacent_isDeterministicNoop_butConsumed() {
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 10.0, spawnRate = 0.0, adjacencyEdgeIds = emptyList())
        val p2 = Planet(2, Vec2(10.0, 0.0), Owner.P2, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = emptyList())

        var s = GameState(planets = listOf(p1, p2), edges = emptyList())

        s = s.enqueueVolleySend(
            simTimeMicros = 0L,
            playerId = Owner.P1,
            sourcePlanetId = 1,
            targetPlanetId = 2,
            fraction = SendFraction.PCT_100
        )

        s = s.step(dtMicros = 0L)

        // No adjacency => no change, no fleet, but command is consumed deterministically.
        assertEquals(10.0, s.planets.first { it.id == 1 }.unitsFloat, 0.0)
        assertTrue(s.fleets.isEmpty())
        assertTrue(s.pendingCommands.isEmpty())
    }
}

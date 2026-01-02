package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductionTest {

    @Test
    fun production_increasesUnits_bySpawnRateTimesDt() {
        val p = Planet(
            id = 1,
            pos = Vec2(0.0, 0.0),
            owner = Owner.P1,
            unitsFloat = 0.0,
            spawnRate = 2.5,              // units per second
            adjacencyEdgeIds = emptyList()
        )

        val s0 = GameState(planets = listOf(p), edges = emptyList())
        val s1 = s0.step(dtMicros = 1_000_000L) // 1.0s

        assertEquals(2.5, s1.planets[0].unitsFloat, 0.0)
    }

    @Test
    fun production_clampsUnits_atZero_whenNegativeSpawnWouldGoBelowZero() {
        val p = Planet(
            id = 1,
            pos = Vec2(0.0, 0.0),
            owner = Owner.P1,
            unitsFloat = 0.25,
            spawnRate = -1.0,             // drains 1 unit per second
            adjacencyEdgeIds = emptyList()
        )

        val s0 = GameState(planets = listOf(p), edges = emptyList())
        val s1 = s0.step(dtMicros = 1_000_000L) // 1.0s => -0.75 => clamp to 0

        assertEquals(0.0, s1.planets[0].unitsFloat, 0.0)
        assertEquals(0, s1.planets[0].intHP())
    }

    @Test
    fun intHP_usesFloor() {
        val pA = Planet(1, Vec2(0.0, 0.0), Owner.NEUTRAL, unitsFloat = 2.999, spawnRate = 0.0, adjacencyEdgeIds = emptyList())
        val pB = Planet(2, Vec2(0.0, 0.0), Owner.NEUTRAL, unitsFloat = 3.0,   spawnRate = 0.0, adjacencyEdgeIds = emptyList())
        val pC = Planet(3, Vec2(0.0, 0.0), Owner.NEUTRAL, unitsFloat = 0.999, spawnRate = 0.0, adjacencyEdgeIds = emptyList())

        assertEquals(2, pA.intHP())
        assertEquals(3, pB.intHP())
        assertEquals(0, pC.intHP())
    }

    @Test
    fun production_withZeroDt_doesNotChangeUnits() {
        val p = Planet(
            id = 1,
            pos = Vec2(0.0, 0.0),
            owner = Owner.P1,
            unitsFloat = 7.25,
            spawnRate = 999.0,
            adjacencyEdgeIds = emptyList()
        )

        val s0 = GameState(planets = listOf(p), edges = emptyList())
        val s1 = s0.step(dtMicros = 0L)

        assertEquals(7.25, s1.planets[0].unitsFloat, 0.0)
    }
}

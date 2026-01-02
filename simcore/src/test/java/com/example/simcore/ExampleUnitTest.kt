package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun determinism_goldenHash_600Steps() {
        val final = runSteps(GameState(), steps = 600, dtMicros = GameState.DEFAULT_DT_MICROS)

        // Updated golden hash for Milestone 4:
        // worldHash now includes pendingCommands.size (0), so empty-world hash changes.
        val expected = "258-9894f0-170fb63ffbb800d2-0c8210784d8af5a5"
        assertEquals("Determinism golden hash mismatch at 600 steps", expected, final.stateHash())
    }

    @Test
    fun determinism_sameInputs_sameOutputs() {
        val a = runSteps(GameState(), steps = 600, dtMicros = GameState.DEFAULT_DT_MICROS)
        val b = runSteps(GameState(), steps = 600, dtMicros = GameState.DEFAULT_DT_MICROS)
        assertEquals(a.stateHash(), b.stateHash())
    }

    @Test
    fun worldHash_independentOfInsertionOrder() {
        // Same world, inserted in different orders. We canonicalize to sorted-by-id.
        val p1 = Planet(
            id = 1,
            pos = Vec2(10.0, 0.0),
            owner = Owner.P1,
            unitsFloat = 5.5,
            spawnRate = 3.0,
            adjacencyEdgeIds = listOf(10)
        )
        val p2 = Planet(
            id = 2,
            pos = Vec2(0.0, 10.0),
            owner = Owner.NEUTRAL,
            unitsFloat = 2.0,
            spawnRate = 0.0,
            adjacencyEdgeIds = listOf(10)
        )
        val e10 = Edge(
            id = 10,
            aPlanetId = 1,
            bPlanetId = 2,
            lengthWorldUnits = 14.1421356237
        )

        val a = GameState(
            planets = listOf(p1, p2),
            edges = listOf(e10),
            fleets = emptyList(),
            nextFleetId = 0,
            pendingCommands = emptyList(),
            nextCommandId = 0
        )

        val b = GameState(
            planets = listOf(p2, p1).sortedBy { it.id }.map { it.canonical() },
            edges = listOf(e10),
            fleets = emptyList(),
            nextFleetId = 0,
            pendingCommands = emptyList(),
            nextCommandId = 0
        )

        assertEquals(a.worldHash(), b.worldHash())
        assertEquals(a.stateHash(), b.stateHash())
    }

    private fun runSteps(initial: GameState, steps: Int, dtMicros: Long): GameState {
        var s = initial
        repeat(steps) { s = s.step(dtMicros) }
        return s
    }
}

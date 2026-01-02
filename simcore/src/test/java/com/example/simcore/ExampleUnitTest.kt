package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun determinism_goldenHash_600Steps() {
        val final = runSteps(GameState(), steps = 600, dtMicros = GameState.DEFAULT_DT_MICROS)

        // Golden hash for this exact Milestone 0 harness implementation.
        val expected = "258-9894f0-170fb63ffbb800d2"
        assertEquals("Determinism golden hash mismatch at 600 steps", expected, final.stateHash())
    }

    @Test
    fun determinism_sameInputs_sameOutputs() {
        val a = runSteps(GameState(), steps = 600, dtMicros = GameState.DEFAULT_DT_MICROS)
        val b = runSteps(GameState(), steps = 600, dtMicros = GameState.DEFAULT_DT_MICROS)
        assertEquals(a.stateHash(), b.stateHash())
    }

    private fun runSteps(initial: GameState, steps: Int, dtMicros: Long): GameState {
        var s = initial
        repeat(steps) { s = s.step(dtMicros) }
        return s
    }
}

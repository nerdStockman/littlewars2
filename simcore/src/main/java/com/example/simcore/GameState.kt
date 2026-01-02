package com.example.simcore

/**
 * Minimal deterministic sim core stub.
 * No Android imports. No randomness.
 */
data class GameState(
    val tick: Long = 0L
) {
    fun step(): GameState {
        // Fixed-timestep stepping will be driven by the app for now.
        return copy(tick = tick + 1L)
    }

    fun stateHash(): String {
        // Simple stable “hash” for now; we’ll upgrade later.
        return tick.toString()
    }
}

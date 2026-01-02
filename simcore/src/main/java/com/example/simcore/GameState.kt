package com.example.simcore

/**
 * Minimal deterministic sim core stub (Milestone 0).
 *
 * Goals:
 * - No Android imports
 * - No randomness
 * - Deterministic stepping
 * - Stable state hash that depends on more than just tick
 *
 * Implementation notes:
 * - Uses integer-only sim time in microseconds.
 * - Uses FNV-1a 64-bit checksum (ULong) updated from (tick, time, dt) each step.
 */
data class GameState(
    val tick: Long = 0L,
    val simTimeMicros: Long = 0L,
    val checksum: ULong = FNV_OFFSET_BASIS
) {

    /**
     * Deterministic fixed timestep step.
     * dtMicros default ~= 60 Hz (16.666 ms).
     */
    fun step(dtMicros: Long = DEFAULT_DT_MICROS): GameState {
        val nextTick = tick + 1L
        val nextTime = simTimeMicros + dtMicros

        // Update checksum deterministically based on meaningful state evolution.
        var h = checksum
        h = fnv1aUpdateLong(h, nextTick)
        h = fnv1aUpdateLong(h, nextTime)
        h = fnv1aUpdateLong(h, dtMicros)

        return copy(
            tick = nextTick,
            simTimeMicros = nextTime,
            checksum = h
        )
    }

    /**
     * Stable, human-readable hash string.
     * Example: "258-9894f0-170fb63ffbb800d2"
     */
    fun stateHash(): String {
        val t = tick.toString(16)
        val tm = simTimeMicros.toString(16)
        val c = checksum.toString(16).padStart(16, '0')
        return "$t-$tm-$c"
    }

    private fun fnv1aUpdateLong(hash: ULong, value: Long): ULong {
        // Interpret the signed Long as a 64-bit pattern, then feed 8 bytes (little-endian).
        var h = hash
        var v = value.toULong()
        repeat(8) {
            h = (h xor (v and 0xFFu)) * FNV_PRIME
            v = v shr 8
        }
        return h
    }

    companion object {
        const val DEFAULT_DT_MICROS: Long = 16_666L // ~60 Hz

        private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
        private const val FNV_PRIME: ULong = 0x100000001b3uL
    }
}

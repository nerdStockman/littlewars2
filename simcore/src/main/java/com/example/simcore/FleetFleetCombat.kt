package com.example.simcore

import kotlin.math.floor

/**
 * Milestone 6: Fleet ↔ Fleet combat (spec exact).
 *
 * Spec (7.3):
 *   damageTo2 = floor(A1 * S1 / S2)
 *   damageTo1 = floor(A2 * S2 / S1)
 *   newA1 = A1 - damageTo1
 *   newA2 = A2 - damageTo2
 * Fleets with units <= 0 are destroyed. Survivors continue along their path.
 *
 * Notes:
 * - This file intentionally does NOT do collision detection (Milestone 7).
 * - This is pure deterministic math, suitable for use by whatever collision system you add later.
 */
object FleetFleetCombat {

    /**
     * Pure integer result for unit counts after a fleet-fleet interaction.
     *
     * @return Pair(newA1, newA2) where values may be <= 0 (destroyed).
     */
    fun resolveUnits(
        A1: Int,
        S1: Double,
        A2: Int,
        S2: Double
    ): Pair<Int, Int> {
        require(A1 >= 1) { "A1 must be >= 1" }
        require(A2 >= 1) { "A2 must be >= 1" }
        require(S1.isFinite() && S1 > 0.0) { "S1 must be finite and > 0" }
        require(S2.isFinite() && S2 > 0.0) { "S2 must be finite and > 0" }

        val damageTo2 = floor((A1.toDouble() * S1) / S2).toInt()
        val damageTo1 = floor((A2.toDouble() * S2) / S1).toInt()

        val newA1 = A1 - damageTo1
        val newA2 = A2 - damageTo2
        return newA1 to newA2
    }

    /**
     * Convenience wrapper that applies resolveUnits() and returns updated Fleet copies (or null if destroyed).
     *
     * IMPORTANT:
     * - Fleets keep their existing edge/from/to/progress when they survive.
     * - This function does not change ordering or IDs; callers decide ordering deterministically.
     */
    fun resolveFleets(
        f1: Fleet,
        airToAir1: Double,
        f2: Fleet,
        airToAir2: Double
    ): Pair<Fleet?, Fleet?> {
        val (newA1, newA2) = resolveUnits(
            A1 = f1.unitsInt,
            S1 = airToAir1,
            A2 = f2.unitsInt,
            S2 = airToAir2
        )

        val out1 = if (newA1 <= 0) null else f1.copy(unitsInt = newA1)
        val out2 = if (newA2 <= 0) null else f2.copy(unitsInt = newA2)
        return out1 to out2
    }
}

package com.example.simcore

/**
 * Deterministic send fractions for Volley Send.
 * We avoid floating math for unit calculation.
 */
enum class SendFraction(val numerator: Int, val denominator: Int) {
    PCT_25(1, 4),
    PCT_50(1, 2),
    PCT_100(1, 1);

    fun computeSendUnits(availableIntHp: Int): Int {
        if (availableIntHp <= 0) return 0
        val raw = (availableIntHp * numerator) / denominator
        // Spec: minimum send = 1 (as long as available >= 1)
        return if (raw <= 0) 1 else raw
    }
}

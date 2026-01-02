package com.example.simcore

/**
 * Milestone 4 Command model.
 *
 * All player actions become commands applied at deterministic boundaries.
 *
 * For now we only implement VolleySend.
 */
data class Command(
    val id: Int,                 // deterministic, monotonic
    val simTimeMicros: Long,      // command timestamp in simulation micros
    val playerId: Int,            // Owner.P1..P4
    val kind: Kind,
    val sourcePlanetId: Int,
    val targetPlanetId: Int,
    val fraction: SendFraction
) {
    enum class Kind {
        VOLLEY_SEND
    }

    init {
        require(id >= 0) { "Command id must be >= 0" }
        require(simTimeMicros >= 0L) { "Command simTimeMicros must be >= 0" }
        require(sourcePlanetId != targetPlanetId) { "Command $id source and target cannot be same" }
    }
}

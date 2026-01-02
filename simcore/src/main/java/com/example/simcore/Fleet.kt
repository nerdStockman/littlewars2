package com.example.simcore

/**
 * Milestone 3 Fleet model.
 *
 * Fields per spec:
 * - id (deterministic, monotonic)
 * - owner
 * - unitsInt (>= 1)
 * - edgeId
 * - fromPlanetId
 * - toPlanetId
 * - progress in [0, 1] (Double)
 *
 * Note: Fleets are never merged (later milestones may add combat/collisions).
 */
data class Fleet(
    val id: Int,
    val owner: Int,
    val unitsInt: Int,
    val edgeId: Int,
    val fromPlanetId: Int,
    val toPlanetId: Int,
    val progress: Double
) {
    init {
        require(id >= 0) { "Fleet id must be >= 0" }
        require(unitsInt >= 1) { "Fleet $id unitsInt must be >= 1" }
        require(fromPlanetId != toPlanetId) { "Fleet $id cannot have fromPlanetId == toPlanetId" }
        require(progress.isFinite()) { "Fleet $id progress must be finite" }
    }

    fun canonical(): Fleet {
        val p = when {
            progress < 0.0 -> 0.0
            progress > 1.0 -> 1.0
            else -> progress
        }
        return if (p == progress) this else copy(progress = p)
    }
}

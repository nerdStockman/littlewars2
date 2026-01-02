package com.example.simcore

import kotlin.math.floor

/**
 * Milestone 1 Planet model.
 *
 * Deliverable fields:
 * - id
 * - pos
 * - owner
 * - unitsFloat
 * - spawnRate
 * - adjacencyEdgeIds
 *
 * Milestone 2 additions:
 * - intHP(): display helper uses floor(unitsFloat)
 */
data class Planet(
    val id: Int,
    val pos: Vec2,
    val owner: Int,
    val unitsFloat: Double,
    val spawnRate: Double,
    val adjacencyEdgeIds: List<Int>
) {
    init {
        // Deterministic ordering inside the planet too.
        // (Even if caller passes unsorted adjacency lists, we canonicalize.)
        require(id >= 0) { "Planet id must be >= 0" }
        require(adjacencyEdgeIds.distinct().size == adjacencyEdgeIds.size) {
            "Planet $id adjacencyEdgeIds contains duplicates"
        }
    }

    fun canonical(): Planet =
        if (adjacencyEdgeIds == adjacencyEdgeIds.sorted()) this
        else copy(adjacencyEdgeIds = adjacencyEdgeIds.sorted())

    /**
     * Display helper for UI / logging: integer HP is floor(unitsFloat).
     * (We also guard against negatives just in case.)
     */
    fun intHP(): Int = floor(unitsFloat).toInt().coerceAtLeast(0)
}

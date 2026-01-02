package com.example.simcore

/**
 * Milestone 1 Edge model.
 *
 * Deliverable fields:
 * - id
 * - aPlanetId
 * - bPlanetId
 * - lengthWorldUnits
 */
data class Edge(
    val id: Int,
    val aPlanetId: Int,
    val bPlanetId: Int,
    val lengthWorldUnits: Double
) {
    init {
        require(id >= 0) { "Edge id must be >= 0" }
        require(aPlanetId != bPlanetId) { "Edge $id cannot connect a planet to itself" }
        require(lengthWorldUnits >= 0.0) { "Edge $id lengthWorldUnits must be >= 0" }
    }

    fun other(planetId: Int): Int? = when (planetId) {
        aPlanetId -> bPlanetId
        bPlanetId -> aPlanetId
        else -> null
    }
}

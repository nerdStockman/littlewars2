package com.example.simcore

/**
 * Milestone 5: ship profile stats used by combat resolution.
 *
 * NOTE: For now we use a single default profile for all players (and neutral),
 * until ship selection / per-player loadouts arrive in later milestones.
 */
data class ShipProfile(
    val offense: Double,           // 0.1–3.0 (spec)
    val defense: Double,           // 0.1–3.0 (spec)
    val airToAir: Double,          // 0.1–3.0 (spec) (used later in fleet-fleet combat)
    val speedMultiplier: Double,   // used later for per-player speed
    val colonizationBonus: Int     // int >= 0
) {
    init {
        require(offense.isFinite() && offense > 0.0) { "offense must be finite and > 0" }
        require(defense.isFinite() && defense > 0.0) { "defense must be finite and > 0" }
        require(airToAir.isFinite() && airToAir > 0.0) { "airToAir must be finite and > 0" }
        require(speedMultiplier.isFinite() && speedMultiplier > 0.0) { "speedMultiplier must be finite and > 0" }
        require(colonizationBonus >= 0) { "colonizationBonus must be >= 0" }
    }
}

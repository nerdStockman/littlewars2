package com.example.simcore

/**
 * Milestone 5: centralized ship profile lookup.
 *
 * For now: everyone uses the same baseline profile to avoid changing gameplay
 * outside the new "arrival triggers combat" behavior.
 *
 * Later milestones can:
 * - attach a chosen ShipType to each player
 * - build a per-player profile table deterministically
 */
object ShipProfiles {
    private val DEFAULT = ShipProfile(
        offense = 1.0,
        defense = 1.0,
        airToAir = 1.0,
        speedMultiplier = 1.0,
        colonizationBonus = 0
    )

    fun forOwner(ownerId: Int): ShipProfile = DEFAULT
}

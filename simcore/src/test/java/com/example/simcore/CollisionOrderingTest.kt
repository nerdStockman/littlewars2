package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Test

class CollisionOrderingTest {

    @Test
    fun oppositeDirectionFleets_collide_midEdge_and_resolve_combat_without_arrival() {
        // Edge length=10, speed=10 units/sec => 1.0s to traverse full edge.
        // Two fleets starting at opposite ends collide at t=0.5s.
        //
        // IMPORTANT: fleets are consumed on arrival, so we step only 0.6s:
        // collision occurs, but survivor does NOT arrive in the same step.
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))
        val p2 = Planet(2, Vec2(10.0, 0.0), Owner.P2, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))
        val e = Edge(7, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 10.0)

        var s = GameState(planets = listOf(p1, p2), edges = listOf(e))
        s = s.spawnFleet(owner = Owner.P1, unitsInt = 10, edgeId = 7, fromPlanetId = 1, toPlanetId = 2, progress = 0.0)
        s = s.spawnFleet(owner = Owner.P2, unitsInt = 3,  edgeId = 7, fromPlanetId = 2, toPlanetId = 1, progress = 0.0)

        // Step 0.6s: collision at 0.5s happens, then 0.1s travel remains.
        s = s.step(dtMicros = 600_000L)

        // With airToAir=1:
        // 10 vs 3 -> f2 destroyed, f1 survives with 7.
        assertEquals(1, s.fleets.size)
        val survivor = s.fleets[0]
        assertEquals(Owner.P1, survivor.owner)
        assertEquals(7, survivor.unitsInt)
    }

    @Test
    fun twoIndependentCollisions_sameStep_resolve_deterministically_without_arrival() {
        // We want TWO collisions within ONE step, but we do NOT want chain-collisions.
        // So we place the collisions on TWO DIFFERENT EDGES.

        // Edge 7: planets 1 <-> 2
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))
        val p2 = Planet(2, Vec2(10.0, 0.0), Owner.P2, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(7))

        // Edge 8: planets 3 <-> 4 (separate)
        val p3 = Planet(3, Vec2(0.0, 10.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(8))
        val p4 = Planet(4, Vec2(10.0, 10.0), Owner.P2, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(8))

        val e7 = Edge(7, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 10.0)
        val e8 = Edge(8, aPlanetId = 3, bPlanetId = 4, lengthWorldUnits = 10.0)

        var s = GameState(
            planets = listOf(p1, p2, p3, p4).sortedBy { it.id },
            edges = listOf(e7, e8).sortedBy { it.id }
        )

        // Collision on edge 7: 2 vs 1 -> survivor has 1
        s = s.spawnFleet(owner = Owner.P1, unitsInt = 2, edgeId = 7, fromPlanetId = 1, toPlanetId = 2, progress = 0.0) // id 0
        s = s.spawnFleet(owner = Owner.P2, unitsInt = 1, edgeId = 7, fromPlanetId = 2, toPlanetId = 1, progress = 0.0) // id 1

        // Collision on edge 8: 2 vs 1 -> survivor has 1
        s = s.spawnFleet(owner = Owner.P1, unitsInt = 2, edgeId = 8, fromPlanetId = 3, toPlanetId = 4, progress = 0.0) // id 2
        s = s.spawnFleet(owner = Owner.P2, unitsInt = 1, edgeId = 8, fromPlanetId = 4, toPlanetId = 3, progress = 0.0) // id 3

        // Step 0.6s: collisions at 0.5s happen on both edges; no arrival (needs 1.0s).
        s = s.step(dtMicros = 600_000L)

        // Expect two survivors (ids 0 and 2), both P1, both 1 unit.
        assertEquals(2, s.fleets.size)
        assertEquals(listOf(0, 2), s.fleets.map { it.id })
        assertEquals(listOf(Owner.P1, Owner.P1), s.fleets.map { it.owner })
        assertEquals(listOf(1, 1), s.fleets.map { it.unitsInt })
    }
}

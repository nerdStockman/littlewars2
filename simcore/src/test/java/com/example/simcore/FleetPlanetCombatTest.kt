package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetPlanetCombatTest {

    @Test
    fun arrival_damageLessThanHp_defenderKeepsPlanet_hpReduced() {
        // O=D=1.0 by default profile. damage=floor(A*O/D)=A
        // H=10, A=3 => damage=3 < 10 => newHP=7, defender keeps owner.
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(5))
        val p2 = Planet(2, Vec2(0.0, 0.0), Owner.P2, unitsFloat = 10.9, spawnRate = 0.0, adjacencyEdgeIds = listOf(5))
        val e = Edge(5, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 0.0) // instant arrival

        var s = GameState(planets = listOf(p1, p2), edges = listOf(e))
            .spawnFleet(owner = Owner.P1, unitsInt = 3, edgeId = 5, fromPlanetId = 1, toPlanetId = 2)

        s = s.step(dtMicros = 1L)

        assertTrue(s.fleets.isEmpty())
        val tgt = s.planets.first { it.id == 2 }
        assertEquals(Owner.P2, tgt.owner)
        assertEquals(7.0, tgt.unitsFloat, 0.0)
    }

    @Test
    fun arrival_damageEqualsHp_defenderKeepsPlanet_hpBecomesZero() {
        // H=5, A=5 => damage=5 == H => defender keeps, newHP=0
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(5))
        val p2 = Planet(2, Vec2(0.0, 0.0), Owner.P2, unitsFloat = 5.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(5))
        val e = Edge(5, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 0.0)

        var s = GameState(planets = listOf(p1, p2), edges = listOf(e))
            .spawnFleet(owner = Owner.P1, unitsInt = 5, edgeId = 5, fromPlanetId = 1, toPlanetId = 2)

        s = s.step(dtMicros = 1L)

        assertTrue(s.fleets.isEmpty())
        val tgt = s.planets.first { it.id == 2 }
        assertEquals(Owner.P2, tgt.owner)
        assertEquals(0.0, tgt.unitsFloat, 0.0)
    }

    @Test
    fun arrival_damageGreaterThanHp_attackerCaptures_remainderBecomesNewHp() {
        // H=5, A=10 => damage=10 > 5 => capture
        // required=ceil(H*D/O)=ceil(5)=5, remainder=5, B=0 => newHP=5
        val p1 = Planet(1, Vec2(0.0, 0.0), Owner.P1, unitsFloat = 0.0, spawnRate = 0.0, adjacencyEdgeIds = listOf(5))
        val p2 = Planet(2, Vec2(0.0, 0.0), Owner.P2, unitsFloat = 5.9, spawnRate = 0.0, adjacencyEdgeIds = listOf(5))
        val e = Edge(5, aPlanetId = 1, bPlanetId = 2, lengthWorldUnits = 0.0)

        var s = GameState(planets = listOf(p1, p2), edges = listOf(e))
            .spawnFleet(owner = Owner.P1, unitsInt = 10, edgeId = 5, fromPlanetId = 1, toPlanetId = 2)

        s = s.step(dtMicros = 1L)

        assertTrue(s.fleets.isEmpty())
        val tgt = s.planets.first { it.id == 2 }
        assertEquals(Owner.P1, tgt.owner)
        assertEquals(5.0, tgt.unitsFloat, 0.0)
    }
}

package com.example.simcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FleetFleetCombatTest {

    @Test
    fun equalStats_basicAsymmetricOutcome_survivorContinues() {
        // S1=S2=1
        // A1=10, A2=3
        // damageTo2=floor(10*1/1)=10 => newA2=-7 destroyed
        // damageTo1=floor(3*1/1)=3  => newA1=7 survives
        val f1 = Fleet(id = 1, owner = Owner.P1, unitsInt = 10, edgeId = 7, fromPlanetId = 1, toPlanetId = 2, progress = 0.42)
        val f2 = Fleet(id = 2, owner = Owner.P2, unitsInt = 3,  edgeId = 7, fromPlanetId = 2, toPlanetId = 1, progress = 0.58)

        val (out1, out2) = FleetFleetCombat.resolveFleets(f1, airToAir1 = 1.0, f2, airToAir2 = 1.0)

        assertNotNull(out1)
        assertNull(out2)

        assertEquals(7, out1!!.unitsInt)
        // Survivor continues: path/progress unchanged.
        assertEquals(7, out1.edgeId)
        assertEquals(1, out1.fromPlanetId)
        assertEquals(2, out1.toPlanetId)
        assertEquals(0.42, out1.progress, 0.0)
    }

    @Test
    fun unequalStats_roundingAndAsymmetry_matchSpecFloors() {
        // A1=10,S1=2 ; A2=10,S2=1
        // damageTo2=floor(10*2/1)=20 => newA2=-10 destroyed
        // damageTo1=floor(10*1/2)=5  => newA1=5 survives
        val (newA1, newA2) = FleetFleetCombat.resolveUnits(A1 = 10, S1 = 2.0, A2 = 10, S2 = 1.0)
        assertEquals(5, newA1)
        assertEquals(-10, newA2)
    }

    @Test
    fun mutualDestruction_zeroIsDestroyed() {
        // A1=5,S1=1 ; A2=5,S2=1
        // damageTo2=5 => newA2=0 destroyed
        // damageTo1=5 => newA1=0 destroyed
        val f1 = Fleet(id = 1, owner = Owner.P1, unitsInt = 5, edgeId = 9, fromPlanetId = 1, toPlanetId = 2, progress = 0.1)
        val f2 = Fleet(id = 2, owner = Owner.P2, unitsInt = 5, edgeId = 9, fromPlanetId = 2, toPlanetId = 1, progress = 0.9)

        val (out1, out2) = FleetFleetCombat.resolveFleets(f1, airToAir1 = 1.0, f2, airToAir2 = 1.0)

        assertNull(out1)
        assertNull(out2)
    }

    @Test
    fun tinyDamageCanBeZero_floorAllowsNoDamage() {
        // A1=1,S1=1 ; A2=2,S2=3
        // damageTo2=floor(1*1/3)=0 => newA2=2 survives
        // damageTo1=floor(2*3/1)=6 => newA1=-5 destroyed
        val (newA1, newA2) = FleetFleetCombat.resolveUnits(A1 = 1, S1 = 1.0, A2 = 2, S2 = 3.0)
        assertEquals(-5, newA1)
        assertEquals(2, newA2)
    }
}

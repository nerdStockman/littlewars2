package com.example.simcore

/**
 * World-space coordinates for planets.
 * (Render transforms happen later; sim stays in world units.)
 */
data class Vec2(
    val x: Double,
    val y: Double
)

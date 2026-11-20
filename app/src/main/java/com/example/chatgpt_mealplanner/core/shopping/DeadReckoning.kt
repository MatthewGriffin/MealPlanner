package com.example.chatgpt_mealplanner.core.shopping

import kotlin.math.cos
import kotlin.math.sin

/** A spot in a shop in metres from where the trip began: x to the east, y to the north. */
data class ShopPoint(val x: Double, val y: Double)

/**
 * Follows where someone has walked from their steps and the way the phone points, with no GPS: every step moves
 * [strideMetres] along the current heading. It drifts, so positions are only good relative to the start of a trip
 * and are averaged over many trips before they are used.
 */
class DeadReckoning(private val strideMetres: Double = DEFAULT_STRIDE_METRES) {
    private var x = 0.0
    private var y = 0.0
    private var headingRadians = 0.0
    private var hasHeading = false

    /** The compass heading in radians clockwise from north (0 = north, PI/2 = east). */
    fun onHeading(radiansFromNorth: Double) {
        headingRadians = radiansFromNorth
        hasHeading = true
    }

    /** One step. Without a heading yet the step cannot be placed and is ignored. */
    fun onStep() {
        if (!hasHeading) return
        x += strideMetres * sin(headingRadians)
        y += strideMetres * cos(headingRadians)
    }

    fun position() = ShopPoint(x, y)

    fun reset() {
        x = 0.0; y = 0.0; hasHeading = false
    }

    companion object {
        const val DEFAULT_STRIDE_METRES = 0.7
    }
}

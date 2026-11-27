package com.castpro.core.streaming.encoder

import kotlin.math.max
import kotlin.math.min

/**
 * Returns a proportional, H.264-aligned size for the phone's current display.
 * The long edge is capped at [maxLongEdge] and the short edge at 1080 pixels.
 */
fun computeAspectMatchedSize(
    displayW: Int,
    displayH: Int,
    maxLongEdge: Int = 1920
): Pair<Int, Int> {
    val safeW = displayW.coerceAtLeast(1)
    val safeH = displayH.coerceAtLeast(1)
    val longEdge = max(safeW, safeH)
    val shortEdge = min(safeW, safeH)
    val scale = minOf(
        (maxLongEdge.coerceAtLeast(320).toFloat() / longEdge.toFloat()).coerceAtMost(1f),
        (1080f / shortEdge.toFloat()).coerceAtMost(1f)
    )
    val rawW = (safeW * scale).toInt()
    val rawH = (safeH * scale).toInt()
    val alignedW = (rawW / 16) * 16
    val alignedH = (rawH / 16) * 16
    return alignedW.coerceAtLeast(320) to alignedH.coerceAtLeast(320)
}

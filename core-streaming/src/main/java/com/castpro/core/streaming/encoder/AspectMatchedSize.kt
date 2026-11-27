package com.castpro.core.streaming.encoder

/**
 * Returns a proportional, H.264-aligned size for the phone's current display.
 * The long edge is capped at [maxLongEdge] and the short edge at [maxShortEdge].
 */
fun computeAspectMatchedSize(
    displayW: Int,
    displayH: Int,
    maxLongEdge: Int = 1920,
    maxShortEdge: Int = 1080
): Pair<Int, Int> {
    val longEdge = maxOf(displayW, displayH).coerceAtLeast(1)
    val shortEdge = minOf(displayW, displayH).coerceAtLeast(1)
    val scale = minOf(
        maxLongEdge.toFloat() / longEdge.toFloat(),
        maxShortEdge.toFloat() / shortEdge.toFloat(),
        1f
    )
    val safeW = displayW.coerceAtLeast(1)
    val safeH = displayH.coerceAtLeast(1)
    val rawW = (safeW * scale).toInt()
    val rawH = (safeH * scale).toInt()
    val alignedW = (rawW / 16) * 16
    val alignedH = (rawH / 16) * 16
    return alignedW.coerceAtLeast(320) to alignedH.coerceAtLeast(320)
}

package com.castpro.core.streaming.encoder

/**
 * Returns a proportional, 16-pixel-aligned size for the phone's current display.
 *
 * Input dimensions and configured edge limits must be at least 16 pixels. Smaller
 * positive dimensions cannot produce a positive 16-aligned output without upscaling.
 * Scaling is represented as an exact rational number to avoid Float precision loss
 * immediately before the per-axis 16-pixel floor.
 */
fun computeAspectMatchedSize(
    displayW: Int,
    displayH: Int,
    maxLongEdge: Int = 1920,
    maxShortEdge: Int = 1080
): Pair<Int, Int> {
    require(displayW >= 16) { "displayW must be at least 16 pixels: $displayW" }
    require(displayH >= 16) { "displayH must be at least 16 pixels: $displayH" }
    require(maxLongEdge >= 16) { "maxLongEdge must be at least 16 pixels: $maxLongEdge" }
    require(maxShortEdge >= 16) { "maxShortEdge must be at least 16 pixels: $maxShortEdge" }

    val longEdge = maxOf(displayW, displayH)
    val shortEdge = minOf(displayW, displayH)

    // Keep the smallest of 1, maxLongEdge / longEdge, and
    // maxShortEdge / shortEdge as an exact fraction.
    var scaleNumerator = 1L
    var scaleDenominator = 1L

    if (maxLongEdge < longEdge) {
        scaleNumerator = maxLongEdge.toLong()
        scaleDenominator = longEdge.toLong()
    }

    if (maxShortEdge < shortEdge) {
        val candidateNumerator = maxShortEdge.toLong()
        val candidateDenominator = shortEdge.toLong()
        if (candidateNumerator * scaleDenominator <
            scaleNumerator * candidateDenominator
        ) {
            scaleNumerator = candidateNumerator
            scaleDenominator = candidateDenominator
        }
    }

    fun scaleAndAlign(edge: Int): Int {
        val scaledEdge = edge.toLong() * scaleNumerator / scaleDenominator
        return ((scaledEdge / 16L) * 16L).toInt()
    }

    return scaleAndAlign(displayW) to scaleAndAlign(displayH)
}

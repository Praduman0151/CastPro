package com.castpro.tv.viewmodel

import android.media.MediaCodec
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

/**
 * How the decoded frame is placed on the TV panel.
 *
 * STRETCH is the default. It maps the full decoded frame to the full display
 * with independent X and Y scales, so there are no black bars and no crop,
 * even when the phone's frame and the TV panel have different shapes. The
 * decoder itself is kept in SCALE_TO_FIT so it never crops before that matrix.
 *
 * FIT keeps the whole frame with bars; FULL and STRETCH fill the panel without cropping. None of these modes change the capture resolution.
 */
enum class TvDisplayMode(
    val label: String,
    val scalingMode: Int,
    val description: String
) {
    STRETCH(
        "Stretch",
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
        "Full screen, no bars, no crop (distorts to fit)"
    ),
    FIT(
        "Fit",
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
        "Whole frame, black bars kept"
    ),
    FILL(
        "Full",
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
        "Full TV screen, entire frame visible (stretched, no crop)"
    )
}

/**
 * Lightweight, low-latency colour treatments applied as a GPU compositing
 * overlay over the video surface. Standard adds nothing.
 */
enum class TvColorFilter(
    val label: String,
    val tint: Color? = null,
    val alpha: Float = 0f,
    val blendMode: BlendMode = BlendMode.SrcOver
) {
    STANDARD("Standard"),
    VIVID("Vivid", Color.White, 0.12f, BlendMode.Overlay),
    WARM("Warm", Color(0xFFFFB36B), 0.14f, BlendMode.Multiply),
    COOL("Cool", Color(0xFF66B3FF), 0.14f, BlendMode.Multiply),
    NIGHT("Night", Color(0xFF4A90FF), 0.20f, BlendMode.Multiply),
    GAME("Game", Color(0xFF00FF66), 0.08f, BlendMode.Screen),
    CONTRAST("Contrast", Color.Black, 0.12f, BlendMode.Overlay),
    GRAYSCALE("B&W", Color(0xFF808080), 1f, BlendMode.Saturation),
    SEPIA("Sepia", Color(0xFFA96B3F), 0.55f, BlendMode.Color)
}

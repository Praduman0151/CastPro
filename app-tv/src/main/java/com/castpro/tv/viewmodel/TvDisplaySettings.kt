package com.castpro.tv.viewmodel

import android.media.MediaCodec
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

/**
 * How the decoded frame is placed on the TV panel.
 *
 * The scalingMode is applied directly to MediaCodec. SCALE_TO_FIT_WITH_CROPPING
 * is what actually removes black bars from wide phone/game frames: the decoder
 * fills the SurfaceView and crops the overflow instead of letterboxing it.
 */
enum class TvDisplayMode(
    val label: String,
    val scalingMode: Int,
    val description: String
) {
    FIT(
        "Fit",
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
        "Whole frame, black bars kept"
    ),
    FULL(
        "Full",
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
        "Fill the TV, crop the edges"
    ),
    STRETCH(
        "Stretch",
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT,
        "Force 16:9, some distortion"
    ),
    ZOOM(
        "Zoom",
        MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
        "Fill and zoom in slightly"
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

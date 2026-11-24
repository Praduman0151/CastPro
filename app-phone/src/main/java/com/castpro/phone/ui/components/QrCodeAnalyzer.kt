package com.castpro.phone.ui.components

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX -> ZXing QR detector.
 *
 * Only the Y plane is needed for a QR code, so we avoid a full YUV -> RGB
 * conversion. Frames are throttled because running the decoder at the camera's
 * full rate just burns battery.
 */
class QrCodeAnalyzer(
    private val onQrCode: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }
    private var lastAttemptMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAttemptMs < 120L) return
            lastAttemptMs = now

            val (data, width, height) = image.toLuminance()
            if (width <= 0 || height <= 0) return
            val source = PlanarYUVLuminanceSource(
                data,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            try {
                val result = reader.decodeWithState(bitmap)
                if (result.text.isNotBlank()) onQrCode(result.text)
            } catch (_: NotFoundException) {
                // No QR in this frame; keep scanning.
            } finally {
                reader.reset()
            }
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toLuminance(): Triple<ByteArray, Int, Int> {
        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val sourceWidth = width
        val sourceHeight = height
        val pixels = ByteArray(sourceWidth * sourceHeight)

        if (pixelStride == 1 && rowStride == sourceWidth) {
            buffer.get(pixels, 0, pixels.size)
        } else {
            for (row in 0 until sourceHeight) {
                val rowStart = row * rowStride
                if (rowStart >= buffer.limit()) break
                var index = rowStart
                for (column in 0 until sourceWidth) {
                    if (index >= buffer.limit()) break
                    pixels[row * sourceWidth + column] = buffer.get(index)
                    index += pixelStride
                }
            }
        }

        val degrees = imageInfo.rotationDegrees
        return when (degrees) {
            90 -> Triple(rotate90(pixels, sourceWidth, sourceHeight), sourceHeight, sourceWidth)
            180 -> Triple(rotate180(pixels, sourceWidth, sourceHeight), sourceWidth, sourceHeight)
            270 -> Triple(rotate270(pixels, sourceWidth, sourceHeight), sourceHeight, sourceWidth)
            else -> Triple(pixels, sourceWidth, sourceHeight)
        }
    }

    private fun rotate90(source: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                result[x * height + (height - 1 - y)] = source[y * width + x]
            }
        }
        return result
    }

    private fun rotate180(source: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                result[(height - 1 - y) * width + (width - 1 - x)] = source[y * width + x]
            }
        }
        return result
    }

    private fun rotate270(source: ByteArray, width: Int, height: Int): ByteArray {
        val result = ByteArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                result[(width - 1 - x) * height + y] = source[y * width + x]
            }
        }
        return result
    }
}

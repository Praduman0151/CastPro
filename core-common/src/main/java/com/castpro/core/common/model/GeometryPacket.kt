package com.castpro.core.common.model

import java.nio.ByteBuffer

data class GeometryPacket(
    val width: Int,
    val height: Int,
    val aspectRatio: Float,
    val rotationDegrees: Int
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putFloat(aspectRatio)
        buffer.putInt(rotationDegrees)
        return buffer.array()
    }

    companion object {
        fun fromByteArray(data: ByteArray, offset: Int = 0): GeometryPacket? {
            if (data.size - offset < 16) return null
            val buffer = ByteBuffer.wrap(data, offset, 16)
            val w = buffer.int
            val h = buffer.int
            val ratio = buffer.float
            val rot = buffer.int
            return GeometryPacket(w, h, ratio, rot)
        }
    }
}

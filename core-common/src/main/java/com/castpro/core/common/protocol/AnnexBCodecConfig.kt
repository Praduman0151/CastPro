package com.castpro.core.common.protocol

/**
 * Small Annex-B parser for the codec-specific parameter sets emitted by Android
 * MediaCodec. It deliberately rejects AVCC/length-prefixed data: CSD delivered
 * to this receiver must be Annex-B framed before it is queued as CODEC_CONFIG.
 */
object AnnexBCodecConfig {
    private data class NalUnit(val start: Int, val end: Int, val type: Int)

    fun nalUnitTypes(codecMime: String, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<Int> {
        return parse(codecMime, data, offset, length).map { it.type }
    }

    /** Returns only required parameter-set NAL units, retaining their start codes. */
    fun extractParameterSets(codecMime: String, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray? {
        if (offset < 0 || length <= 0 || offset > data.size - length) return null
        val required = requiredTypes(codecMime) ?: return null
        val units = parse(codecMime, data, offset, length)
        if (!required.all { type -> units.any { it.type == type } }) return null
        val selected = units.filter { it.type in required }
        val total = selected.sumOf { it.end - it.start }
        if (total <= 0) return null
        val result = ByteArray(total)
        var target = 0
        selected.forEach { unit ->
            val count = unit.end - unit.start
            data.copyInto(result, target, unit.start, unit.end)
            target += count
        }
        return result
    }

    fun hasRequiredParameterSets(codecMime: String, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean =
        extractParameterSets(codecMime, data, offset, length) != null

    fun hexPrefix(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, maxBytes: Int = 16): String {
        if (offset < 0 || length < 0 || offset > data.size - length) return ""
        return (offset until minOf(offset + length, offset + maxBytes))
            .joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) }
    }

    private fun requiredTypes(codecMime: String): Set<Int>? = when {
        codecMime.equals("video/avc", ignoreCase = true) -> setOf(7, 8)
        codecMime.equals("video/hevc", ignoreCase = true) -> setOf(32, 33, 34)
        else -> null
    }

    private fun parse(codecMime: String, data: ByteArray, offset: Int, length: Int): List<NalUnit> {
        if (offset < 0 || length <= 0 || offset > data.size - length) return emptyList()
        val hevc = codecMime.equals("video/hevc", ignoreCase = true)
        if (!hevc && !codecMime.equals("video/avc", ignoreCase = true)) return emptyList()
        val end = offset + length
        val starts = ArrayList<Pair<Int, Int>>()
        var i = offset
        while (i + 3 < end) {
            val startCodeLength = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeLength > 0) {
                starts += i to startCodeLength
                i += startCodeLength
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexedNotNull { index, item ->
            val header = item.first + item.second
            val unitEnd = if (index + 1 < starts.size) starts[index + 1].first else end
            if (header >= unitEnd) return@mapIndexedNotNull null
            val first = data[header].toInt() and 0xFF
            val type = if (hevc) (first ushr 1) and 0x3F else first and 0x1F
            NalUnit(item.first, unitEnd, type)
        }
    }
}

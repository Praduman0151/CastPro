package com.castpro.core.common.protocol

import org.junit.Assert.*
import org.junit.Test

class AnnexBCodecConfigTest {
    @Test fun extractsAvcSpsAndPpsWithAnnexBStartCodes() {
        val input = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1E,
            0, 0, 1, 0x68, 0xCE.toByte(), 0x06
        )
        val extracted = AnnexBCodecConfig.extractParameterSets("video/avc", input)
        assertNotNull(extracted)
        assertArrayEquals(input, extracted)
        assertEquals(listOf(7, 8), AnnexBCodecConfig.nalUnitTypes("video/avc", extracted!!))
    }

    @Test fun extractsHevcVpsSpsPpsFromCombinedCsd() {
        val input = byteArrayOf(
            0, 0, 0, 1, 0x40, 1, 0x11,
            0, 0, 0, 1, 0x42, 1, 0x22,
            0, 0, 0, 1, 0x44, 1, 0x33
        )
        val extracted = AnnexBCodecConfig.extractParameterSets("video/hevc", input)
        assertNotNull(extracted)
        assertArrayEquals(input, extracted)
        assertEquals(listOf(32, 33, 34), AnnexBCodecConfig.nalUnitTypes("video/hevc", extracted!!))
    }

    @Test fun rejectsMissingParameterSetsAndLengthPrefixedData() {
        assertNull(AnnexBCodecConfig.extractParameterSets("video/avc", byteArrayOf(0, 0, 0, 1, 0x67, 0x42)))
        assertNull(AnnexBCodecConfig.extractParameterSets("video/avc", byteArrayOf(0, 0, 0, 2, 0x67, 0x42)))
    }
}

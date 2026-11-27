package com.castpro.core.streaming.lifecycle

import org.junit.Assert.*
import org.junit.Test

class RecoveryPoliciesTest {
    @Test fun orientationPolicyDetectsBothPortraitLandscapeTransitionsOnly() {
        assertTrue(OrientationReconfigurationPolicy.shouldReconfigure(864, 1920, 1920, 864))
        assertTrue(OrientationReconfigurationPolicy.shouldReconfigure(1920, 864, 864, 1920))
        assertFalse(OrientationReconfigurationPolicy.shouldReconfigure(1080, 1920, 900, 1600))
        assertFalse(OrientationReconfigurationPolicy.shouldReconfigure(0, 0, 1920, 864))
    }

    @Test fun videoStallEscalatesFromNoActionToIdrThenPipelineRecreate() {
        assertEquals(VideoStallAction.NONE, VideoStallPolicy.action(2_999))
        assertEquals(VideoStallAction.REQUEST_KEYFRAME, VideoStallPolicy.action(3_000))
        assertEquals(VideoStallAction.REQUEST_KEYFRAME, VideoStallPolicy.action(7_999))
        assertEquals(VideoStallAction.RECREATE_ENCODER, VideoStallPolicy.action(8_000))
    }

    @Test fun cleanupContinuesAfterAnEarlierResourceThrows() {
        val released = mutableListOf<String>()
        val failures = mutableListOf<String>()
        IndependentCleanup.run(
            listOf(
                CleanupStep("webMirror") { released += "webMirror"; error("stop failed") },
                CleanupStep("audio") { released += "audio" },
                CleanupStep("virtualDisplay") { released += "virtualDisplay" },
                CleanupStep("encoder") { released += "encoder" },
                CleanupStep("projection") { released += "projection" },
                CleanupStep("transport") { released += "transport" }
            )
        ) { name, _ -> failures += name }

        assertEquals(
            listOf("webMirror", "audio", "virtualDisplay", "encoder", "projection", "transport"),
            released
        )
        assertEquals(listOf("webMirror"), failures)
    }
}

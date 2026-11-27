package com.castpro.core.streaming.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRegistryTest {

    @Test
    fun strayCandidateCannotReplaceCurrentBeforePromotion() {
        val registry = ConnectionRegistry<String>()
        assertNull(registry.register("phone-a"))
        assertNull(registry.promote("phone-a"))
        assertEquals("phone-a", registry.current)

        assertNull(registry.register("stray"))
        assertEquals("phone-a", registry.current)
        assertFalse(registry.isCurrent("stray"))
    }

    @Test
    fun firstValidPacketPromotesCandidateAndEvictsOldCurrent() {
        val registry = ConnectionRegistry<String>()
        registry.register("old")
        registry.promote("old")
        registry.register("new")

        assertEquals("old", registry.promote("new"))
        assertEquals("new", registry.current)
        assertTrue(registry.isCurrent("new"))
    }

    @Test
    fun promotionIsIdempotentForCurrentClient() {
        val registry = ConnectionRegistry<String>()
        registry.register("phone")
        assertNull(registry.promote("phone"))
        assertNull(registry.promote("phone"))
        assertEquals("phone", registry.current)
    }

    @Test
    fun removeSignalsDisconnectOnlyForCurrentClient() {
        val registry = ConnectionRegistry<String>()
        registry.register("phone")
        registry.promote("phone")
        registry.register("stray")

        assertFalse(registry.remove("stray"))
        assertEquals("phone", registry.current)
        assertTrue(registry.remove("phone"))
        assertNull(registry.current)
    }

    @Test
    fun registeringNewCandidateReturnsPreviousCandidateForCleanup() {
        val registry = ConnectionRegistry<String>()
        assertNull(registry.register("first-candidate"))
        assertEquals("first-candidate", registry.register("second-candidate"))
        assertNull(registry.current)
        assertNull(registry.promote("second-candidate"))
        assertEquals("second-candidate", registry.current)
    }
}

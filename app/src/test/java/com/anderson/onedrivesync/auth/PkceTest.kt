package com.anderson.onedrivesync.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 7636 primitives: verifier charset, S256 challenge and the opaque state. */
class PkceTest {
    @Test
    fun `verifier has an allowed length and charset`() {
        val verifier = newVerifier()

        assertTrue("length was ${verifier.length}", verifier.length in 43..128)
        assertTrue("charset was $verifier", verifier.matches(Regex("[A-Za-z0-9\\-._~]+")))
    }

    @Test
    fun `verifiers are random`() {
        assertNotEquals(newVerifier(), newVerifier())
    }

    @Test
    fun `challenge matches the RFC 7636 appendix b vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge(verifier))
    }

    @Test
    fun `challenge is base64url without padding and is deterministic`() {
        val verifier = newVerifier()
        val first = challenge(verifier)

        assertEquals(first, challenge(verifier))
        assertEquals(43, first.length)
        assertFalse(first.contains("="))
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `state is random and url safe`() {
        val state = newState()

        assertNotEquals(state, newState())
        assertEquals(43, state.length)
        assertTrue(state.matches(Regex("[A-Za-z0-9_-]+")))
    }
}

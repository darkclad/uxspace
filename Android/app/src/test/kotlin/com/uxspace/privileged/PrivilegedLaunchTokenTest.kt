package com.uxspace.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedLaunchTokenTest {
    @Test
    fun issuedTokenIsAcceptedExactlyOnce() {
        val gate = PrivilegedLaunchToken()
        val token = gate.issue()

        assertEquals(PrivilegedLaunchToken.BYTE_COUNT, token.size)
        assertTrue(gate.consume(token, acceptingPublications = true))
        assertFalse(gate.consume(token, acceptingPublications = true))
    }

    @Test
    fun wrongTokenDoesNotBlockRealHelper() {
        val gate = PrivilegedLaunchToken()
        val token = gate.issue()
        val wrong = token.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertFalse(gate.consume(wrong, acceptingPublications = true))
        assertTrue(gate.consume(token, acceptingPublications = true))
    }

    @Test
    fun publicationOutsideStartingDoesNotConsumeToken() {
        val gate = PrivilegedLaunchToken()
        val token = gate.issue()

        assertFalse(gate.consume(token, acceptingPublications = false))
        assertTrue(gate.consume(token, acceptingPublications = true))
    }

    @Test
    fun missingOrMalformedTokenIsRejected() {
        val gate = PrivilegedLaunchToken()
        val token = gate.issue()

        assertFalse(gate.consume(null, acceptingPublications = true))
        assertFalse(gate.consume(ByteArray(1), acceptingPublications = true))
        assertTrue(gate.consume(token, acceptingPublications = true))
    }

    @Test
    fun issuingAgainInvalidatesPreviousToken() {
        val gate = PrivilegedLaunchToken()
        val first = gate.issue()
        val second = gate.issue()

        assertFalse(gate.consume(first, acceptingPublications = true))
        assertTrue(gate.consume(second, acceptingPublications = true))
    }

    @Test
    fun clearingRejectsPendingToken() {
        val gate = PrivilegedLaunchToken()
        val token = gate.issue()

        gate.clear()

        assertFalse(gate.consume(token, acceptingPublications = true))
    }
}

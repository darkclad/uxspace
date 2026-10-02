package com.uxspace.privileged

import java.security.MessageDigest
import java.security.SecureRandom

/** One-use authenticator for the shell helper currently being started by UxSpace. */
internal class PrivilegedLaunchToken {
    private var pending: ByteArray? = null

    @Synchronized
    fun issue(): ByteArray {
        pending?.fill(0)
        val token = ByteArray(BYTE_COUNT).also(SecureRandom()::nextBytes)
        pending = token
        return token.copyOf()
    }

    /**
     * Consume the pending token only for a publication that arrived in the expected state.
     * Failed attempts leave it intact so an unrelated shell process cannot block the real helper.
     */
    @Synchronized
    fun consume(candidate: ByteArray?, acceptingPublications: Boolean): Boolean {
        val expected = pending ?: return false
        if (!acceptingPublications || candidate == null || candidate.size != BYTE_COUNT) {
            return false
        }
        if (!MessageDigest.isEqual(expected, candidate)) return false
        pending = null
        expected.fill(0)
        return true
    }

    @Synchronized
    fun clear() {
        pending?.fill(0)
        pending = null
    }

    companion object {
        const val BYTE_COUNT = 32
    }
}

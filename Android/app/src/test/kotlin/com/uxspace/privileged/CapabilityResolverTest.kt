package com.uxspace.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolverTest {
    @Test
    fun firstAvailableCandidateIsPreferred() {
        var modernCalls = 0
        val resolution = resolveCapability(
            listOf(
                CapabilityCandidate("legacy") { "samsung" },
                CapabilityCandidate("modern") {
                    modernCalls++
                    "pixel"
                },
            ),
        )

        assertEquals("samsung", resolution.value)
        assertEquals("legacy", resolution.selectedName)
        assertEquals(0, modernCalls)
        assertTrue(resolution.failures.isEmpty())
    }

    @Test
    fun missingLegacyFallsBackToModernBackend() {
        val resolution = resolveCapability(
            listOf(
                CapabilityCandidate("legacy") { throw NoSuchMethodException("getInstance") },
                CapabilityCandidate("modern") { "pixel" },
                CapabilityCandidate("context") { "fallback" },
            ),
        )

        assertEquals("pixel", resolution.value)
        assertEquals("modern", resolution.selectedName)
        assertEquals(listOf("legacy: getInstance"), resolution.failures)
    }

    @Test
    fun contextBackendIsFinalFallback() {
        val resolution = resolveCapability(
            listOf(
                CapabilityCandidate("legacy") { error("legacy unavailable") },
                CapabilityCandidate("modern") { error("modern unavailable") },
                CapabilityCandidate("context") { "oem" },
            ),
        )

        assertEquals("oem", resolution.value)
        assertEquals("context", resolution.selectedName)
        assertEquals(2, resolution.failures.size)
    }

    @Test
    fun completeFailureRetainsDiagnostics() {
        val resolution = resolveCapability(
            listOf(
                CapabilityCandidate<String>("legacy") {
                    throw NoSuchMethodException("legacy method")
                },
                CapabilityCandidate<String>("modern") {
                    throw ClassNotFoundException("modern class")
                },
            ),
        )

        assertNull(resolution.value)
        assertNull(resolution.selectedName)
        assertEquals(
            listOf("legacy: legacy method", "modern: modern class"),
            resolution.failures,
        )
    }

    @Test
    fun nestedReflectionFailureReportsRootCause() {
        val resolution = resolveCapability(
            listOf(
                CapabilityCandidate<String>("legacy") {
                    throw java.lang.reflect.InvocationTargetException(
                        IllegalStateException("no application context"),
                    )
                },
            ),
        )

        assertEquals(listOf("legacy: no application context"), resolution.failures)
    }
}

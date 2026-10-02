package com.uxspace.privileged

/** One runtime implementation of a hidden or OEM-varying platform capability. */
internal data class CapabilityCandidate<T : Any>(
    val name: String,
    val resolve: () -> T,
)

internal data class CapabilityResolution<T : Any>(
    val value: T?,
    val selectedName: String?,
    val failures: List<String>,
)

/**
 * Select the first usable runtime implementation, retaining concise diagnostics for every
 * rejected candidate. Platform internals vary across Android and OEM releases, so capability
 * probing is deliberately preferred to manufacturer or SDK-version checks.
 */
internal fun <T : Any> resolveCapability(
    candidates: List<CapabilityCandidate<T>>,
): CapabilityResolution<T> {
    val failures = mutableListOf<String>()
    for (candidate in candidates) {
        try {
            return CapabilityResolution(candidate.resolve(), candidate.name, failures)
        } catch (t: Exception) {
            var cause: Throwable = t
            while (cause.cause != null && cause.cause !== cause) {
                cause = cause.cause!!
            }
            val detail = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
            failures += "${candidate.name}: $detail"
        }
    }
    return CapabilityResolution(null, null, failures)
}

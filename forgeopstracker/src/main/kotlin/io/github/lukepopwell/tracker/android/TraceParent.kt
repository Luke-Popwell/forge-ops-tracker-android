package io.github.lukepopwell.tracker.android

import java.security.SecureRandom

/**
 * Writes the W3C Trace Context `traceparent` header (https://www.w3.org/TR/trace-context/), the
 * vendor-neutral format for carrying one trace across service boundaries:
 * `00-<32 hex trace id>-<16 hex parent span id>-<2 hex flags>`. [Trace.startHttpSpan] and
 * [Trace.measureHttpSpan] put it on an outgoing request, so a backend that understands the standard
 * (`gems/forge_ops_tracker` from 0.12.0, or any other) continues this trace instead of starting its
 * own. Write-only: an app makes requests, it doesn't receive them, so there is nothing to parse.
 */
object TraceParent {
    const val HEADER = "traceparent"

    // Always "01" (sampled): whether a trace is sent is only decided once it finishes (see
    // Configuration.traceCaptureThresholdMillis), long after this header has gone out, so "this may
    // be recorded" is the only honest answer. The backend makes its own decision either way.
    private const val SAMPLED_FLAGS = "01"

    private val RANDOM = SecureRandom()

    fun build(traceId: String, spanId: String): String = "00-$traceId-$spanId-$SAMPLED_FLAGS"

    /** 32 lowercase hex characters, never all zeros (the spec's one invalid value). */
    @PublishedApi internal fun generateTraceId(): String = randomNonZeroHex(16)

    /** 16 lowercase hex characters, never all zeros. */
    @PublishedApi internal fun generateSpanId(): String = randomNonZeroHex(8)

    private fun randomNonZeroHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        do RANDOM.nextBytes(raw) while (raw.all { it == 0.toByte() })
        return raw.joinToString("") { "%02x".format(it) }
    }
}

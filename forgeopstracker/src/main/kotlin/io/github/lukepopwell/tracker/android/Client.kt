package io.github.lukepopwell.tracker.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * What became of one delivery attempt. [REJECTED] is a 4xx other than 408 and 429: the server will
 * never take this payload, so whoever holds it lets it go instead of sending it again. [FAILED] is
 * everything that may work later (no DSN, a network error, a timeout, a 5xx, 408, 429), so the
 * payload is kept and retried, within the bounds each caller sets.
 */
internal enum class DeliveryOutcome {
    DELIVERED,
    REJECTED,
    FAILED,
    ;

    companion object {
        /** `status` is the HTTP status of the response, or 0 when there was none. */
        fun of(status: Int): DeliveryOutcome = when {
            status in 200..299 -> DELIVERED
            status in 400..499 && status != 408 && status != 429 -> REJECTED
            else -> FAILED
        }

        /** Why a payload with this status was let go, for the one log line that says so. */
        fun dropReason(status: Int): String = when {
            of(status) == REJECTED -> "rejected by the server (HTTP $status)"
            status == 0 -> "the server could not be reached"
            else -> "the server answered HTTP $status"
        }
    }
}

/**
 * Delivers one payload over HTTP via [HttpURLConnection]: present on Android since API 1,
 * unlike `java.net.http.HttpClient` (the choice `sdks/java`/`sdks/kotlin` make for plain-JVM
 * server apps), which Android didn't support until a very recent API level. `HttpURLConnection`
 * is the correct, broadly-compatible choice for an Android library specifically: not a
 * downgrade from the newer API, a different platform's own idiomatic answer to the same problem.
 * Every failure mode (DNS, connection, timeout, TLS, a non-2xx response) is caught here and
 * turned into a `false` return rather than a thrown exception, since a broken or unreachable
 * tracker must never be able to break the host app.
 */
class Client(private val configuration: Configuration) {
    fun deliver(payload: JSONObject): Boolean = delivered(deliverStatus(payload))

    /**
     * Same delivery contract as [deliver], against the DSN's performance_samples endpoint (see
     * [Configuration.performanceSamplesUri]). `samples` is wrapped as `{"samples": [...]}`, the
     * shape `Api::V1::PerformanceSamplesController` expects.
     */
    fun deliverPerformanceSamples(samples: org.json.JSONArray): Boolean = delivered(performanceSamplesStatus(samples))

    /**
     * Same delivery contract again, against the DSN's custom metrics and infrastructure metrics
     * endpoints. `entries` is wrapped as `{"metrics": [...]}`.
     */
    fun deliverMetrics(entries: JSONArray): Boolean = delivered(metricsStatus(entries))

    fun deliverInfrastructureMetrics(entries: JSONArray): Boolean = delivered(infrastructureMetricsStatus(entries))

    /**
     * Same delivery contract again, against the DSN's spans endpoint (see [Configuration.spansUri]).
     * `trace` is sent as-is: `{"trace_id": ..., "spans": [...]}`, the shape `Api::V1::SpansController`
     * expects.
     */
    fun deliverSpans(trace: JSONObject): Boolean = delivered(post(configuration.spansUri, trace))

    /**
     * Same delivery contract again, against the DSN's changes endpoint (see [Configuration.changesUri]).
     * A 403 (a plan without change tracking) is just a `false` like any other rejection.
     */
    fun deliverChange(change: JSONObject): Boolean = delivered(post(configuration.changesUri, change))

    /**
     * The same requests as [deliver], [deliverPerformanceSamples], [deliverMetrics] and
     * [deliverInfrastructureMetrics], returning the HTTP status instead (0 when there was no
     * response), for the callers inside this library that hold on to a payload and so need to know
     * whether a failure is final (see [DeliveryOutcome]), not only that it happened.
     */
    internal fun deliverStatus(payload: JSONObject): Int = post(configuration.ingestionUri, payload)

    internal fun performanceSamplesStatus(samples: JSONArray): Int =
        post(configuration.performanceSamplesUri, JSONObject().put("samples", samples))

    internal fun metricsStatus(entries: JSONArray): Int = post(configuration.customMetricsUri, JSONObject().put("metrics", entries))

    internal fun infrastructureMetricsStatus(entries: JSONArray): Int =
        post(configuration.infrastructureMetricsUri, JSONObject().put("metrics", entries))

    private fun delivered(status: Int): Boolean = DeliveryOutcome.of(status) == DeliveryOutcome.DELIVERED

    private fun post(uri: java.net.URI?, payload: JSONObject): Int {
        uri ?: return 0
        val apiKey = configuration.apiKey ?: return 0

        var connection: HttpURLConnection? = null
        return try {
            connection = uri.toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = configuration.connectTimeoutMillis
            connection.readTimeout = configuration.readTimeoutMillis
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            connection.responseCode
        } catch (e: Exception) {
            configuration.log("delivery failed: ${e.javaClass.simpleName}: ${e.message}")
            0
        } finally {
            connection?.disconnect()
        }
    }
}

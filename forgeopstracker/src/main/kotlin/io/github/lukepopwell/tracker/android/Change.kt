package io.github.lukepopwell.tracker.android

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the `POST /api/v1/changes` body for [ForgeOpsTracker.recordChange]. Separate from the
 * facade so the payload shape can be tested without a network round trip.
 */
object Change {
    /** The kinds the changes endpoint accepts; anything else is sent as `"other"` rather than rejected. */
    val KINDS: Set<String> = setOf("feature_flag", "config", "migration", "dependency", "infrastructure", "other")

    /** The server's own limit on a title; a longer one is truncated here instead of rejected there. */
    const val MAX_TITLE_LENGTH: Int = 200

    fun normalizeKind(kind: String?): String = if (kind in KINDS) kind!! else "other"

    /**
     * `null` for a change with no title, the one field the server can't do without. [details] that
     * can't be encoded as JSON (a NaN or infinite number, say) and a [url] that isn't http(s) are left
     * out rather than sent, since the server would reject the whole change.
     */
    fun payload(
        configuration: Configuration,
        kind: String?,
        title: String?,
        details: Map<String, Any?>? = null,
        environment: String? = null,
        service: String? = null,
        actor: String? = null,
        url: String? = null,
        id: String? = null,
        occurredAt: Date? = null,
    ): JSONObject? {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val payload = JSONObject()
            .put("kind", normalizeKind(kind))
            .put("title", trimmed.take(MAX_TITLE_LENGTH))
            .put("environment", environment ?: configuration.environment)
            .put("occurred_at", iso8601(occurredAt ?: Date()))
        details?.let { toJson(it) }?.let { payload.put("details", it) }
        if (!service.isNullOrBlank()) payload.put("service", service)
        if (!actor.isNullOrBlank()) payload.put("actor", actor)
        if (url != null && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
            payload.put("url", url)
        }
        if (!id.isNullOrBlank()) payload.put("id", id)
        return payload
    }

    // Built value by value through put(), which rejects a NaN or infinite number with a
    // JSONException, rather than the JSONObject(Map) constructor, which accepts one and then makes
    // toString() return null.
    private fun toJson(details: Map<String, Any?>): JSONObject? = try {
        jsonValue(details) as JSONObject
    } catch (e: Exception) {
        null
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), jsonValue(v)) } }
        is Iterable<*> -> JSONArray().also { array -> value.forEach { array.put(jsonValue(it)) } }
        is Double -> value.also { require(it.isFinite()) }
        is Float -> value.also { require(it.isFinite()) }
        is String, is Boolean, is Number -> value
        else -> value.toString()
    }

    private fun iso8601(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
}

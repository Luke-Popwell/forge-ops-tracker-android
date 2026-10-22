package io.github.lukepopwell.tracker.android

import java.security.MessageDigest
import java.util.Locale

/**
 * The rules [Configuration.strictPrivacy] applies, for customers who can't let personal or health
 * data leave their own servers: only structured facts about an error are sent, never free text
 * that could hold a name, an email or a diagnosis. Ported from the server's own `StrictPrivacy`
 * (see `sdks/STRICT_PRIVACY.md`), which applies the same rules again on arrival when the project's
 * own Strict privacy setting is on; `strict_privacy_vectors.json` in the test resources pins the
 * results, so the two can't drift apart unnoticed.
 *
 * "Word character" is ASCII `[A-Za-z0-9_]` only, spelled out as a class rather than `\w`/`\b`, so
 * no regex option can make a digit in another script or an accented letter count as one.
 */
object StrictPrivacy {
    internal const val FINGERPRINT_LENGTH = 32

    private const val WORD = "A-Za-z0-9_"
    private val EMAIL = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    // A quoted value (KeyError: 'Jane Doe') is data, not the shape of the error. Single quotes only open
    // and close at a word boundary, so a contraction (Couldn't) or the `email' style never starts one.
    private val DOUBLE_QUOTED = Regex("\"[^\"\\r\\n]*\"")
    private val SINGLE_QUOTED = Regex("(?<![$WORD])'[^'\\r\\n]*'(?![$WORD])")
    private val HEX_OBJECT_ID = Regex("0x[0-9a-fA-F]{4,}")
    private val UUID = Regex("(?<![$WORD])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![$WORD])")
    private val NUMBER = Regex("(?<![$WORD])[0-9]+(?![$WORD])")
    private val HTTP_METHOD = Regex("^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+(\\S+)", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val SPLIT_AUTHORITY = Regex("[/?#]")
    private val PORT = Regex(":[0-9]+\\z")
    private val ASCII_DIGIT = Regex("[0-9]")
    private const val MAX_SEGMENT_LENGTH = 40
    private val SPLIT_QUERY = Regex("[?#]")

    // A route template's parameter segment: ":id", "{id}", "<int:id>", "[slug]" or "*path".
    private val ROUTE_PARAMETER = Regex("\\A(?::[^\\n]+|\\{[^\\n]+\\}|<[^\\n]+>|\\[[^\\n]+\\]|\\*[^\\n]*)\\z")

    // Span data that's structure, not content: the masked statement and which database it ran on.
    private val SPAN_DATA_KEYS = listOf("db.statement", "db.system")

    /**
     * The message with emails, quoted values, hex object ids, UUIDs and standalone numbers replaced, in
     * that order; `null` is `""`.
     */
    fun normalizeMessage(message: String?): String =
        (message ?: "").replace(EMAIL, "<email>").replace(DOUBLE_QUOTED, "<str>").replace(SINGLE_QUOTED, "<str>")
            .replace(HEX_OBJECT_ID, "0x").replace(UUID, "<uuid>").replace(NUMBER, "<n>")

    /**
     * What's sent in place of the message: the first 32 lowercase hex characters of the SHA-256 of
     * the normalized message's UTF-8 bytes. Lets ForgeOps group errors without ever seeing the text.
     */
    fun messageFingerprint(message: String?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizeMessage(message).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(Locale.US, it) }.take(FINGERPRINT_LENGTH)
    }

    /**
     * A path that can't name a person, with no query or fragment. A route template (any segment a
     * parameter: `:id`, `{id}`, `<int:id>`, `[slug]`, `*path`) is kept as it is. A raw path keeps
     * only its first segment (`":id"` if it holds an ASCII digit or is longer than 40 characters),
     * then `"/:path"` if there was more: `"/patients/jane-doe/12"` is `"/patients/:path"`. No
     * segments at all is `"/"`.
     */
    fun strictPath(path: String?): String {
        val withoutQuery = (path ?: "").split(SPLIT_QUERY, limit = 2)[0]
        val all = withoutQuery.split("/")
        if (all.any { ROUTE_PARAMETER.matches(it) }) return withoutQuery
        val segments = all.filter { it.isNotEmpty() }
        val first = segments.firstOrNull() ?: return "/"
        val id = ASCII_DIGIT.containsMatchIn(first) || first.codePointCount(0, first.length) > MAX_SEGMENT_LENGTH
        return (if (withoutQuery.startsWith("/")) "/" else "") + (if (id) ":id" else first) + (if (segments.size > 1) "/:path" else "")
    }

    /** Renamed [strictPath], which it now calls; kept so existing callers still compile. */
    @Deprecated("Renamed strictPath", ReplaceWith("strictPath(path)"))
    fun pathWithoutIds(path: String?): String = strictPath(path)

    /**
     * A span name (or a transaction name) with no path or host detail that could name a person:
     * `"GET https://api.example.com/patients/42?x=1"` is `"GET api.example.com"`, and a path, bare
     * (`"/patients/42"`) or after a method (`"GET /patients/42"`), has its ids taken out. A target
     * that isn't a host keeps its path with ids taken out; anything else (`"OrdersController#create"`)
     * is returned unchanged. Read by hand, never with a URL parser, so an address a parser would
     * reject can't make the whole address go out.
     */
    fun spanName(name: String): String {
        if (name.startsWith("/")) return strictPath(name)
        val match = HTTP_METHOD.find(name) ?: return name
        val method = match.groupValues[1].uppercase(Locale.US)
        val target = match.groupValues[2]
        if (target.startsWith("/")) return "$method ${strictPath(target)}"
        return "$method ${hostOf(target) ?: strictPath(target)}"
    }

    /**
     * The host of an outbound call's target: without any `scheme://`, userinfo or `:port`, and only
     * when it really is one (the address had a scheme or a port, or the host has a dot or is
     * `localhost`); `null` otherwise.
     */
    private fun hostOf(target: String): String? {
        val scheme = SCHEME.find(target)
        val authority = (if (scheme != null) target.substring(scheme.range.last + 1) else target)
            .split(SPLIT_AUTHORITY, limit = 2)[0].substringAfterLast('@')
        val port = PORT.find(authority)
        val host = if (port != null) authority.substring(0, port.range.first) else authority
        if (host.isEmpty()) return null
        return host.takeIf { scheme != null || port != null || "." in host || it.equals("localhost", ignoreCase = true) }
    }

    /** Only the allowed top-level keys with a string, number or boolean value; `null` when nothing is left. */
    internal fun allowed(fields: Map<String, Any?>?, allowedFields: Collection<String>): Map<String, Any?>? {
        if (fields == null || allowedFields.isEmpty()) return null
        val kept = fields.filter { (key, value) -> key in allowedFields && (value is String || value is Number || value is Boolean) }
        return kept.ifEmpty { null }
    }

    /** The user's `id` only, as a string; `null` when there's no id. */
    internal fun user(user: Map<String, Any?>?): Map<String, Any?>? {
        val id = user?.get("id")?.toString()
        return if (id.isNullOrBlank()) null else mapOf("id" to id)
    }

    /** Span data keeps only the (already masked) `db.statement` and `db.system`. */
    internal fun spanData(data: Map<String, Any?>): Map<String, Any?> = data.filterKeys { it in SPAN_DATA_KEYS }
}

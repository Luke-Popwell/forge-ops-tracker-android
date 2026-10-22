package io.github.lukepopwell.tracker.android

/**
 * Redacts likely-sensitive content out of a payload before it ever leaves the device: the same
 * patterns ForgeOps itself applies again on arrival (defense in depth: this layer keeps the data
 * off the wire and out of the crash report file on disk; the server-side layer is what actually
 * protects the database). Same implementation as `sdks/kotlin`'s own `PiiScrubber` (nothing here
 * is Android-specific), duplicated rather than shared as a dependency so this library has zero
 * dependencies of its own, including on another module in this repo: see README.md's
 * "Dependencies" section.
 */
object PiiScrubber {
    const val REDACTED = "[FILTERED]"

    private val SENSITIVE_KEYS = setOf(
        "password", "passwd", "pwd",
        "secret", "apisecret", "clientsecret", "secretkey",
        "token", "accesstoken", "refreshtoken", "apikey", "apitoken", "authorization", "authtoken",
        "bearer", "sessiontoken", "csrftoken",
        "creditcard", "cardnumber", "cardnum", "cvv", "cvv2", "cvc",
        "ssn", "socialsecuritynumber", "socialsecurity",
        "privatekey",
    )

    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "EMAIL" to Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
        "SSN" to Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        "CREDIT CARD" to Regex("\\b\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{1,4}\\b"),
        "BEARER TOKEN" to Regex("\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*", RegexOption.IGNORE_CASE),
        "JWT" to Regex("\\bey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        "AWS KEY" to Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        "STRIPE KEY" to Regex("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{10,}\\b"),
        "GITHUB TOKEN" to Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
    )

    fun scrub(value: Any?, key: String? = null): Any? {
        if (value != null && isSensitiveKey(key)) {
            return REDACTED
        }

        return when (value) {
            is Map<*, *> -> value.entries.associate { (k, v) ->
                val stringKey = k.toString()
                stringKey to scrub(v, stringKey)
            }
            is List<*> -> value.map { scrub(it, key) }
            is String -> scrubString(value)
            else -> value
        }
    }

    /** Exposed (not private) so EventBuilder's own JSON-tree scrubbing: which walks
     * `org.json.JSONObject`/`JSONArray`, not Kotlin `Map`/`List`, so it can't reuse [scrub]
     * directly: can still share this one key-sensitivity check rather than duplicating it. */
    fun isSensitiveKey(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        val normalized = key.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        return SENSITIVE_KEYS.any { normalized.contains(it) }
    }

    private fun scrubString(input: String): String {
        var result = input
        for ((label, pattern) in PATTERNS) {
            result = pattern.replace(result, "[$label FILTERED]")
        }
        return result
    }
}

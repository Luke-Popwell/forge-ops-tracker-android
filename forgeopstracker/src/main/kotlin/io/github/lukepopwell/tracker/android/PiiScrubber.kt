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
        "iban", "accountnumber", "routingnumber", "bankaccount",
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

    // Card numbers in any other shape, and IBANs: see sdks/PII_SCRUBBING.md, which every SDK
    // follows, and pii_scrubbing_vectors.json in the test resources, which pins the results. Word
    // characters are ASCII only, so the boundaries are spelled out as [A-Za-z0-9_].
    private val DIGIT_RUN = Regex("(?<![A-Za-z0-9_])[0-9](?:[ -]?[0-9])*(?![A-Za-z0-9_])")
    private val CARD_PREFIXES = Regex(
        "^(?:4|5[1-5]|2(?:22[1-9]|2[3-9][0-9]|[3-6][0-9][0-9]|7[01][0-9]|720)|3[47]|6011|64[4-9]|65" +
            "|35(?:2[89]|[3-8][0-9])|36|30[0-5]|62)",
    )
    private val IBAN = Regex(
        "(?<![A-Za-z0-9_])[A-Z]{2}[0-9]{2}(?:[A-Z0-9]{11,30}|(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,4})?)" +
            "(?![A-Za-z0-9_])",
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
        // IBANs first: a spaced IBAN's middle groups look like a formatted card number.
        var result = IBAN.replace(input) { if (isIban(it.value)) "[IBAN FILTERED]" else it.value }
        for ((label, pattern) in PATTERNS) {
            result = pattern.replace(result, "[$label FILTERED]")
        }
        return DIGIT_RUN.replace(result) { scrubCardNumbersIn(it.value) }
    }

    /** From each group of the run on, the longest stretch of whole groups that is a card number is
     * replaced, so a card followed by its CVV, two cards in a row, and Amex's 4-6-5 are all found. */
    private fun scrubCardNumbersIn(run: String): String {
        val digits = mutableListOf<String>()
        val separators = mutableListOf<String>()
        var start = 0
        for ((at, c) in run.withIndex()) {
            if (c == ' ' || c == '-') {
                digits.add(run.substring(start, at))
                separators.add(c.toString())
                start = at + 1
            }
        }
        digits.add(run.substring(start))
        separators.add("")

        val out = StringBuilder()
        var i = 0
        while (i < digits.size) {
            val j = (digits.size - 1 downTo i).firstOrNull { last -> isCardNumber(digits.subList(i, last + 1).joinToString("")) }
            if (j != null) {
                out.append("[CREDIT CARD FILTERED]").append(separators[j])
                i = j + 1
            } else {
                out.append(digits[i]).append(separators[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isCardNumber(digits: String): Boolean =
        digits.length in 13..19 && CARD_PREFIXES.containsMatchIn(digits) && isLuhn(digits)

    private fun isLuhn(digits: String): Boolean {
        var sum = 0
        for ((index, c) in digits.reversed().withIndex()) {
            var value = c - '0'
            if (index % 2 == 1) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
        }
        return sum % 10 == 0
    }

    /** The IBAN check, a digit at a time: the first four characters moved to the end, letters as
     * two digits (A is 10), and the remainder by 97 is 1. */
    private fun isIban(candidate: String): Boolean {
        val compact = candidate.replace(" ", "")
        if (compact.length !in 15..34) return false
        val rearranged = compact.substring(4) + compact.substring(0, 4)
        var remainder = 0
        for (c in rearranged) {
            val number = if (c in 'A'..'Z') (c - 'A' + 10).toString() else c.toString()
            for (digit in number) {
                remainder = (remainder * 10 + (digit - '0')) % 97
            }
        }
        return remainder == 1
    }
}

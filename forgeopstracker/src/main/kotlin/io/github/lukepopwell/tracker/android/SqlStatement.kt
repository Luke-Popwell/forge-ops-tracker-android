package io.github.lukepopwell.tracker.android

import java.sql.SQLException

/**
 * Finds the SQL behind a database error and reduces it to something safe to send: the names of the
 * stored procedures, tables and views it touched, and (only if
 * [Configuration.captureSqlStatement] is on) the statement itself with every string and number
 * replaced by `?`. Ported from `sdks/kotlin`'s `SqlStatement`, which is itself ported from the
 * server's own `SqlStatementMasker`/`SqlObjectExtractor`: same rules everywhere, and the server
 * applies them again on arrival, so a difference here can only ever mean less is masked
 * client-side, never that something unmasked gets stored.
 *
 * Deliberately a single pass over a few patterns, not a SQL parser.
 */
object SqlStatement {
    const val MASK = "?"
    const val MAX_LENGTH = 4000
    private const val MAX_NAMES = 10
    private const val MAX_NAME_LENGTH = 200
    private const val MAX_CAUSE_DEPTH = 5

    // A string's type prefix (E'', X'', N'', B'', U&'') only counts when it isn't the end of a word,
    // but the quote always does: LIKE'%x%', with no space, is still a string. A backslash escapes the
    // next character ('o\'brien'); where a backslash is just a character, the string is masked on to
    // the end: more than needed, never less. The possessive run of ordinary characters matches
    // exactly what the server's pattern does; it only keeps a long string from costing one stack
    // frame (or backtracking entry) per character, on the JVM and in Android's own regex engine.
    private const val STRING = """(?:(?<![\w${'$'}])(?:[EeXxNnBb]|[Uu]&))?'(?:[^'\\]++|\\(?:.|\z)|'')*(?:'|\z)"""
    private const val DOUBLE_QUOTED_STRING = """"(?:[^"\\]++|\\(?:.|\z)|"")*(?:"|\z)"""
    private const val DOLLAR_QUOTED = """(\${'$'}[A-Za-z_]*\${'$'}).*?(?:\1|\z)"""
    // Integers, decimals (.5 too), exponents, hex and binary, never the digits inside an identifier.
    private const val NUMBER =
        """(?<![\w${'$'}.])(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?)(?!\w)"""
    private val LITERAL = Regex("$STRING|$DOLLAR_QUOTED|$NUMBER", RegexOption.DOT_MATCHES_ALL)

    // MySQL and MariaDB read "double quotes" as a string, not a name, so for those they're masked too.
    private val LITERAL_WITH_DOUBLE_QUOTES =
        Regex("$STRING|$DOUBLE_QUOTED_STRING|$DOLLAR_QUOTED|$NUMBER", RegexOption.DOT_MATCHES_ALL)
    private val DOUBLE_QUOTED_STRING_SYSTEMS = setOf("mysql", "mariadb")

    private const val PART = """(?:[\w${'$'}#@]+|"[^"]+"|\[[^\]]+\]|`[^`]+`)"""
    private const val NAME = "$PART(?:\\.$PART)*"
    private val OPERATIONS = setOf(
        "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "WITH", "CALL", "EXEC", "EXECUTE", "CREATE", "ALTER", "DROP",
        "TRUNCATE",
    )
    private val PROCEDURE_CALL = Regex(
        "\\b(?:CALL|EXEC(?:UTE)?|PERFORM)\\s+(?!IMMEDIATE\\b|FUNCTION\\b|PROCEDURE\\b)($NAME)",
        RegexOption.IGNORE_CASE,
    )
    private val RELATION = Regex("\\b(FROM|JOIN|INTO|UPDATE|TABLE)\\s+($NAME)(\\s*\\()?", RegexOption.IGNORE_CASE)
    private val SELECT_FUNCTION = Regex("\\A\\s*SELECT\\s+($NAME)\\s*\\(", RegexOption.IGNORE_CASE)
    private val BUILTINS = setOf(
        "count", "sum", "min", "max", "avg", "now", "coalesce", "nullif", "lower", "upper", "length", "concat",
        "cast", "date_trunc", "current_timestamp", "current_date", "row_number", "rank", "json_build_object",
        "json_agg", "array_agg",
    )
    private val FROM_INSIDE_FUNCTION = Regex("\\b(?:EXTRACT|SUBSTRING|TRIM|OVERLAY)\\s*\\([^()]*\\)", RegexOption.IGNORE_CASE)
    private val KEYWORDS_NOT_NAMES = setOf("select", "set", "values", "where", "lateral", "only", "unnest", "generate_series")
    private val FULL_NAME = Regex("\\A$NAME\\z")
    private val FROM_WORD = Regex("\\bFROM\\b", RegexOption.IGNORE_CASE)
    private val FIRST_WORD = Regex("\\A\\s*(\\w+)")

    // Accessors JVM database layers use for the failing statement: Hibernate's JDBCException#getSQL,
    // Spring's BadSqlGrammarException#getSql and UncategorizedSQLException#getSql. Read
    // reflectively so this client never depends on any of them.
    private val STATEMENT_ACCESSORS = listOf("getSQL", "getSql")

    // SQLite's own error text ends `..., while compiling: SELECT ...` (JDBC SQLite drivers and
    // Android's SQLiteException both), which is the only place the statement appears when the
    // exception type has no accessor. Best-effort by nature.
    private val SQLITE_COMPILING = Regex("while compiling:\\s*(.+?)\\s*\\z", RegexOption.DOT_MATCHES_ALL)

    /**
     * The raw statement off the error itself or, for an app that wraps a database error in its own
     * exception, off whatever it was raised from (`cause`, or the next exception in a
     * [SQLException] chain).
     */
    fun findIn(error: Throwable?): String? {
        val seen = HashSet<Throwable>()
        val queue = ArrayDeque<Throwable?>()
        queue.add(error)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_CAUSE_DEPTH * 3) {
            val current = queue.removeFirst()
            visited++
            if (current == null || !seen.add(current)) continue
            statementOf(current)?.let { return it }
            queue.add(current.cause)
            if (current is SQLException) queue.add(current.nextException)
        }
        return null
    }

    private fun statementOf(error: Throwable): String? {
        for (accessor in STATEMENT_ACCESSORS) {
            try {
                val method = error.javaClass.getMethod(accessor)
                if (method.returnType == String::class.java && method.parameterCount == 0) {
                    val value = method.invoke(error)
                    if (value is String && value.isNotBlank()) return value
                }
            } catch (e: ReflectiveOperationException) {
                // No such accessor on this class, or it isn't callable: keep looking.
            } catch (e: RuntimeException) {
                // Same: not a source of SQL.
            }
        }
        val fromMessage = error.message?.let { SQLITE_COMPILING.find(it)?.groupValues?.get(1) }
        return fromMessage?.takeIf { it.isNotBlank() }
    }

    /**
     * [system] is the statement's `db.system` when known: for `"mysql"` and `"mariadb"` (any case),
     * double-quoted strings are masked too.
     */
    @JvmOverloads
    fun mask(statement: String?, system: String? = null): String? {
        if (statement.isNullOrBlank()) return null
        val doubleQuotedStrings = system != null && system.lowercase() in DOUBLE_QUOTED_STRING_SYSTEMS
        val masked = try {
            (if (doubleQuotedStrings) LITERAL_WITH_DOUBLE_QUOTES else LITERAL).replace(statement, MASK)
        } catch (e: StackOverflowError) {
            // A string with thousands of escaped quotes in it still recurses once per escape. Rather
            // than send any of it, send nothing but the mask.
            return MASK
        }
        return if (masked.length > MAX_LENGTH) masked.substring(0, MAX_LENGTH) + "..." else masked
    }

    /**
     * Takes an already-masked statement (so a keyword inside a string value can't be mistaken for
     * SQL). Returns null when nothing recognizable was found.
     */
    fun objects(masked: String?): Map<String, Any?>? {
        if (masked.isNullOrBlank()) return null

        val sql = FROM_INSIDE_FUNCTION.replace(masked, " ")
        val procedures = PROCEDURE_CALL.findAll(sql).map { it.groupValues[1] }.toMutableList()
        val relations = mutableListOf<String>()

        for (match in RELATION.findAll(sql)) {
            val name = match.groupValues[2]
            if (name.lowercase() in KEYWORDS_NOT_NAMES) continue
            val keyword = match.groupValues[1].uppercase()
            val functionCall = match.groupValues[3].isNotEmpty() && (keyword == "FROM" || keyword == "JOIN")
            (if (functionCall) procedures else relations).add(name)
        }

        SELECT_FUNCTION.find(sql)?.let { select ->
            val function = select.groupValues[1]
            if (function.lowercase() !in BUILTINS && !FROM_WORD.containsMatchIn(sql)) procedures.add(function)
        }

        val operation = FIRST_WORD.find(sql)?.groupValues?.get(1)?.uppercase() ?: ""
        val result = linkedMapOf<String, Any?>()
        if (operation in OPERATIONS) result["operation"] = operation
        val cleanProcedures = clean(procedures)
        val cleanRelations = clean(relations)
        result["procedures"] = cleanProcedures
        result["relations"] = cleanRelations
        if (cleanProcedures.isEmpty() && cleanRelations.isEmpty() && "operation" !in result) return null
        return result
    }

    private fun clean(names: List<String>): List<String> {
        val cleaned = mutableListOf<String>()
        for (raw in names) {
            val name = raw.trim().take(MAX_NAME_LENGTH)
            if (FULL_NAME.matches(name) && name !in cleaned) cleaned.add(name)
        }
        return cleaned.take(MAX_NAMES)
    }
}

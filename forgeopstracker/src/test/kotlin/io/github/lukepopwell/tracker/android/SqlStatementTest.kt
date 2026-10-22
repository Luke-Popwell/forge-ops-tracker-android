package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqlStatementTest {
    /** Stands in for an exception exposing the statement as getSQL(), like Hibernate's. */
    class FakeJdbcException(message: String, private val sql: String) : RuntimeException(message) {
        @Suppress("unused")
        fun getSQL(): String = sql
    }

    private val sqliteError = RuntimeException("no such table: notes (code 1 SQLITE_ERROR): , while compiling: SELECT * FROM notes WHERE id = 42")

    @Test
    fun `reads the statement out of SQLite's while compiling text and off a getSQL accessor`() {
        assertEquals("SELECT * FROM notes WHERE id = 42", SqlStatement.findIn(sqliteError))
        assertEquals("SELECT 1", SqlStatement.findIn(FakeJdbcException("boom", "SELECT 1")))
        assertEquals("SELECT 1", SqlStatement.findIn(RuntimeException("wrapped", FakeJdbcException("boom", "SELECT 1"))))
        assertNull(SqlStatement.findIn(IllegalStateException("nope")))
        assertNull(SqlStatement.findIn(null))
    }

    @Test
    fun `masks strings and numbers but not identifiers or placeholders`() {
        assertEquals(
            "SELECT * FROM orders2 WHERE email = ? AND id = ? AND x = \$1",
            SqlStatement.mask("SELECT * FROM orders2 WHERE email = 'a@b.co' AND id = 42 AND x = \$1"),
        )
        assertEquals("EXEC sp_x @t = ?", SqlStatement.mask("EXEC sp_x @t = 'it''s'"))
        assertEquals("SELECT ? WHERE n = ?", SqlStatement.mask("SELECT 1 WHERE n = 'oops"))
        assertEquals("DO ?", SqlStatement.mask("DO \$b\$ BEGIN PERFORM 1; END \$b\$"))
        assertNull(SqlStatement.mask("  "))
    }

    @Test
    fun `matches the server's masker on every shared case`() {
        for ((input, system, expected) in SHARED_CASES) {
            assertEquals("$input ($system)", expected, SqlStatement.mask(input, system))
            assertEquals("masking again changed $expected", expected, SqlStatement.mask(expected, system))
        }
    }

    @Test
    fun `is idempotent, truncates, and masks a very long string in one piece`() {
        val once = SqlStatement.mask("SELECT * FROM t WHERE a = 'x' AND b = 9")
        assertEquals(once, SqlStatement.mask(once))
        assertEquals(SqlStatement.MAX_LENGTH + 3, SqlStatement.mask("SELECT " + "a, ".repeat(3000) + " b")!!.length)
        assertEquals("SELECT ?", SqlStatement.mask("SELECT '" + "x".repeat(100_000) + "'"))
        assertEquals("SELECT ?", SqlStatement.mask("SELECT \"" + "x".repeat(100_000) + "\"", "mysql"))
        val escapes = SqlStatement.mask("SELECT '" + "x\\'".repeat(50_000) + "'")
        assertTrue(escapes, escapes == "SELECT ?" || escapes == "?")
    }

    @Test
    fun `finds procedures, views and joined tables`() {
        val found = SqlStatement.objects("EXEC dbo.sp_refund_order @id = ?")!!
        assertEquals(listOf("dbo.sp_refund_order"), found["procedures"])
        assertEquals(
            listOf("v_totals", "public.customers"),
            SqlStatement.objects("SELECT * FROM v_totals t JOIN public.customers c ON c.id = t.id")!!["relations"],
        )
        assertEquals(emptyList<String>(), SqlStatement.objects("INSERT INTO audit_log (a) VALUES (?)")!!["procedures"])
        assertNull(SqlStatement.objects("garbage"))
    }

    private fun newConfiguration() = Configuration(ApplicationProvider.getApplicationContext()).apply {
        environment = "production"
        packageName = "com.acme.myapp"
    }

    @Test
    fun `event builder sends the table name by default and the masked statement only when opted in`() {
        val config = newConfiguration()

        val payload = EventBuilder.buildForThrowable(config, sqliteError, null)
        assertEquals("notes", payload.getJSONObject("sql_objects").getJSONArray("relations").getString(0))
        assertFalse(payload.has("sql_statement"))

        config.captureSqlStatement = true
        assertEquals("SELECT * FROM notes WHERE id = ?", EventBuilder.buildForThrowable(config, sqliteError, null).getString("sql_statement"))

        config.captureSqlObjects = false
        config.captureSqlStatement = false
        val off = EventBuilder.buildForThrowable(config, sqliteError, null)
        assertFalse(off.has("sql_objects"))
        assertFalse(off.has("sql_statement"))
        assertTrue(!EventBuilder.buildForThrowable(newConfiguration(), IllegalStateException("nope"), null).has("sql_objects"))
    }

    companion object {
        /** The server's own test set for SqlStatementMasker: input, db.system (or null), expected. */
        private val SHARED_CASES = listOf<Triple<String, String?, String>>(
            Triple("SELECT * FROM orders WHERE email = 'a@b.co' AND id = 42 LIMIT 10", null, "SELECT * FROM orders WHERE email = ? AND id = ? LIMIT ?"),
            Triple("EXEC sp_note @text = 'it''s broken'", null, "EXEC sp_note @text = ?"),
            Triple("SELECT 1 WHERE name = 'unterminated", null, "SELECT ? WHERE name = ?"),
            Triple("DO \$body\$ BEGIN PERFORM 1; END \$body\$", null, "DO ?"),
            Triple("SELECT \"user id\" FROM orders2 WHERE id = \$1 AND v = sp_v2(?)", null, "SELECT \"user id\" FROM orders2 WHERE id = \$1 AND v = sp_v2(?)"),
            Triple("SELECT price * 1.5 FROM t", null, "SELECT price * ? FROM t"),
            Triple("SELECT * FROM users WHERE name = E'o\\'brien' AND id = 1", null, "SELECT * FROM users WHERE name = ? AND id = ?"),
            Triple("SELECT * FROM users WHERE name = 'o\\'brien' AND id = 1", null, "SELECT * FROM users WHERE name = ? AND id = ?"),
            Triple("SELECT * FROM t WHERE b = X'DEADBEEF' AND s = N'uni' AND u = U&'d\\0061t' AND e = e'x'", null, "SELECT * FROM t WHERE b = ? AND s = ? AND u = ? AND e = ?"),
            Triple("SELECT * FROM t WHERE a LIKE'%secret%'", null, "SELECT * FROM t WHERE a LIKE?"),
            Triple("SELECT * FROM t WHERE f = 0x1F AND b = 0b101 AND n = 3e10 AND m = 1.5E-3 AND k = .5", null, "SELECT * FROM t WHERE f = ? AND b = ? AND n = ? AND m = ? AND k = ?"),
            Triple("SELECT e, t.col, 1e5e FROM t", null, "SELECT e, t.col, 1e5e FROM t"),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "mysql", "SELECT ? FROM t WHERE token = ?"),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "MariaDB", "SELECT ? FROM t WHERE token = ?"),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "postgresql", "SELECT \"user id\" FROM t WHERE token = \"abc123secret\""),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", null, "SELECT \"user id\" FROM t WHERE token = \"abc123secret\""),
            Triple("SELECT * FROM t WHERE a = 'x' AND b = 9", null, "SELECT * FROM t WHERE a = ? AND b = ?"),
            Triple("SELECT * FROM t WHERE a = ? AND b = ?", null, "SELECT * FROM t WHERE a = ? AND b = ?"),
            Triple("SELECT * FROM t WHERE path = 'C:\\\\dir\\\\' AND n = 5", null, "SELECT * FROM t WHERE path = ? AND n = ?"),
            Triple("INSERT INTO t (a, b) VALUES (-5, +3.25e+2)", null, "INSERT INTO t (a, b) VALUES (-?, +?)"),
            Triple("SELECT * FROM t WHERE a = 'secret\\", null, "SELECT * FROM t WHERE a = ?"),
            Triple("SELECT * FROM t WHERE a = \"secret\\", "mysql", "SELECT * FROM t WHERE a = ?"),
        )
    }
}

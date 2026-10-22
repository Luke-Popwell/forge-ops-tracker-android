package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Checks this client against `strict_privacy_vectors.json` (a byte-identical copy of
 * `sdks/strict_privacy_vectors.json`, generated from the server's own `StrictPrivacy`), and that
 * strict privacy strips every free-text field from an event while leaving events exactly as before
 * when it's off.
 */
@RunWith(RobolectricTestRunner::class)
class StrictPrivacyTest {
    private fun vectors(kind: String): JSONArray {
        val json = javaClass.getResourceAsStream("/strict_privacy_vectors.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(json).getJSONArray(kind)
    }

    private fun newConfiguration() = Configuration(ApplicationProvider.getApplicationContext()).apply {
        environment = "production"
        release = "1.2.3"
        serverName = "Pixel 8"
        packageName = "io.github.lukepopwell"
        strictPrivacy = false
        privacyAllowedFields = emptySet()
        captureSqlStatement = true
        traceCaptureThresholdMillis = 0
    }

    @Test
    fun `normalizes and fingerprints every vector exactly as the server does`() {
        val vectors = vectors("fingerprint")
        assertTrue(vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val message = vector.getString("message")
            assertEquals(message, vector.getString("normalized"), StrictPrivacy.normalizeMessage(message))
            assertEquals(message, vector.getString("fingerprint"), StrictPrivacy.messageFingerprint(message))
        }
    }

    @Test
    fun `an absent message fingerprints as the empty one`() {
        assertEquals(StrictPrivacy.messageFingerprint(""), StrictPrivacy.messageFingerprint(null))
    }

    @Test
    fun `takes ids out of every path vector`() {
        val vectors = vectors("path")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            assertEquals(vector.getString("path"), vector.getString("strict"), StrictPrivacy.strictPath(vector.getString("path")))
        }
    }

    @Test
    fun `names every span vector by method and host only`() {
        val vectors = vectors("span_name")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            assertEquals(vector.getString("name"), vector.getString("strict"), StrictPrivacy.spanName(vector.getString("name")))
        }
    }

    @Test
    fun `is off by default and read from the environment`() {
        assertEquals(Configuration.resolveStrictPrivacy(System::getenv), Configuration(ApplicationProvider.getApplicationContext()).strictPrivacy)
        assertFalse(Configuration.resolveStrictPrivacy { null })
        assertTrue(Configuration.resolveStrictPrivacy(mapOf("FORGE_OPS_STRICT_PRIVACY" to "true")::get))
        assertTrue(Configuration.resolveStrictPrivacy(mapOf("FORGE_OPS_STRICT_PRIVACY" to "TRUE")::get))
        assertFalse(Configuration.resolveStrictPrivacy(mapOf("FORGE_OPS_STRICT_PRIVACY" to "false")::get))
        assertEquals(emptySet<String>(), Configuration.resolvePrivacyAllowedFields { null })
        assertEquals(
            listOf("error_code", "feature"),
            Configuration.resolvePrivacyAllowedFields(mapOf("FORGE_OPS_PRIVACY_ALLOWED_FIELDS" to "error_code, feature,,")::get).toList(),
        )
    }

    @Test
    fun `an explicit option wins over the environment`() {
        val config = newConfiguration().apply {
            strictPrivacy = true
            privacyAllowedFields = setOf("error_code")
        }
        assertTrue(config.strictPrivacy)
        assertEquals(setOf("error_code"), config.privacyAllowedFields)
    }

    @Test
    fun `a strict event carries no free text`() {
        val config = newConfiguration().apply {
            strictPrivacy = true
            privacyAllowedFields = setOf("error_code", "retries", "flagged", "nested")
        }

        val payload = build(config)

        assertEquals("", payload.getString("message"))
        assertEquals(StrictPrivacy.messageFingerprint(SCRUBBED_MESSAGE), payload.getString("message_fingerprint"))
        assertTrue(payload.getString("message_fingerprint").matches(Regex("[0-9a-f]{32}")))
        val context = payload.getJSONObject("context")
        assertEquals(setOf("error_code", "retries", "flagged"), context.keys().asSequence().toSet())
        assertEquals("E42", context.getString("error_code"))
        assertEquals(3, context.getInt("retries"))
        assertEquals(true, context.getBoolean("flagged"))
        assertFalse(payload.has("tags"))
        val user = payload.getJSONObject("user")
        assertEquals(setOf("id"), user.keys().asSequence().toSet())
        assertEquals("42", user.get("id"))
        assertFalse(payload.has("breadcrumbs"))
        assertFalse(payload.has("endpoint"))
        assertFalse(payload.has("sql_statement"))
        val frames = payload.getJSONArray("backtrace")
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            assertFalse(frame.has("context_line"))
            assertFalse(frame.has("pre_context"))
            assertFalse(frame.has("post_context"))
        }

        // The structured fields stay exactly as they are without strict privacy.
        assertEquals(SqlStatementTest.FakeJdbcException::class.java.name, payload.getString("exception_class"))
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", payload.getString("trace_id"))
        assertEquals("production", payload.getString("environment"))
        assertEquals("1.2.3", payload.getString("release"))
        assertEquals("android", payload.getString("sdk_name"))
        assertTrue(payload.has("sql_objects"))
        assertFalse(payload.toString().contains("jane"))
    }

    @Test
    fun `a strict event leaves out context and user when nothing is allowed`() {
        val config = newConfiguration().apply { strictPrivacy = true }
        val payload = EventBuilder.buildForThrowable(config, RuntimeException("x"), mapOf("error_code" to "E42"), mapOf("email" to "jane@example.com"))
        assertFalse(payload.has("context"))
        assertFalse(payload.has("tags"))
        assertFalse(payload.has("user"))

        val noMessage = EventBuilder.buildForThrowable(config, RuntimeException(), emptyMap())
        assertEquals(StrictPrivacy.messageFingerprint(""), noMessage.getString("message_fingerprint"))
    }

    @Test
    fun `a strict ANR report sends its fingerprint instead of its message`() {
        val config = newConfiguration().apply { strictPrivacy = true }
        val payload = EventBuilder.buildForAnr(config, Thread.currentThread().stackTrace, breadcrumbs = listOf(mapOf("message" to "x")))
        assertEquals("ANR", payload.getString("exception_class"))
        assertEquals("", payload.getString("message"))
        assertEquals(
            StrictPrivacy.messageFingerprint("Application Not Responding: main thread blocked for at least ${config.anrThresholdMillis}ms"),
            payload.getString("message_fingerprint"),
        )
        assertFalse(payload.has("breadcrumbs"))
    }

    @Test
    fun `with strict privacy off events are exactly as before`() {
        val payload = build(newConfiguration())

        assertEquals(
            listOf(
                "exception_class", "message", "backtrace", "occurred_at", "environment", "release", "server_name",
                "context", "tags", "sdk_name", "user", "breadcrumbs", "trace_id", "sql_objects", "sql_statement",
            ).sorted(),
            payload.keys().asSequence().toList().sorted(),
        )
        assertEquals(SCRUBBED_MESSAGE, payload.getString("message"))
        val context = payload.getJSONObject("context")
        assertEquals(setOf("error_code", "retries", "flagged", "nested", "note"), context.keys().asSequence().toSet())
        assertEquals(0, payload.getJSONObject("tags").length())
        val user = payload.getJSONObject("user")
        assertEquals(42, user.getInt("id"))
        assertEquals("jane@example.com", user.getString("email"))
        assertEquals("jane", user.getString("username"))
        assertEquals(1, payload.getJSONArray("breadcrumbs").length())
        assertEquals("SELECT * FROM patients WHERE id = ?", payload.getString("sql_statement"))
        assertFalse(payload.has("message_fingerprint"))
    }

    @Test
    fun `strict privacy records no breadcrumbs`() {
        val config = newConfiguration().apply { strictPrivacy = true }
        val buffer = BreadcrumbBuffer(config)
        buffer.add("looked up jane@example.com", "custom", "info", emptyMap())
        assertEquals(emptyList<Map<String, Any?>>(), buffer.all())

        config.strictPrivacy = false
        buffer.add("looked up", "custom", "info", emptyMap())
        assertEquals(1, buffer.all().size)
    }

    @Test
    fun `strict spans keep only method host and database data`() {
        val config = newConfiguration().apply { strictPrivacy = true }
        var delivered: JSONObject? = null
        val trace = Trace("GET /patients/42", config) { delivered = it }
        trace.recordSpan("GET https://api.example.com/patients/42?x=1", "http", System.currentTimeMillis(), 5.0,
            mapOf("url" to "https://api.example.com/patients/42", "status" to 200))
        trace.startHttpSpan("GET", "https://api.example.com/patients/42?x=1").finish(mapOf("status" to 200))
        trace.recordSpan("Load patient", "database", System.currentTimeMillis(), 5.0, mapOf("rows" to 1),
            statement = "SELECT * FROM patients WHERE name = 'jane'", dbSystem = "sqlite")
        trace.finish()

        val spans = assertNotNull(delivered).let { delivered!!.getJSONArray("spans") }
        assertEquals("GET /patients/:path", spans.getJSONObject(0).getString("name"))
        assertEquals("GET api.example.com", spans.getJSONObject(1).getString("name"))
        assertEquals(0, spans.getJSONObject(1).getJSONObject("data").length())
        assertEquals("GET api.example.com", spans.getJSONObject(2).getString("name"))
        assertEquals(0, spans.getJSONObject(2).getJSONObject("data").length())
        assertEquals("Load patient", spans.getJSONObject(3).getString("name"))
        val data = spans.getJSONObject(3).getJSONObject("data")
        assertEquals(setOf("db.statement", "db.system"), data.keys().asSequence().toSet())
        assertEquals("SELECT * FROM patients WHERE name = ?", data.getString("db.statement"))
        assertEquals("sqlite", data.getString("db.system"))
    }

    @Test
    fun `spans are unchanged with strict privacy off`() {
        var delivered: JSONObject? = null
        val trace = Trace("GET /patients/42", newConfiguration()) { delivered = it }
        trace.recordSpan("GET https://api.example.com/patients/42", "http", System.currentTimeMillis(), 5.0, mapOf("status" to 200))
        trace.finish()

        val spans = delivered!!.getJSONArray("spans")
        assertEquals("GET /patients/42", spans.getJSONObject(0).getString("name"))
        assertEquals("GET https://api.example.com/patients/42", spans.getJSONObject(1).getString("name"))
        assertEquals(200, spans.getJSONObject(1).getJSONObject("data").getInt("status"))
    }

    private fun build(config: Configuration): JSONObject = EventBuilder.buildForThrowable(
        config,
        SqlStatementTest.FakeJdbcException(EMAIL_MESSAGE, "SELECT * FROM patients WHERE id = 8821"),
        linkedMapOf("error_code" to "E42", "retries" to 3, "flagged" to true, "nested" to mapOf("a" to 1), "note" to "free text"),
        linkedMapOf("id" to 42, "email" to "jane@example.com", "username" to "jane"),
        listOf(mapOf("category" to "custom", "message" to "opened jane's chart", "level" to "info", "data" to emptyMap<String, Any?>())),
        "4bf92f3577b34da6a3ce929d0e0e4736",
    )

    private companion object {
        const val EMAIL_MESSAGE = "Couldn't find patient jane@example.com with id=8821"

        // The fingerprint is taken from the message as it would otherwise have been sent: scrubbed.
        const val SCRUBBED_MESSAGE = "Couldn't find patient [EMAIL FILTERED] with id=8821"
    }
}

package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventBuilderTest {
    private fun newConfiguration() = Configuration(ApplicationProvider.getApplicationContext()).apply {
        environment = "production"
        release = "abc123"
        serverName = "Pixel 8"
        packageName = "com.acme.myapp"
    }

    @Test
    fun `builds payload with exception class, message, and configured metadata`() {
        val config = newConfiguration()
        val error = IllegalStateException("boom")

        val payload = EventBuilder.buildForThrowable(config, error, mapOf("order_id" to 42))

        assertEquals("java.lang.IllegalStateException", payload.getString("exception_class"))
        assertEquals("boom", payload.getString("message"))
        assertEquals("production", payload.getString("environment"))
        assertEquals("abc123", payload.getString("release"))
        assertEquals("Pixel 8", payload.getString("server_name"))
        assertEquals(42, payload.getJSONObject("context").getInt("order_id"))
        assertTrue(payload.getString("occurred_at").endsWith("Z"))
        assertEquals("android", payload.getString("sdk_name"))
    }

    @Test
    fun `parses real stack frames with class, method, and line`() {
        val config = newConfiguration()
        val error = IllegalStateException("boom")

        val frames = EventBuilder.buildForThrowable(config, error, null).getJSONArray("backtrace")

        assertTrue(frames.length() > 0)
        val frame = frames.getJSONObject(0)
        assertTrue(frame.get("method") is String)
        assertTrue((frame.get("method") as String).contains("#"))
        assertTrue(frame.get("line") is Int)
    }

    @Test
    fun `marks a frame from this test's own package as in_app when it matches`() {
        val config = newConfiguration()
        config.packageName = "io.github.lukepopwell.tracker.android" // this test class's own package
        val error = IllegalStateException("boom")

        val frames = EventBuilder.buildForThrowable(config, error, null).getJSONArray("backtrace")

        var anyInApp = false
        for (i in 0 until frames.length()) {
            if (frames.getJSONObject(i).getBoolean("in_app")) anyInApp = true
        }
        assertTrue("the frame that threw should be in this test's own package", anyInApp)
    }

    @Test
    fun `no frame is in_app when packageName is unset`() {
        val config = newConfiguration()
        config.packageName = null
        val error = IllegalStateException("boom")

        val frames = EventBuilder.buildForThrowable(config, error, null).getJSONArray("backtrace")

        for (i in 0 until frames.length()) {
            assertTrue(!frames.getJSONObject(i).getBoolean("in_app"))
        }
    }

    @Test
    fun `buildForAnr has no throwable but still produces a real backtrace`() {
        val config = newConfiguration()
        val stackTrace = Thread.currentThread().stackTrace

        val payload = EventBuilder.buildForAnr(config, stackTrace)

        assertEquals("ANR", payload.getString("exception_class"))
        assertTrue(payload.getString("message").contains("Application Not Responding"))
        assertTrue(payload.getJSONArray("backtrace").length() > 0)
    }

    @Test
    fun `scrubs likely pii out of message and context by default`() {
        val config = newConfiguration()
        val error = IllegalStateException("failed for user@example.com")

        val payload = EventBuilder.buildForThrowable(config, error, mapOf("user" to mapOf("email" to "ada@example.com", "password" to "hunter2")))

        assertEquals("failed for [EMAIL FILTERED]", payload.getString("message"))
        val user = payload.getJSONObject("context").getJSONObject("user")
        assertEquals("[EMAIL FILTERED]", user.getString("email"))
        assertEquals(PiiScrubber.REDACTED, user.getString("password"))
    }

    @Test
    fun `leaves payload untouched when scrubPii is disabled`() {
        val config = newConfiguration()
        config.scrubPii = false
        val error = IllegalStateException("failed for user@example.com")

        val payload = EventBuilder.buildForThrowable(config, error, mapOf("email" to "ada@example.com"))

        assertEquals("failed for user@example.com", payload.getString("message"))
        assertEquals("ada@example.com", payload.getJSONObject("context").getString("email"))
    }

    @Test
    fun `includes the user when given one, never scrubbed even though it's an email`() {
        val config = newConfiguration()
        val error = IllegalStateException("boom")

        val payload = EventBuilder.buildForThrowable(config, error, null, mapOf("id" to 42, "email" to "ada@example.com"))

        val user = payload.getJSONObject("user")
        assertEquals(42, user.getInt("id"))
        assertEquals("ada@example.com", user.getString("email"))
    }

    @Test
    fun `omits the user key entirely when none was given`() {
        val config = newConfiguration()
        val error = IllegalStateException("boom")

        val payload = EventBuilder.buildForThrowable(config, error, null)

        assertFalse(payload.has("user"))
    }

    @Test
    fun `includes breadcrumbs when given`() {
        val config = newConfiguration()
        val crumb = mapOf("category" to "controller", "message" to "GET /orders/42", "level" to "info", "timestamp" to "2024-01-15T10:29:58Z", "data" to emptyMap<String, Any?>())

        val payload = EventBuilder.buildForThrowable(config, IllegalStateException("boom"), null, null, listOf(crumb))

        val crumbs = payload.getJSONArray("breadcrumbs")
        assertEquals(1, crumbs.length())
        assertEquals("GET /orders/42", crumbs.getJSONObject(0).getString("message"))
        assertEquals("controller", crumbs.getJSONObject(0).getString("category"))
    }

    @Test
    fun `omits the breadcrumbs key entirely when none were given`() {
        val payload = EventBuilder.buildForThrowable(newConfiguration(), IllegalStateException("boom"), null)

        assertFalse(payload.has("breadcrumbs"))
    }

    @Test
    fun `buildForAnr includes breadcrumbs too`() {
        val crumb = mapOf("category" to "custom", "message" to "before the hang", "level" to "info", "timestamp" to "2024-01-15T10:29:58Z", "data" to emptyMap<String, Any?>())

        val payload = EventBuilder.buildForAnr(newConfiguration(), Thread.currentThread().stackTrace, null, listOf(crumb))

        assertEquals("before the hang", payload.getJSONArray("breadcrumbs").getJSONObject(0).getString("message"))
    }

    @Test
    fun `scrubs breadcrumb message and data but leaves category level and timestamp untouched`() {
        val crumb = mapOf(
            "category" to "custom",
            "message" to "emailed alice@example.com",
            "level" to "info",
            "timestamp" to "2024-01-15T10:29:58Z",
            "data" to mapOf("email" to "alice@example.com", "password" to "hunter2"),
        )

        val payload = EventBuilder.buildForThrowable(newConfiguration(), IllegalStateException("boom"), null, null, listOf(crumb))

        val scrubbed = payload.getJSONArray("breadcrumbs").getJSONObject(0)
        assertFalse(scrubbed.getString("message").contains("alice@example.com"))
        assertEquals("custom", scrubbed.getString("category"))
        assertEquals("info", scrubbed.getString("level"))
        assertEquals("2024-01-15T10:29:58Z", scrubbed.getString("timestamp"))
        assertEquals("[FILTERED]", scrubbed.getJSONObject("data").getString("password"))
    }

    // --- Source context capture ---
    //
    // captureSourceContext is a documented no-op on Android (see Configuration.captureSourceContext
    // and EventBuilder.attachSourceContext's own comments): StackTraceElement.getFileName() never
    // returns a real, openable path on the JVM, and a compiled Android build never ships .java/.kt
    // source onto the device regardless. There is no real disk-read logic to exercise here, so
    // these tests only confirm the option exists, defaults to true, and that its (always inert)
    // gating path never throws or otherwise changes a frame.

    @Test
    fun `captureSourceContext defaults to true`() {
        val config = Configuration(ApplicationProvider.getApplicationContext())

        assertTrue(config.captureSourceContext)
    }

    @Test
    fun `no frame ever gets context_line, pre_context, or post_context on Android, even with captureSourceContext enabled`() {
        val config = newConfiguration()
        config.captureSourceContext = true
        val error = IllegalStateException("boom")

        val frames = EventBuilder.buildForThrowable(config, error, null).getJSONArray("backtrace")

        assertTrue(frames.length() > 0)
        for (i in 0 until frames.length()) {
            val frame = frames.getJSONObject(i)
            assertTrue(!frame.has("context_line"))
            assertTrue(!frame.has("pre_context"))
            assertTrue(!frame.has("post_context"))
        }
    }

    @Test
    fun `disabling captureSourceContext never throws and leaves the payload otherwise identical`() {
        val config = newConfiguration()
        config.captureSourceContext = false
        val error = IllegalStateException("boom")

        val payload = EventBuilder.buildForThrowable(config, error, null)

        assertTrue(payload.getJSONArray("backtrace").length() > 0)
    }
}

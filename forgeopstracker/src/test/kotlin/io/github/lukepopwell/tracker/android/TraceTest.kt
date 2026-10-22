package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TraceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        ForgeOpsTracker.resetForTesting()
        server = MockWebServer()
        server.start()
    }

    @After
    fun cleanup() {
        server.shutdown()
        ForgeOpsTracker.resetForTesting()
    }

    private fun configure(tracing: Boolean = true, thresholdMillis: Long = 10, environment: String = "production") {
        ForgeOpsTracker.init(ApplicationProvider.getApplicationContext()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/api/v1/events"
            config.environment = environment
            config.release = "a1b2c3d"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
            config.trackTracing = tracing
            config.traceCaptureThresholdMillis = thresholdMillis
        }
    }

    private fun deliveredTrace(): JSONObject {
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("trace was never delivered", request)
        assertEquals("/api/v1/spans", request!!.path)
        return JSONObject(request.body.readUtf8())
    }

    private fun span(trace: JSONObject, name: String): JSONObject {
        val spans = trace.getJSONArray("spans")
        for (i in 0 until spans.length()) {
            if (spans.getJSONObject(i).getString("name") == name) return spans.getJSONObject(i)
        }
        throw AssertionError("no span named $name")
    }

    @Test
    fun `a slow trace is delivered to spans with nested spans and the wire shape`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        ForgeOpsTracker.trace("HomeScreen.load") { trace ->
            trace.measureSpan("fetch feed", kind = "http", data = mapOf("status" to 200)) {
                trace.recordSpan("SELECT feed", kind = "database", startedAtMillis = 1_700_000_000_123L, durationMs = 3.0)
                Thread.sleep(30)
            }
            trace.recordSpan("sibling", startedAtMillis = System.currentTimeMillis(), durationMs = 1.0)
        }
        ForgeOpsTracker.flushSpans()

        val trace = deliveredTrace()
        assertTrue(trace.getString("trace_id").matches(Regex("[0-9a-f]{32}")))
        val root = span(trace, "HomeScreen.load")
        val fetch = span(trace, "fetch feed")
        assertTrue(root.isNull("parent_span_id"))
        assertEquals("controller", root.getString("kind"))
        assertEquals(16, root.getString("span_id").length)
        assertEquals(root.getString("span_id"), fetch.getString("parent_span_id"))
        assertEquals(fetch.getString("span_id"), span(trace, "SELECT feed").getString("parent_span_id"))
        assertEquals(root.getString("span_id"), span(trace, "sibling").getString("parent_span_id"))
        assertEquals("2023-11-14T22:13:20.123Z", span(trace, "SELECT feed").getString("started_at"))
        assertEquals("production", fetch.getString("environment"))
        assertEquals("a1b2c3d", fetch.getString("release"))
        assertEquals(200, fetch.getJSONObject("data").getInt("status"))
    }

    @Test
    fun `an unknown kind is sent as other since the server would reject the whole trace`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        ForgeOpsTracker.trace("t") { trace ->
            trace.recordSpan("q", kind = "db", startedAtMillis = 0L, durationMs = 1.0)
            trace.recordSpan("r", kind = "database", startedAtMillis = 0L, durationMs = 1.0)
            Thread.sleep(30)
        }
        ForgeOpsTracker.flushSpans()

        val trace = deliveredTrace()
        assertEquals("other", span(trace, "q").getString("kind"))
        assertEquals("database", span(trace, "r").getString("kind"))
    }

    @Test
    fun `a block that throws still records its span sends the trace and rethrows unchanged`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()
        val failure = IllegalStateException("boom")

        val thrown = assertThrows(IllegalStateException::class.java) {
            ForgeOpsTracker.trace("t") { trace ->
                trace.measureSpan("bad") {
                    Thread.sleep(30)
                    throw failure
                }
            }
        }
        ForgeOpsTracker.flushSpans()

        assertEquals(failure, thrown)
        span(deliveredTrace(), "bad")
    }

    @Test
    fun `a fast trace sends nothing`() {
        configure(thresholdMillis = 60_000)

        ForgeOpsTracker.trace("fast") { trace -> trace.recordSpan("q", kind = "database", startedAtMillis = 0L, durationMs = 1.0) }
        ForgeOpsTracker.flushSpans()

        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `reporting disabled starts no trace and the nullable forms still run the block`() {
        configure(environment = "development")
        assertNull(ForgeOpsTracker.startTrace("x"))
        var ran = false
        ForgeOpsTracker.trace("x") { trace ->
            assertNull(trace)
            ran = trace.measureSpan("y") {
                Thread.sleep(30)
                true
            }
            trace.recordSpan("z", startedAtMillis = 0L, durationMs = 1.0)
            assertEquals(emptyMap<String, String>(), trace.measureHttpSpan("GET", "https://api.example.com/") { it })
            val http = trace.startHttpSpan("GET", "https://api.example.com/")
            assertNull(http.traceparent)
            http.finish()
            trace.finish()
        }
        assertTrue(ran)
        ForgeOpsTracker.flushSpans()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `track tracing off keeps a trace id for errors and headers but records and sends nothing`() {
        configure(tracing = false)
        ForgeOpsTracker.trace("x") { trace ->
            assertNotNull(trace)
            trace.measureSpan("y") { Thread.sleep(30) }
            val headers = trace.measureHttpSpan("GET", "https://api.example.com/") { it }
            assertTrue(headers.getValue("traceparent").startsWith("00-${trace!!.traceId}-"))
        }
        ForgeOpsTracker.flushSpans()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `startTrace before init returns null instead of throwing`() {
        assertNull(ForgeOpsTracker.startTrace("x"))
        ForgeOpsTracker.flushSpans()
    }

    @Test
    fun `a span recorded from another thread parents under the root not the open span on this one`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        ForgeOpsTracker.trace("t") { trace ->
            trace.measureSpan("outer") {
                val done = CountDownLatch(1)
                Thread {
                    trace.recordSpan("background", kind = "job", startedAtMillis = System.currentTimeMillis(), durationMs = 1.0)
                    done.countDown()
                }.start()
                done.await()
                Thread.sleep(30)
            }
        }
        ForgeOpsTracker.flushSpans()

        val trace = deliveredTrace()
        assertEquals(span(trace, "t").getString("span_id"), span(trace, "background").getString("parent_span_id"))
    }

    @Test
    fun `finish is idempotent and spans after it are dropped`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        val trace = ForgeOpsTracker.startTrace("t")
        Thread.sleep(30)
        trace.finish()
        trace.finish()
        trace.recordSpan("late", startedAtMillis = 0L, durationMs = 1.0)
        trace.finish()
        ForgeOpsTracker.flushSpans()

        val delivered = deliveredTrace()
        assertEquals(1, delivered.getJSONArray("spans").length())
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a trace holds at most 500 spans including the root`() {
        val config = Configuration(ApplicationProvider.getApplicationContext()).apply {
            dsn = "http://key@127.0.0.1:1/api/v1/events"
            traceCaptureThresholdMillis = 0
        }
        var delivered: JSONObject? = null
        val trace = Trace("root", config) { delivered = it }
        repeat(700) { trace.recordSpan("q", kind = "database", startedAtMillis = 0L, durationMs = 1.0) }
        trace.finish()

        assertEquals(500, delivered!!.getJSONArray("spans").length())
    }

    @Test
    fun `spansUri swaps the trailing events segment`() {
        val config = Configuration(ApplicationProvider.getApplicationContext()).apply { dsn = "https://key@tracker.example.com/api/v1/events" }
        assertEquals("https://tracker.example.com/api/v1/spans", config.spansUri.toString())
        assertFalse(config.spansUri == null)
    }

    @Test
    fun `a database span sends its statement masked as db statement, with db system`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        ForgeOpsTracker.trace("t") { trace ->
            trace.measureSpan(
                "Load orders",
                kind = "database",
                data = mapOf("rows" to 3),
                statement = "SELECT * FROM orders WHERE email = 'a@b.co' AND total > 4200",
                dbSystem = "SQLite",
            ) { Thread.sleep(30) }
            trace.recordSpan(
                "Count",
                kind = "database",
                startedAtMillis = 0L,
                durationMs = 1.0,
                data = mapOf("db.statement" to "SELECT count(*) FROM carts WHERE token = 'secret-token'"),
            )
            trace.recordSpan("Not db", kind = "service", startedAtMillis = 0L, durationMs = 1.0, statement = "SELECT 'x'", dbSystem = "sqlite")
        }
        ForgeOpsTracker.flushSpans()

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("trace was never delivered", request)
        val wire = request!!.body.readUtf8()
        val trace = JSONObject(wire)
        val load = span(trace, "Load orders").getJSONObject("data")
        assertEquals("SELECT * FROM orders WHERE email = ? AND total > ?", load.getString("db.statement"))
        assertEquals("sqlite", load.getString("db.system"))
        assertEquals(3, load.getInt("rows"))
        val count = span(trace, "Count").getJSONObject("data")
        assertEquals("SELECT count(*) FROM carts WHERE token = ?", count.getString("db.statement"))
        assertFalse(count.has("db.system"))
        assertEquals(0, span(trace, "Not db").getJSONObject("data").length())
        assertFalse(wire.contains("a@b.co"))
        assertFalse(wire.contains("secret-token"))
    }

    @Test
    fun `a database statement is cut at 4000 characters`() {
        val sql = "SELECT " + "a, ".repeat(3000) + "b FROM t"
        val statement = Trace.spanData("database", mapOf("db.statement" to sql))!!["db.statement"] as String
        assertEquals(4003, statement.length)
        assertTrue(statement.endsWith("..."))
    }

    @Test
    fun `double quoted strings are masked only for mysql and mariadb`() {
        val sql = "SELECT id FROM users WHERE token = \"abc123secret\""
        fun masked(system: String) = Trace.spanData("database", mapOf("db.statement" to sql, "db.system" to system))!!["db.statement"]
        assertEquals("SELECT id FROM users WHERE token = ?", masked("MySQL"))
        assertEquals("SELECT id FROM users WHERE token = ?", masked("mariadb"))
        assertEquals(sql, masked("sqlite"))
    }

    @Test
    fun `the null-safe forms forward the statement`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        val trace: Trace? = ForgeOpsTracker.startTrace("t")
        trace.measureSpan("q", kind = "database", statement = "SELECT 1 FROM t WHERE id = 7", dbSystem = "sqlite") { Thread.sleep(30) }
        trace.finish()
        ForgeOpsTracker.flushSpans()

        assertEquals("SELECT ? FROM t WHERE id = ?", span(deliveredTrace(), "q").getJSONObject("data").getString("db.statement"))
    }
}

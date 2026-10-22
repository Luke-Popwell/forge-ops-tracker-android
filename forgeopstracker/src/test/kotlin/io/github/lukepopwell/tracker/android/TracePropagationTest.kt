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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TracePropagationTest {
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

    private fun configure(thresholdMillis: Long = 10, configure: (Configuration) -> Unit = {}) {
        ForgeOpsTracker.init(ApplicationProvider.getApplicationContext()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/api/v1/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
            config.traceCaptureThresholdMillis = thresholdMillis
            configure(config)
        }
    }

    private fun delivered(path: String): JSONObject {
        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("nothing was delivered", request)
        assertEquals(path, request!!.path)
        return JSONObject(request.body.readUtf8())
    }

    private fun span(trace: JSONObject, name: String): JSONObject {
        val spans = trace.getJSONArray("spans")
        for (i in 0 until spans.length()) {
            if (spans.getJSONObject(i).getString("name") == name) return spans.getJSONObject(i)
        }
        throw AssertionError("no span named $name in $trace")
    }

    private fun parentIdOf(traceparent: String?): String {
        val match = Regex("00-([0-9a-f]{32})-([0-9a-f]{16})-01").matchEntire(traceparent ?: "")
        assertNotNull("not a W3C traceparent: $traceparent", match)
        return match!!.groupValues[2]
    }

    // Ids and the header format

    @Test
    fun `trace and span ids are W3C shaped and the header is version 00 sampled`() {
        repeat(50) {
            assertTrue(TraceParent.generateTraceId().matches(Regex("[0-9a-f]{32}")))
            assertTrue(TraceParent.generateSpanId().matches(Regex("[0-9a-f]{16}")))
        }
        assertEquals(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            TraceParent.build("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7"),
        )
    }

    // Outgoing requests

    @Test
    fun `measureHttpSpan hands the block a traceparent naming the http span it records`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        var header: String? = null
        var traceId: String? = null
        val result = ForgeOpsTracker.trace("Checkout.pay") { trace ->
            traceId = trace!!.traceId
            trace.measureHttpSpan("post", "https://api.example.com/orders/42?token=secret", data = mapOf("attempt" to 1)) { headers ->
                header = headers[TraceParent.HEADER]
                Thread.sleep(30)
                "paid"
            }
        }
        ForgeOpsTracker.flushSpans()
        assertEquals("paid", result)

        val trace = delivered("/api/v1/spans")
        assertEquals(traceId, trace.getString("trace_id"))
        assertTrue(header!!.startsWith("00-$traceId-"))
        val http = span(trace, "POST api.example.com")
        assertEquals("http", http.getString("kind"))
        assertEquals(http.getString("span_id"), parentIdOf(header))
        assertEquals(span(trace, "Checkout.pay").getString("span_id"), http.getString("parent_span_id"))
        assertEquals(1, http.getJSONObject("data").getInt("attempt"))
        assertFalse(trace.toString().contains("secret"))
    }

    @Test
    fun `measureHttpSpan records the span and rethrows unchanged when the request fails`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()
        val failure = IOException("connection reset")

        val thrown = assertThrows(IOException::class.java) {
            ForgeOpsTracker.trace("Checkout.pay") { trace ->
                trace.measureHttpSpan("GET", "https://api.example.com/cart") {
                    Thread.sleep(30)
                    throw failure
                }
            }
        }
        ForgeOpsTracker.flushSpans()

        assertEquals(failure, thrown)
        span(delivered("/api/v1/spans"), "GET api.example.com")
    }

    @Test
    fun `startHttpSpan finished from a callback thread records under the span open when it started`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure()

        var header: String? = null
        ForgeOpsTracker.trace("Checkout.pay") { trace ->
            trace.measureSpan("submit") {
                val http = trace.startHttpSpan("POST", "https://api.example.com/orders")
                header = http.traceparent
                assertEquals(mapOf("traceparent" to http.traceparent), http.headers)
                val done = CountDownLatch(1)
                Thread {
                    Thread.sleep(20)
                    http.finish(mapOf("status" to 201))
                    http.finish(mapOf("status" to 500)) // only the first finish counts
                    done.countDown()
                }.start()
                done.await()
                Thread.sleep(10)
            }
        }
        ForgeOpsTracker.flushSpans()

        val trace = delivered("/api/v1/spans")
        val spans = trace.getJSONArray("spans")
        val httpSpans = (0 until spans.length()).map { spans.getJSONObject(it) }.filter { it.getString("kind") == "http" }
        assertEquals(1, httpSpans.size)
        val http = httpSpans.single()
        assertEquals(http.getString("span_id"), parentIdOf(header))
        assertEquals(span(trace, "submit").getString("span_id"), http.getString("parent_span_id"))
        assertEquals(201, http.getJSONObject("data").getInt("status"))
    }

    @Test
    fun `propagateTraces off still records the span but produces no header`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure { it.propagateTraces = false }

        ForgeOpsTracker.trace("t") { trace ->
            assertEquals(emptyMap<String, String>(), trace.measureHttpSpan("GET", "https://api.example.com/") { Thread.sleep(30); it })
            assertNull(trace.startHttpSpan("GET", "https://api.example.com/").traceparent)
        }
        ForgeOpsTracker.flushSpans()

        span(delivered("/api/v1/spans"), "GET api.example.com")
    }

    @Test
    fun `tracePropagationTargets limits which hosts get the header`() {
        configure(thresholdMillis = 60_000) { it.tracePropagationTargets = listOf("example.com") }

        ForgeOpsTracker.trace("t") { trace ->
            assertNotNull(trace.startHttpSpan("GET", "https://api.example.com/").traceparent)
            assertNull(trace.startHttpSpan("GET", "https://badexample.com/").traceparent)
            assertNull(trace.startHttpSpan("GET", "https://cdn.thirdparty.example/").traceparent)
            assertNull(trace.startHttpSpan("GET", "::not a url::").traceparent)
        }
    }

    // Errors

    @Test
    fun `an error captured while a trace is open carries its trace id`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure(thresholdMillis = 60_000)

        var traceId: String? = null
        ForgeOpsTracker.trace("Checkout.pay") { trace ->
            traceId = trace!!.traceId
            assertEquals(traceId, ForgeOpsTracker.currentTraceId())
            ForgeOpsTracker.captureException(IllegalStateException("payment failed"))
        }

        assertEquals(traceId, delivered("/api/v1/events").getString("trace_id"))
    }

    @Test
    fun `an error captured with no open trace has no trace_id at all`() {
        server.enqueue(MockResponse().setResponseCode(202))
        configure(thresholdMillis = 60_000)

        ForgeOpsTracker.trace("finished already") { }
        assertNull(ForgeOpsTracker.currentTraceId())
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        assertFalse(delivered("/api/v1/events").has("trace_id"))
    }

    @Test
    fun `an explicit trace argument wins, and null leaves it off`() {
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(202))
        configure(thresholdMillis = 60_000)

        val first = ForgeOpsTracker.startTrace("first")!!
        val second = ForgeOpsTracker.startTrace("second")!!
        assertEquals(second.traceId, ForgeOpsTracker.currentTraceId())

        ForgeOpsTracker.captureException(IllegalStateException("boom"), trace = first)
        assertEquals(first.traceId, delivered("/api/v1/events").getString("trace_id"))

        ForgeOpsTracker.captureException(IllegalStateException("boom"), trace = null)
        assertFalse(delivered("/api/v1/events").has("trace_id"))

        second.finish()
        assertEquals(first.traceId, ForgeOpsTracker.currentTraceId())
        first.finish()
        assertNull(ForgeOpsTracker.currentTraceId())
    }

    @Test
    fun `a trace that is never finished is eventually forgotten`() {
        configure(thresholdMillis = 60_000)
        ForgeOpsTracker.startTrace("abandoned")!!
        val later = (1..40).map { ForgeOpsTracker.startTrace("flow $it")!! }

        later.forEach { it.finish() }

        assertNull(ForgeOpsTracker.currentTraceId())
    }

    @Test
    fun `crash and ANR reports carry the trace id they are given`() {
        val config = Configuration(ApplicationProvider.getApplicationContext()).apply { environment = "production" }
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        assertEquals(traceId, EventBuilder.buildForThrowable(config, IllegalStateException("x"), null, traceId = traceId).getString("trace_id"))
        assertEquals(traceId, EventBuilder.buildForAnr(config, Thread.currentThread().stackTrace, traceId = traceId).getString("trace_id"))
        assertFalse(EventBuilder.buildForThrowable(config, IllegalStateException("x"), null).has("trace_id"))
        assertFalse(EventBuilder.buildForAnr(config, Thread.currentThread().stackTrace).has("trace_id"))
    }
}

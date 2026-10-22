package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MetricBufferTest {
    private lateinit var server: MockWebServer
    private val buffers = ArrayList<MetricBuffer>()

    @Before
    fun setUp() {
        ForgeOpsTracker.resetForTesting()
        server = MockWebServer()
        server.start()
    }

    @After
    fun cleanup() {
        buffers.forEach { it.discard() }
        server.shutdown()
        ForgeOpsTracker.resetForTesting()
    }

    private fun configuration() = Configuration(ApplicationProvider.getApplicationContext()).apply {
        dsn = "http://key@${server.hostName}:${server.port}/api/v1/events"
        environment = "production"
        release = "a1b2c3d"
        serverName = "web-1"
    }

    private fun buffer(deliver: (JSONArray) -> Boolean): MetricBuffer =
        MetricBuffer(configuration(), deliver) { 3_600_000L }.also { buffers.add(it) }

    private fun entry(name: String, value: Double) = JSONObject().put("metric_name", name).put("value", value)

    private fun names(entries: JSONArray) = (0 until entries.length()).map { entries.getJSONObject(it).getString("metric_name") }

    @Test
    fun `delivers every entry as one batch stamped with recorded_at`() {
        val delivered = CopyOnWriteArrayList<JSONArray>()
        val buffer = buffer { delivered.add(it); true }

        assertTrue(buffer.record(entry("signup", 1.0), 1.0))
        assertTrue(buffer.record(entry("refund", -12.5), -12.5))
        buffer.flush()

        assertEquals(1, delivered.size)
        assertEquals(listOf("signup", "refund"), names(delivered[0]))
        assertEquals(-12.5, delivered[0].getJSONObject(1).getDouble("value"), 0.0)
        assertTrue(delivered[0].getJSONObject(0).getString("recorded_at").matches(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ")))
        assertEquals(0, buffer.size())
    }

    @Test
    fun `drops NaN and infinite values`() {
        val buffer = buffer { true }
        assertFalse(buffer.record(entry("nan", 0.0), Double.NaN))
        assertFalse(buffer.record(entry("inf", 0.0), Double.POSITIVE_INFINITY))
        assertTrue(buffer.record(entry("ok", 3.0), 3.0))
    }

    @Test
    fun `a failed delivery keeps every entry for the next flush`() {
        var calls = 0
        val delivered = CopyOnWriteArrayList<List<String>>()
        val buffer = buffer { entries -> delivered.add(names(entries)); ++calls > 1 }

        buffer.record(entry("a", 1.0), 1.0)
        buffer.flush()
        buffer.record(entry("b", 2.0), 2.0)
        buffer.flush()
        buffer.flush() // nothing left: no third delivery

        assertEquals(listOf(listOf("a"), listOf("a", "b")), delivered.toList())
    }

    private fun statusBuffer(deliverStatus: (JSONArray) -> Int): MetricBuffer =
        MetricBuffer(configuration(), { 3_600_000L }, deliverStatus).also { buffers.add(it) }

    @Test
    fun `a rejected batch is dropped and never sent again`() {
        val delivered = CopyOnWriteArrayList<List<String>>()
        val buffer = statusBuffer { entries -> delivered.add(names(entries)); 403 }

        buffer.record(entry("a", 1.0), 1.0)
        buffer.flush()
        assertEquals(0, buffer.size())
        buffer.flush() // nothing left: no second delivery
        buffer.record(entry("b", 2.0), 2.0)
        buffer.flush()

        assertEquals(listOf(listOf("a"), listOf("b")), delivered.toList())
    }

    @Test
    fun `a 5xx, a 408, a 429 or no response keeps every entry`() {
        for (status in listOf(500, 503, 408, 429, 0)) {
            val buffer = statusBuffer { status }
            buffer.record(entry("a", 1.0), 1.0)
            buffer.flush()
            assertEquals("status $status", 1, buffer.size())
        }
    }

    @Test
    fun `the fifth failed flush in a row drops the batch`() {
        var calls = 0
        val buffer = statusBuffer { calls += 1; 503 }

        buffer.record(entry("a", 1.0), 1.0)
        repeat(4) { buffer.flush() }
        assertEquals(1, buffer.size())

        buffer.flush()
        assertEquals(0, buffer.size())
        assertEquals(5, calls)

        // The count starts again with the next batch.
        buffer.record(entry("b", 2.0), 2.0)
        buffer.flush()
        assertEquals(1, buffer.size())
    }

    @Test
    fun `a deliver that only says false is bounded the same way`() {
        val buffer = buffer { false }

        buffer.record(entry("a", 1.0), 1.0)
        repeat(4) { buffer.flush() }
        assertEquals(1, buffer.size())
        buffer.flush()
        assertEquals(0, buffer.size())
    }

    @Test
    fun `captureMetric drops a batch the server rejects and keeps one it could not take yet`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(ApplicationProvider.getApplicationContext()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/api/v1/events"
            config.environment = "production"
        }

        ForgeOpsTracker.captureMetric("signup")
        ForgeOpsTracker.flushMetricsSync() // 401: dropped
        ForgeOpsTracker.flushMetricsSync() // nothing left to send
        assertEquals(1, server.requestCount)

        ForgeOpsTracker.captureMetric("payment")
        ForgeOpsTracker.flushMetricsSync() // 503: kept
        ForgeOpsTracker.flushMetricsSync() // and sent again
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an entry recorded while delivery is in flight is never lost`() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CopyOnWriteArrayList<List<String>>()
        val buffer = buffer { entries ->
            delivered.add(names(entries))
            if (delivered.size == 1) {
                inFlight.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            true
        }

        buffer.record(entry("first", 1.0), 1.0)
        val flushing = Thread { buffer.flush() }.also { it.start() }
        assertTrue(inFlight.await(5, TimeUnit.SECONDS))
        buffer.record(entry("during", 2.0), 2.0)
        release.countDown()
        flushing.join(5000)
        buffer.flush()

        assertEquals(listOf(listOf("first"), listOf("during")), delivered.toList())
    }

    @Test
    fun `is capped and drops further entries until a flush succeeds`() {
        val buffer = buffer { false }
        val accepted = (1..MetricBuffer.MAX_ENTRIES + 50).count { buffer.record(entry("m", 1.0), 1.0) }
        assertEquals(MetricBuffer.MAX_ENTRIES, accepted)
    }

    @Test
    fun `captureMetric and captureInfrastructureMetric deliver to their own endpoints through the full stack`() {
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(ApplicationProvider.getApplicationContext()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/api/v1/events"
            config.environment = "production"
            config.release = "a1b2c3d"
            config.serverName = "web-1"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }

        ForgeOpsTracker.captureMetric("signup")
        ForgeOpsTracker.captureMetric("payment", 49.0)
        ForgeOpsTracker.captureInfrastructureMetric("battery", 0.42, "db-1")
        ForgeOpsTracker.captureInfrastructureMetric("memory", 0.7)
        ForgeOpsTracker.flushMetricsSync()

        val requests = listOf(server.takeRequest(2, TimeUnit.SECONDS), server.takeRequest(2, TimeUnit.SECONDS))
        assertNotNull(requests[0])
        assertNotNull(requests[1])
        val byPath = requests.associateBy { it!!.path }
        val custom = JSONObject(byPath["/api/v1/custom_metrics"]!!.body.readUtf8()).getJSONArray("metrics")
        assertEquals("Bearer key", byPath["/api/v1/custom_metrics"]!!.getHeader("Authorization"))
        assertEquals(listOf("signup", "payment"), names(custom))
        assertEquals(1.0, custom.getJSONObject(0).getDouble("value"), 0.0)
        assertEquals(49.0, custom.getJSONObject(1).getDouble("value"), 0.0)
        assertEquals("production", custom.getJSONObject(0).getString("environment"))
        assertEquals("a1b2c3d", custom.getJSONObject(0).getString("release"))
        val infrastructure = JSONObject(byPath["/api/v1/infrastructure_metrics"]!!.body.readUtf8()).getJSONArray("metrics")
        assertEquals("db-1", infrastructure.getJSONObject(0).getString("hostname"))
        assertEquals("web-1", infrastructure.getJSONObject(1).getString("hostname"))
    }

    @Test
    fun `captures are a no-op when reporting is not enabled and before init`() {
        ForgeOpsTracker.captureMetric("before init") // must not throw
        ForgeOpsTracker.init(ApplicationProvider.getApplicationContext()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/api/v1/events"
            config.environment = "development"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }
        ForgeOpsTracker.captureMetric("signup")
        ForgeOpsTracker.captureInfrastructureMetric("battery", 1.0)
        ForgeOpsTracker.flushMetricsSync()

        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `the metric uris swap the trailing events segment`() {
        val config = configuration()
        assertEquals("http://${server.hostName}:${server.port}/api/v1/custom_metrics", config.customMetricsUri.toString())
        assertEquals("http://${server.hostName}:${server.port}/api/v1/infrastructure_metrics", config.infrastructureMetricsUri.toString())
    }
}

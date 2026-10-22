package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class PerformanceFlusherTest {
    private lateinit var server: MockWebServer
    private var flusher: PerformanceFlusher? = null

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun cleanup() {
        flusher?.discard()
        server.shutdown()
    }

    private fun configuration(configure: Configuration.() -> Unit = {}): Configuration =
        Configuration(ApplicationProvider.getApplicationContext()).apply {
            dsn = "http://key@${server.hostName}:${server.port}/api/v1/events"
            environment = "production"
            performanceFlushIntervalMillis = 3_600_000 // tests flush by hand unless they say otherwise
            configure()
        }

    private fun flusherFor(config: Configuration): PerformanceFlusher =
        PerformanceFlusher(config, Client(config)).also { flusher = it }

    private fun firstSample(request: RecordedRequest): JSONObject =
        JSONObject(request.body.readUtf8()).getJSONArray("samples").getJSONObject(0)

    @Test
    fun `strict privacy takes the ids out of a raw path transaction name and keeps a plain name`() {
        val f = flusherFor(configuration { strictPrivacy = true })

        f.record("GET /patients/42", 10.0)
        f.record("GET /patients/43", 30.0)
        f.record("HomeScreen.load", 5.0)

        assertEquals(setOf("GET /patients/:path", "HomeScreen.load"), f.tallies().keys)
        assertEquals(2L, f.tallies()["GET /patients/:path"]?.first)
    }

    @Test
    fun `buckets by transaction name with count sum and max`() {
        val f = flusherFor(configuration())

        f.record("HomeScreen.load", 10.0)
        f.record("HomeScreen.load", 30.0)
        f.record("Checkout.pay", 5.0)

        assertEquals(Triple(2L, 40.0, 30.0), f.tallies()["HomeScreen.load"])
        assertEquals(Triple(1L, 5.0, 5.0), f.tallies()["Checkout.pay"])
    }

    @Test
    fun `record does nothing when trackPerformance is off or the environment is not enabled`() {
        val off = flusherFor(configuration { trackPerformance = false })
        off.record("x", 10.0)
        assertTrue(off.tallies().isEmpty())

        val development = flusherFor(configuration { environment = "development" })
        development.record("x", 10.0)
        assertTrue(development.tallies().isEmpty())
    }

    @Test
    fun `flush delivers one batch to performance_samples and empties the buckets`() {
        server.enqueue(MockResponse().setResponseCode(202))
        val f = flusherFor(configuration { release = "a1b2c3d" })
        f.record("HomeScreen.load", 10.0)
        f.record("HomeScreen.load", 30.0)

        f.flush()

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/performance_samples", request.path)
        assertEquals("Bearer key", request.getHeader("Authorization"))
        val sample = firstSample(request)
        assertEquals("HomeScreen.load", sample.getString("transaction_name"))
        assertEquals(2, sample.getInt("request_count"))
        assertEquals(40.0, sample.getDouble("duration_sum_ms"), 0.0)
        assertEquals(30.0, sample.getDouble("max_duration_ms"), 0.0)
        assertEquals("production", sample.getString("environment"))
        assertEquals("a1b2c3d", sample.getString("release"))
        assertTrue(sample.getString("period_started_at").endsWith("Z"))
        assertTrue(f.tallies().isEmpty())
    }

    @Test
    fun `flush does nothing when there is nothing to send`() {
        flusherFor(configuration()).flush()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a failed delivery keeps every bucket so the next flush carries more`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(202))
        val f = flusherFor(configuration())
        f.record("x", 10.0)

        f.flush()
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["x"])

        f.record("x", 20.0)
        f.flush()

        server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals(2, firstSample(server.takeRequest(2, TimeUnit.SECONDS)!!).getInt("request_count"))
        assertTrue(f.tallies().isEmpty())
    }

    @Test
    fun `a rejected delivery drops the batch and never sends it again`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(202))
        val logged = ArrayList<String>()
        val f = flusherFor(configuration { logger = { logged.add(it) } })
        f.record("x", 10.0)

        f.flush()
        assertTrue(f.tallies().isEmpty())
        assertEquals(1, logged.count { it.contains("dropped 1 performance samples") && it.contains("HTTP 401") })

        f.flush() // nothing left: no second request
        assertEquals(1, server.requestCount)

        f.record("y", 20.0)
        f.flush()

        server.takeRequest(2, TimeUnit.SECONDS)
        val samples = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("samples")
        assertEquals(1, samples.length())
        assertEquals("y", samples.getJSONObject(0).getString("transaction_name"))
    }

    @Test
    fun `a 429 or an unreachable server keeps every bucket`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val config = configuration()
        val f = flusherFor(config)
        f.record("x", 10.0)

        f.flush()
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["x"])

        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        config.dsn = "http://key@localhost:$closedPort/api/v1/events"
        config.connectTimeoutMillis = 300
        f.flush()
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["x"])
    }

    @Test
    fun `the fifth failed flush in a row drops the batch`() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(503)) }
        val logged = ArrayList<String>()
        val f = flusherFor(configuration { logger = { logged.add(it) } })
        f.record("x", 10.0)

        repeat(4) { f.flush() }
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["x"])

        f.flush()
        assertTrue(f.tallies().isEmpty())
        assertEquals(5, server.requestCount)
        assertEquals(1, logged.count { it.contains("after 5 failed flushes in a row") })

        f.flush() // nothing left: no sixth request
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `a successful flush starts the failure count again`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(MockResponse().setResponseCode(202))
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }
        val f = flusherFor(configuration())
        f.record("x", 10.0)

        repeat(5) { f.flush() }
        assertTrue(f.tallies().isEmpty())

        f.record("y", 10.0)
        repeat(4) { f.flush() }
        assertEquals(Triple(1L, 10.0, 10.0), f.tallies()["y"])
    }

    @Test
    fun `a record that lands during delivery is never lost`() {
        // Deterministic reproduction of the race flush's own comment describes: the server holds its
        // response until this test has recorded again, guaranteeing record() runs strictly between
        // the snapshot and delivery succeeding.
        val gate = CountDownLatch(1)
        val received = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received.countDown()
                gate.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(202)
            }
        }
        val f = flusherFor(configuration())
        f.record("x", 10.0)

        val flushing = Thread { f.flush() }
        flushing.start()
        assertTrue(received.await(2, TimeUnit.SECONDS))

        f.record("x", 25.0) // same transaction, mid-delivery
        f.record("new", 7.0) // a brand-new one, mid-delivery
        gate.countDown()
        flushing.join(5000)

        assertEquals(Triple(1L, 25.0, 25.0), f.tallies()["x"])
        assertEquals(Triple(1L, 7.0, 7.0), f.tallies()["new"])
    }

    @Test
    fun `the schedule flushes on its own interval`() {
        server.enqueue(MockResponse().setResponseCode(202))
        val f = flusherFor(configuration { performanceFlushIntervalMillis = 50 })

        f.record("x", 10.0)

        assertEquals("x", firstSample(server.takeRequest(3, TimeUnit.SECONDS)!!).getString("transaction_name"))
    }

    @Test
    fun `discard stops the schedule and delivers nothing`() {
        val f = flusherFor(configuration { performanceFlushIntervalMillis = 50 })
        f.record("x", 10.0)

        f.discard()
        Thread.sleep(300)

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `performanceSamplesUri swaps the trailing events segment`() {
        val config = Configuration(ApplicationProvider.getApplicationContext()).apply { dsn = "https://key@tracker.example.com/api/v1/events" }

        assertEquals("https://tracker.example.com/api/v1/performance_samples", config.performanceSamplesUri.toString())
        assertNull(Configuration(ApplicationProvider.getApplicationContext()).apply { dsn = null }.performanceSamplesUri)
    }

    private fun histogramOf(sample: JSONObject): Map<String, Int> {
        val histogram = sample.getJSONObject("histogram")
        return histogram.keys().asSequence().associateWith { histogram.getInt(it) }
    }

    @Test
    fun `delivers a latency histogram per bucket alongside count sum and max`() {
        server.enqueue(MockResponse().setResponseCode(202))
        val f = flusherFor(configuration())
        for (duration in listOf(10.0, 40.0, 120.0, 700.0, 12_000.0)) f.record("HomeScreen.load", duration)

        f.flush()

        val sample = firstSample(server.takeRequest(2, TimeUnit.SECONDS)!!)
        assertEquals(mapOf("50" to 2, "250" to 1, "1000" to 1, "inf" to 1), histogramOf(sample))
        assertEquals(5, sample.getInt("request_count"))
    }

    @Test
    fun `keeps histogram counts for the next flush when delivery fails`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(202))
        val f = flusherFor(configuration())
        f.record("x", 10.0)
        f.flush() // the first delivery fails: the histogram must survive it

        f.record("x", 300.0)
        f.flush()

        server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals(mapOf("50" to 1, "500" to 1), histogramOf(firstSample(server.takeRequest(2, TimeUnit.SECONDS)!!)))
    }

    @Test
    fun `a histogram count recorded during delivery is sent on the next flush`() {
        val gate = CountDownLatch(1)
        val received = CountDownLatch(1)
        val bodies = java.util.concurrent.LinkedBlockingQueue<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                bodies.add(request.body.readUtf8())
                received.countDown()
                gate.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(202)
            }
        }
        val f = flusherFor(configuration())
        f.record("x", 10.0)

        val flushing = Thread { f.flush() }
        flushing.start()
        assertTrue(received.await(2, TimeUnit.SECONDS))
        f.record("x", 300.0) // same transaction, mid-delivery
        f.record("y", 5.0) // a brand-new one, mid-delivery
        gate.countDown()
        flushing.join(5000)
        assertTrue(bodies.poll(3, TimeUnit.SECONDS)!!.contains("\"histogram\":{\"50\":1}"))

        f.flush()
        val second = bodies.poll(3, TimeUnit.SECONDS)!!
        assertTrue(second.contains("\"histogram\":{\"500\":1}"))
        assertTrue(second.contains("\"transaction_name\":\"y\""))
    }
}

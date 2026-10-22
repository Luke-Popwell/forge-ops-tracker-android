package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ForgeOpsTrackerTest {
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

    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `init configures and returns the shared configuration`() {
        val config = ForgeOpsTracker.init(context()) { c ->
            c.dsn = "https://key@tracker.example.com/events"
            c.release = "abc123"
        }

        assertEquals("https://key@tracker.example.com/events", config.dsn)
        assertEquals("abc123", config.release)
    }

    @Test
    fun `captureException delivers through the full stack`() {
        server.enqueue(MockResponse().setResponseCode(202))

        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }

        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("Bearer key", recorded?.getHeader("Authorization"))
    }

    @Test
    fun `setUser attaches the user to a later captureException call`() {
        server.enqueue(MockResponse().setResponseCode(202))

        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }

        ForgeOpsTracker.setUser(mapOf("id" to 42, "email" to "alice@example.com"))
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertTrue(recorded?.body?.readUtf8()?.contains("alice@example.com") == true)
    }

    @Test
    fun `an explicit user argument overrides whatever setUser last set`() {
        server.enqueue(MockResponse().setResponseCode(202))

        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }

        ForgeOpsTracker.setUser(mapOf("id" to 42))
        ForgeOpsTracker.captureException(IllegalStateException("boom"), user = mapOf("id" to 99))

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertTrue(recorded?.body?.readUtf8()?.contains("\"id\":99") == true)
    }

    @Test
    fun `resetForTesting clears the current user`() {
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.setUser(mapOf("id" to 42))
        ForgeOpsTracker.resetForTesting()

        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertTrue(recorded?.body?.readUtf8()?.contains("\"user\"") == false)
    }

    @Test
    fun `uncaught exception handler writes a pending report and chains to the previous handler`() {
        // Deliberately does NOT enqueue a mock response or assert a request arrives here: an
        // uncaught exception writes to the crash store only; it does not attempt a live upload
        // (see ForgeOpsTracker's own comment on captureException for why: unlike an explicit
        // report, this one means the process may be seconds from terminating, the same "no
        // network call near a crash" reasoning this repo's own Objective-C/Swift/C clients
        // follow). Verifying that full "written now, delivered next launch" story is what the
        // rest of this test does, via an explicit uploadPendingReportsSync() call afterward.
        val config = ForgeOpsTracker.init(context()) { c ->
            c.dsn = "http://key@${server.hostName}:${server.port}/events"
            c.environment = "production"
            c.installUncaughtExceptionHandler = true
            c.installAnrWatchdog = false
        }

        val previousRan = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> previousRan.countDown() }
        ForgeOpsTracker.installHandlers()

        val thread = Thread { throw RuntimeException("uncaught boom") }
        thread.start()
        thread.join()

        assertTrue(previousRan.await(2, TimeUnit.SECONDS))
        assertEquals(1, CrashStore(config).pendingFiles().size)

        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.uploadPendingReportsSync()

        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
    }

    @Test
    fun `installHandlers is idempotent`() {
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
        }

        ForgeOpsTracker.installHandlers()
        ForgeOpsTracker.installHandlers() // must not throw or double-install
    }

    @Test
    fun `addBreadcrumb attaches the trail to a later captureException call`() {
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }

        ForgeOpsTracker.addBreadcrumb("charging card", category = "payment", data = mapOf("order_id" to 42))
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        val body = server.takeRequest(2, TimeUnit.SECONDS)?.body?.readUtf8() ?: ""
        assertTrue(body.contains("charging card"))
        assertTrue(body.contains("\"category\":\"payment\""))
    }

    @Test
    fun `addBreadcrumb defaults to the custom category and info level`() {
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }

        ForgeOpsTracker.addBreadcrumb("something happened")

        val crumb = ForgeOpsTracker.currentBreadcrumbs.single()
        assertEquals("custom", crumb["category"])
        assertEquals("info", crumb["level"])
    }

    @Test
    fun `addBreadcrumb before init is a silent no-op, never a throw`() {
        ForgeOpsTracker.addBreadcrumb("too early")

        assertTrue(ForgeOpsTracker.currentBreadcrumbs.isEmpty())
    }

    @Test
    fun `clearBreadcrumbs empties the trail`() {
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }

        ForgeOpsTracker.addBreadcrumb("first")
        ForgeOpsTracker.clearBreadcrumbs()
        ForgeOpsTracker.captureException(IllegalStateException("boom"))

        val body = server.takeRequest(2, TimeUnit.SECONDS)?.body?.readUtf8() ?: ""
        assertFalse(body.contains("\"breadcrumbs\""))
    }

    @Test
    fun `resetForTesting clears the breadcrumb trail`() {
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }
        ForgeOpsTracker.addBreadcrumb("leftover")

        ForgeOpsTracker.resetForTesting()

        assertTrue(ForgeOpsTracker.currentBreadcrumbs.isEmpty())
    }

    @Test
    fun `an uncaught exception's pending report carries the breadcrumb trail`() {
        val config = ForgeOpsTracker.init(context()) { c ->
            c.dsn = "http://key@${server.hostName}:${server.port}/events"
            c.environment = "production"
            c.installUncaughtExceptionHandler = true
            c.installAnrWatchdog = false
        }
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        ForgeOpsTracker.installHandlers()
        ForgeOpsTracker.addBreadcrumb("about to crash", category = "ui")

        val thread = Thread { throw RuntimeException("uncaught boom") }
        thread.start()
        thread.join()

        val pending = CrashStore(config).pendingFiles().single()
        val payload = CrashStore(config).read(pending)!!
        assertEquals("about to crash", payload.getJSONArray("breadcrumbs").getJSONObject(0).getString("message"))
    }

    @Test
    fun `recordPerformance tallies in-process and flushPerformanceSync delivers one aggregate batch`() {
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
            config.performanceFlushIntervalMillis = 3_600_000 // flushed by hand below
        }

        ForgeOpsTracker.recordPerformance("HomeScreen.load", 10.0)
        ForgeOpsTracker.recordPerformance("HomeScreen.load", 30.0)
        ForgeOpsTracker.flushPerformanceSync()

        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/performance_samples", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"transaction_name\":\"HomeScreen.load\""))
        assertTrue(body.contains("\"request_count\":2"))
    }

    @Test
    fun `flushPerformance runs on the upload thread, never the callers`() {
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
            config.performanceFlushIntervalMillis = 3_600_000 // flushed by hand below
        }
        ForgeOpsTracker.recordPerformance("x", 10.0)

        ForgeOpsTracker.flushPerformance()

        assertTrue(server.takeRequest(3, TimeUnit.SECONDS) != null)
    }

    @Test
    fun `timeTransaction returns the block's own value and records how long it took`() {
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
            config.performanceFlushIntervalMillis = 3_600_000 // flushed by hand below
        }

        val value = ForgeOpsTracker.timeTransaction("timed") {
            Thread.sleep(30)
            42
        }
        ForgeOpsTracker.flushPerformanceSync()

        assertEquals(42, value)
        val sample = org.json.JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("samples").getJSONObject(0)
        assertEquals("timed", sample.getString("transaction_name"))
        assertTrue(sample.getDouble("duration_sum_ms") >= 25.0)
    }

    @Test
    fun `timeTransaction records even when the block throws, and rethrows it unchanged`() {
        server.enqueue(MockResponse().setResponseCode(202))
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
            config.performanceFlushIntervalMillis = 3_600_000 // flushed by hand below
        }

        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            ForgeOpsTracker.timeTransaction<Unit>("timed throws") { throw IllegalStateException("inside") }
        }
        ForgeOpsTracker.flushPerformanceSync()

        assertEquals("inside", thrown.message)
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8().contains("timed throws"))
    }

    @Test
    fun `trackPerformance off records and delivers nothing`() {
        ForgeOpsTracker.init(context()) { config ->
            config.dsn = "http://key@${server.hostName}:${server.port}/events"
            config.environment = "production"
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
            config.trackPerformance = false
        }

        ForgeOpsTracker.recordPerformance("never recorded", 10.0)
        ForgeOpsTracker.flushPerformanceSync()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `recordPerformance before init is a silent no-op, never a throw`() {
        ForgeOpsTracker.recordPerformance("too early", 10.0)
        ForgeOpsTracker.flushPerformanceSync()

        assertEquals(0, server.requestCount)
    }
}

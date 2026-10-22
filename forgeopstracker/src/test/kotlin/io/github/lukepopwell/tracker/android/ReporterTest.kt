package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReporterTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun newConfiguration(): Configuration {
        val config = Configuration(ApplicationProvider.getApplicationContext())
        config.dsn = "http://key@${server.hostName}:${server.port}/events"
        return config
    }

    @Test
    fun `reportThrowable does nothing when disabled`() {
        val config = newConfiguration()
        config.environment = "development" // not enabled

        Reporter(config).reportThrowable(IllegalStateException("boom"))

        assertEquals(0, CrashStore(config).pendingFiles().size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `reportThrowable writes a pending file when enabled`() {
        val config = newConfiguration()
        config.environment = "production"

        Reporter(config).reportThrowable(IllegalStateException("boom"))

        assertEquals(1, CrashStore(config).pendingFiles().size)
    }

    @Test
    fun `uploadPendingReports delivers and deletes on success`() {
        server.enqueue(MockResponse().setResponseCode(202))
        val config = newConfiguration()
        config.environment = "production"
        val reporter = Reporter(config)

        reporter.reportThrowable(IllegalStateException("boom"))
        reporter.uploadPendingReports()

        assertEquals(0, CrashStore(config).pendingFiles().size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `uploadPendingReports leaves the file in place on failure`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val config = newConfiguration()
        config.environment = "production"
        val reporter = Reporter(config)

        reporter.reportThrowable(IllegalStateException("boom"))
        reporter.uploadPendingReports()

        assertEquals(1, CrashStore(config).pendingFiles().size)
    }

    @Test
    fun `uploadPendingReports leaves the file in place on a 429 or when nothing is listening`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val config = newConfiguration()
        config.environment = "production"
        val reporter = Reporter(config)
        reporter.reportThrowable(IllegalStateException("boom"))

        reporter.uploadPendingReports()
        assertEquals(1, CrashStore(config).pendingFiles().size)

        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        config.dsn = "http://key@localhost:$closedPort/events"
        config.connectTimeoutMillis = 300
        reporter.uploadPendingReports()
        assertEquals(1, CrashStore(config).pendingFiles().size)
    }

    @Test
    fun `uploadPendingReports deletes a report the server rejects and never sends it again`() {
        server.enqueue(MockResponse().setResponseCode(422))
        server.enqueue(MockResponse().setResponseCode(400))
        val config = newConfiguration()
        config.environment = "production"
        val logged = ArrayList<String>()
        config.logger = { logged.add(it) }
        val reporter = Reporter(config)
        reporter.reportThrowable(IllegalStateException("first"))
        reporter.reportThrowable(IllegalStateException("second"))

        reporter.uploadPendingReports()

        assertEquals(0, CrashStore(config).pendingFiles().size)
        assertEquals("a rejected report must not hold up the one behind it", 2, server.requestCount)
        assertEquals(2, logged.count { it.contains("dropped a crash report") })

        reporter.uploadPendingReports()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `reportThrowable includes the given user, never scrubbed even though it's an email`() {
        val config = newConfiguration()
        config.environment = "production"
        val reporter = Reporter(config)

        reporter.reportThrowable(IllegalStateException("boom"), null, mapOf("id" to 42, "email" to "alice@example.com"))

        val files = CrashStore(config).pendingFiles()
        val payload = CrashStore(config).read(files[0])
        assertEquals("alice@example.com", payload?.getJSONObject("user")?.getString("email"))
    }

    @Test
    fun `reportAnr writes a pending file shaped as an ANR`() {
        val config = newConfiguration()
        config.environment = "production"
        val reporter = Reporter(config)

        reporter.reportAnr(Thread.currentThread().stackTrace)

        val files = CrashStore(config).pendingFiles()
        assertEquals(1, files.size)
        assertTrue(CrashStore(config).read(files[0])?.getString("exception_class") == "ANR")
    }
}

package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

/**
 * Exercises Client against a real local HTTP server (MockWebServer) rather than a mocking
 * framework: the same "test against something real" approach `sdks/java`/`sdks/kotlin` use for
 * their own Client tests, via MockWebServer here specifically because AGP's unit-test compile
 * classpath doesn't expose `com.sun.net.httpserver.HttpServer` the way a plain-JVM project's own
 * classpath does (confirmed directly: a compile error, not a runtime one). Robolectric is only
 * here to construct a real Configuration (which needs a Context): HttpURLConnection itself is
 * plain `java.net`, unaffected by Robolectric either way.
 */
@RunWith(RobolectricTestRunner::class)
class ClientTest {
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

    private fun newConfiguration(): Configuration = Configuration(ApplicationProvider.getApplicationContext())

    @Test
    fun `delivers successfully on a 2xx response and sends the authorization header`() {
        server.enqueue(MockResponse().setResponseCode(202))

        val config = newConfiguration()
        config.dsn = "http://the-api-key@${server.hostName}:${server.port}/api/v1/events"
        val client = Client(config)

        val delivered = client.deliver(JSONObject().put("message", "boom"))

        assertTrue(delivered)
        val recorded = server.takeRequest()
        assertEquals("Bearer the-api-key", recorded.getHeader("Authorization"))
        assertEquals("{\"message\":\"boom\"}", recorded.body.readUtf8())
    }

    @Test
    fun `returns false on a non-2xx response`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val config = newConfiguration()
        config.dsn = "http://key@${server.hostName}:${server.port}/events"
        val client = Client(config)

        assertFalse(client.deliver(JSONObject().put("message", "boom")))
    }

    @Test
    fun `a 4xx is rejected, except 408 and 429 which like a 5xx fail for now`() {
        for (status in listOf(400, 401, 403, 404, 413, 422)) {
            assertEquals("status $status", DeliveryOutcome.REJECTED, DeliveryOutcome.of(status))
        }
        for (status in listOf(0, 408, 429, 500, 502, 503, 301)) {
            assertEquals("status $status", DeliveryOutcome.FAILED, DeliveryOutcome.of(status))
        }
        for (status in listOf(200, 201, 202, 204)) {
            assertEquals("status $status", DeliveryOutcome.DELIVERED, DeliveryOutcome.of(status))
        }
    }

    @Test
    fun `deliverStatus is the http status, and 0 when nothing is listening`() {
        server.enqueue(MockResponse().setResponseCode(422))
        val config = newConfiguration()
        config.dsn = "http://key@${server.hostName}:${server.port}/events"
        assertEquals(422, Client(config).deliverStatus(JSONObject().put("message", "boom")))

        val closedPort = ServerSocket(0).use { it.localPort }
        config.dsn = "http://key@localhost:$closedPort/events"
        config.connectTimeoutMillis = 300
        assertEquals(0, Client(config).deliverStatus(JSONObject().put("message", "boom")))
    }

    @Test
    fun `returns false when there is no dsn`() {
        val client = Client(newConfiguration())
        assertFalse(client.deliver(JSONObject().put("message", "boom")))
    }

    @Test
    fun `returns false rather than throwing when nothing is listening`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val config = newConfiguration()
        config.dsn = "http://key@localhost:$closedPort/events"
        config.connectTimeoutMillis = 300
        val client = Client(config)

        assertFalse(client.deliver(JSONObject().put("message", "boom")))
    }
}

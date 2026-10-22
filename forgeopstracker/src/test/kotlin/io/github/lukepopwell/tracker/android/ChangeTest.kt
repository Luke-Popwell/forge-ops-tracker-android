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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.util.Date
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ChangeTest {
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

    private fun init(port: Int = server.port, environment: String = "production") {
        ForgeOpsTracker.init(ApplicationProvider.getApplicationContext()) { config ->
            config.dsn = "http://key@${server.hostName}:$port/api/v1/events"
            config.environment = environment
            config.installUncaughtExceptionHandler = false
            config.installAnrWatchdog = false
        }
    }

    private fun configuration() = Configuration(ApplicationProvider.getApplicationContext())

    @Test
    fun `recordChange posts the change to the changes endpoint`() {
        server.enqueue(MockResponse().setResponseCode(202))
        init()

        ForgeOpsTracker.recordChange(
            "feature_flag",
            "new_checkout turned on",
            details = mapOf("key" to "new_checkout", "from" to false, "to" to true, "rollout" to listOf(10, 50)),
            service = "android-app",
            actor = "flag-service",
            url = "https://flags.example.com/new_checkout",
            id = "flag-123",
            occurredAt = Date(1_790_000_000_000L),
        )

        val request = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("POST", request!!.method)
        assertEquals("/api/v1/changes", request.path)
        assertEquals("Bearer key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("feature_flag", body.getString("kind"))
        assertEquals("new_checkout turned on", body.getString("title"))
        assertEquals("production", body.getString("environment"))
        assertEquals("2026-09-21T14:13:20.000Z", body.getString("occurred_at"))
        assertEquals("android-app", body.getString("service"))
        assertEquals("flag-service", body.getString("actor"))
        assertEquals("https://flags.example.com/new_checkout", body.getString("url"))
        assertEquals("flag-123", body.getString("id"))
        val details = body.getJSONObject("details")
        assertEquals("new_checkout", details.getString("key"))
        assertEquals(true, details.getBoolean("to"))
        assertEquals(50, details.getJSONArray("rollout").getInt(1))
    }

    @Test
    fun `optional fields are left out and occurred_at defaults to now`() {
        val payload = Change.payload(configuration().apply { environment = "staging" }, "config", "checkout timeout raised")!!

        assertEquals(setOf("kind", "title", "environment", "occurred_at"), payload.keys().asSequence().toSet())
        assertEquals("staging", payload.getString("environment"))
        assertEquals(24, payload.getString("occurred_at").length)
    }

    @Test
    fun `an unknown kind is sent as other`() {
        Change.KINDS.forEach { assertEquals(it, Change.normalizeKind(it)) }
        assertEquals("other", Change.normalizeKind("flag"))
        assertEquals("other", Change.normalizeKind(null))
        assertEquals("other", Change.payload(configuration(), "deploy", "x")!!.getString("kind"))
    }

    @Test
    fun `the title is truncated, a blank one drops the change, and a bad url or details are left out`() {
        val config = configuration()
        assertEquals(200, Change.payload(config, "other", "a".repeat(250))!!.getString("title").length)
        assertNull(Change.payload(config, "other", "   "))
        val payload = Change.payload(config, "other", "x", details = mapOf("ratio" to listOf(Double.NaN)), url = "javascript:alert(1)")!!
        assertFalse(payload.has("details"))
        assertFalse(payload.has("url"))
        assertEquals("qa", Change.payload(config, "other", "x", environment = "qa")!!.getString("environment"))
    }

    @Test
    fun `recordChange is a no-op when reporting is not enabled and before init`() {
        ForgeOpsTracker.recordChange("feature_flag", "before init") // must not throw
        init(environment = "development")

        ForgeOpsTracker.recordChange("feature_flag", "ignored")
        ForgeOpsTracker.flushChanges()

        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `recordChange never throws when the plan rejects it or the server is unreachable`() {
        server.enqueue(MockResponse().setResponseCode(403))
        init()
        ForgeOpsTracker.recordChange("feature_flag", "rejected")
        ForgeOpsTracker.flushChanges()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        val closedPort = ServerSocket(0).use { it.localPort }
        ForgeOpsTracker.resetForTesting()
        init(port = closedPort)
        ForgeOpsTracker.recordChange("feature_flag", "unreachable")
        ForgeOpsTracker.flushChanges()
        assertFalse(Client(configuration().apply { dsn = "http://key@127.0.0.1:$closedPort/api/v1/events" }).deliverChange(JSONObject()))
    }

    @Test
    fun `the changes uri swaps the trailing events segment`() {
        val config = configuration().apply { dsn = "https://key@tracker.example.com/api/v1/events" }
        assertEquals("https://tracker.example.com/api/v1/changes", config.changesUri.toString())
    }
}

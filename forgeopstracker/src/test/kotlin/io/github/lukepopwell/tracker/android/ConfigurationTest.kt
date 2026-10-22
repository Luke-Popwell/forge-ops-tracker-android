package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigurationTest {
    private fun newConfiguration() = Configuration(ApplicationProvider.getApplicationContext())

    @Test
    fun `defaults derive package name and files dir from the context`() {
        val config = newConfiguration()

        assertFalse(config.packageName.isNullOrBlank())
        assertTrue(config.crashReportsDirectory.path.contains("forgeops-tracker"))
        assertEquals("production", config.environment)
        assertTrue(config.scrubPii)
        assertTrue(config.installUncaughtExceptionHandler)
        assertTrue(config.installAnrWatchdog)
        assertTrue(config.enabledEnvironments.containsAll(listOf("production", "staging")))
        assertTrue(config.propagateTraces)
        assertNull(config.tracePropagationTargets)
        assertTrue(config.shouldPropagateTrace("any.host.example"))
    }

    @Test
    fun `trace propagation targets match a host or its subdomains on a dot boundary, and regexes`() {
        val config = newConfiguration()
        config.tracePropagationTargets = listOf("example.com", ".internal.example", Regex("^10\\.0\\."), java.util.regex.Pattern.compile("corp$"))

        assertTrue(config.shouldPropagateTrace("example.com"))
        assertTrue(config.shouldPropagateTrace("api.example.com"))
        assertTrue(config.shouldPropagateTrace("API.Example.COM"))
        assertFalse(config.shouldPropagateTrace("badexample.com"))
        assertFalse(config.shouldPropagateTrace("example.com.evil.net"))
        assertTrue(config.shouldPropagateTrace("orders.internal.example"))
        assertTrue(config.shouldPropagateTrace("10.0.3.4"))
        assertFalse(config.shouldPropagateTrace("110.0.3.4"))
        assertTrue(config.shouldPropagateTrace("billing.corp"))
        assertFalse(config.shouldPropagateTrace(null))
    }

    @Test
    fun `an empty target list or propagateTraces off sends the header nowhere`() {
        val config = newConfiguration()
        config.tracePropagationTargets = emptyList()
        assertFalse(config.shouldPropagateTrace("api.example.com"))

        config.tracePropagationTargets = null
        config.propagateTraces = false
        assertFalse(config.shouldPropagateTrace("api.example.com"))
    }

    @Test
    fun `apiKey and ingestionUri`() {
        val config = newConfiguration()
        config.dsn = "https://abc123@forgeops.example/api/v1/events"

        assertEquals("abc123", config.apiKey)
        assertEquals("https://forgeops.example/api/v1/events", config.ingestionUri.toString())
    }

    @Test
    fun `empty or malformed dsn has no apiKey or ingestionUri`() {
        for (dsn in listOf("", "not a url")) {
            val config = newConfiguration()
            config.dsn = dsn
            assertNull(config.apiKey)
            assertNull(config.ingestionUri)
        }
    }

    @Test
    fun `isEnabled requires dsn, apiKey, and an enabled environment`() {
        val config = newConfiguration()
        config.dsn = "https://key@host/path"

        config.environment = "production"
        assertTrue(config.isEnabled)

        config.environment = "development"
        assertFalse(config.isEnabled)

        config.environment = "production"
        config.dsn = null
        assertFalse(config.isEnabled)
    }

    @Test
    fun `a DSN alone is enough to send, since the environment defaults to production`() {
        val config = newConfiguration()
        config.dsn = "https://key@host/api/v1/events"

        assertTrue(config.isEnabled)
        assertNull(config.notSendingWarning)
    }

    @Test
    fun `notSendingWarning says which environment it saw and how to change it`() {
        val config = newConfiguration()
        assertNull(config.notSendingWarning)

        config.dsn = "https://key@host/api/v1/events"
        config.environment = "development"
        assertEquals(
            "Not sending: this environment is \"development\", and only production, staging are enabled. " +
                "Set config.environment = \"production\" (or add \"development\" to the enabled environments) " +
                "to send from here.",
            config.notSendingWarning,
        )

        config.enabledEnvironments = emptySet()
        assertTrue(config.notSendingWarning!!.contains("no environments are enabled"))
    }

    @Test
    fun `init logs the warning once per process, and not at all when it will send`() {
        val sending = newConfiguration().apply { dsn = "https://key@host/api/v1/events" }
        val quiet = newConfiguration().apply { dsn = "https://key@host/api/v1/events"; environment = "development" }
        val warned = java.util.concurrent.atomic.AtomicBoolean()
        val logged = { org.robolectric.shadows.ShadowLog.getLogsForTag("ForgeOps").map { it.msg } }
        org.robolectric.shadows.ShadowLog.clear()

        ForgeOpsTracker.warnIfNotSending(sending, warned)
        assertTrue(logged().isEmpty())

        ForgeOpsTracker.warnIfNotSending(quiet, warned)
        ForgeOpsTracker.warnIfNotSending(quiet, warned)
        assertEquals(1, logged().size)
        assertTrue(logged().single().startsWith("[ForgeOps] Not sending: this environment is \"development\""))
    }
}

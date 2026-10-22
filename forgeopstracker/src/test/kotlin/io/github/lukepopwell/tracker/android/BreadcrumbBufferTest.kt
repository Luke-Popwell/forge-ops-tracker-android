package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class BreadcrumbBufferTest {
    private fun newConfiguration() = Configuration(ApplicationProvider.getApplicationContext())

    @Test
    fun `records entries in order with the wire shape`() {
        val buffer = BreadcrumbBuffer(newConfiguration())

        buffer.add("first", "custom", "info", emptyMap())
        buffer.add("second", "payment", "warning", mapOf("order_id" to 42))

        val all = buffer.all()
        assertEquals(2, all.size)
        assertEquals("first", all[0]["message"])
        assertEquals(emptyMap<String, Any?>(), all[0]["data"])
        assertEquals("payment", all[1]["category"])
        assertEquals("warning", all[1]["level"])
        assertEquals(mapOf("order_id" to 42), all[1]["data"])
        assertTrue((all[1]["timestamp"] as String).endsWith("Z"))
    }

    @Test
    fun `drops the oldest entries past maxBreadcrumbs`() {
        val config = newConfiguration().apply { maxBreadcrumbs = 2 }
        val buffer = BreadcrumbBuffer(config)

        listOf("first", "second", "third").forEach { buffer.add(it, "custom", "info", emptyMap()) }

        assertEquals(listOf("second", "third"), buffer.all().map { it["message"] })
    }

    @Test
    fun `records nothing when trackBreadcrumbs is off`() {
        val config = newConfiguration().apply { trackBreadcrumbs = false }
        val buffer = BreadcrumbBuffer(config)

        buffer.add("nope", "custom", "info", emptyMap())

        assertTrue(buffer.all().isEmpty())
    }

    @Test
    fun `clear empties the trail`() {
        val buffer = BreadcrumbBuffer(newConfiguration())
        buffer.add("first", "custom", "info", emptyMap())

        buffer.clear()

        assertTrue(buffer.all().isEmpty())
    }

    @Test
    fun `all returns a copy, never the live list`() {
        val buffer = BreadcrumbBuffer(newConfiguration())
        buffer.add("first", "custom", "info", emptyMap())

        val snapshot = buffer.all()
        buffer.add("second", "custom", "info", emptyMap())

        assertEquals(1, snapshot.size)
    }

    @Test
    fun `a trail is shared across threads, not per-thread`() {
        val buffer = BreadcrumbBuffer(newConfiguration())

        val thread = Thread { buffer.add("added on another thread", "custom", "info", emptyMap()) }
        thread.start()
        thread.join()

        assertEquals(listOf("added on another thread"), buffer.all().map { it["message"] })
    }

    @Test
    fun `is safe to add to from many threads at once`() {
        val config = newConfiguration().apply { maxBreadcrumbs = 50 }
        val buffer = BreadcrumbBuffer(config)
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(200)

        repeat(200) { i ->
            pool.execute {
                buffer.add("crumb $i", "custom", "info", emptyMap())
                buffer.all()
                done.countDown()
            }
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals("exactly the cap, no lost or duplicated slots", 50, buffer.all().size)
    }
}

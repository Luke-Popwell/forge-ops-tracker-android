package io.github.lukepopwell.tracker.android

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashStoreTest {
    private fun newStore(): CrashStore {
        val config = Configuration(ApplicationProvider.getApplicationContext())
        return CrashStore(config)
    }

    @Test
    fun `write and read round trips`() {
        val store = newStore()
        val payload = JSONObject().put("exception_class", "Boom").put("message", "bad")

        assertTrue(store.write(payload))

        val files = store.pendingFiles()
        assertEquals(1, files.size)
        val read = store.read(files[0])
        assertEquals("Boom", read?.getString("exception_class"))
        assertEquals("bad", read?.getString("message"))
    }

    @Test
    fun `pendingFiles are oldest first`() {
        val store = newStore()
        for (i in 0..2) {
            store.write(JSONObject().put("n", i))
            Thread.sleep(10) // ensure distinct mtimes
        }

        val values = store.pendingFiles().map { store.read(it)?.getInt("n") }
        assertEquals(listOf(0, 1, 2), values)
    }

    @Test
    fun `delete removes it`() {
        val store = newStore()
        store.write(JSONObject().put("n", 1))
        val file = store.pendingFiles()[0]

        store.delete(file)

        assertTrue(store.pendingFiles().isEmpty())
    }

    @Test
    fun `pendingFiles keeps only the newest twenty and deletes the rest`() {
        val config = Configuration(ApplicationProvider.getApplicationContext())
        val store = CrashStore(config)
        val total = CrashStore.MAX_PENDING_FILES + 3
        for (i in 0 until total) store.write(JSONObject().put("n", i))
        // Spread the mtimes out by hand: oldest first, an hour apart.
        val now = System.currentTimeMillis()
        for (file in config.crashReportsDirectory.listFiles()!!) {
            val n = store.read(file)!!.getInt("n")
            assertTrue(file.setLastModified(now - (total - n) * 3_600_000L))
        }

        val values = store.pendingFiles().map { store.read(it)?.getInt("n") }

        assertEquals((3 until total).toList(), values)
        assertEquals(CrashStore.MAX_PENDING_FILES, config.crashReportsDirectory.listFiles()!!.size)
    }

    @Test
    fun `pendingFiles deletes anything older than seven days`() {
        val config = Configuration(ApplicationProvider.getApplicationContext())
        val store = CrashStore(config)
        for (name in listOf("old", "almost", "new")) store.write(JSONObject().put("n", name))
        val now = System.currentTimeMillis()
        for (file in config.crashReportsDirectory.listFiles()!!) {
            when (store.read(file)!!.getString("n")) {
                "old" -> assertTrue(file.setLastModified(now - CrashStore.MAX_PENDING_AGE_MILLIS - 3_600_000L))
                "almost" -> assertTrue(file.setLastModified(now - CrashStore.MAX_PENDING_AGE_MILLIS + 3_600_000L))
            }
        }

        val values = store.pendingFiles().map { store.read(it)?.getString("n") }

        assertEquals(listOf("almost", "new"), values)
        assertEquals(2, config.crashReportsDirectory.listFiles()!!.size)
    }

    @Test
    fun `pendingFiles is empty when the directory does not exist`() {
        val store = newStore()
        assertTrue(store.pendingFiles().isEmpty())
    }
}

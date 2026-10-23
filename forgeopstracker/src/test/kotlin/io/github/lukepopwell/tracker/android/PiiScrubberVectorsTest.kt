package io.github.lukepopwell.tracker.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Checks this client against `pii_scrubbing_vectors.json` (a byte-identical copy of
 * `sdks/pii_scrubbing_vectors.json`, generated from the server's own `PiiScrubber`: see
 * `sdks/PII_SCRUBBING.md`). Robolectric only for `org.json`, which reads the file.
 */
@RunWith(RobolectricTestRunner::class)
class PiiScrubberVectorsTest {
    private fun vectors(kind: String): JSONArray {
        val json = javaClass.getResourceAsStream("/pii_scrubbing_vectors.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(json).getJSONArray(kind)
    }

    @Test
    fun `scrubs card numbers and ibans in every vector exactly as the server does`() {
        val vectors = vectors("strings")
        assertTrue(vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val input = vector.getString("input")
            assertEquals(input, vector.getString("scrubbed"), PiiScrubber.scrub(input))
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `redacts bank detail keys in every vector exactly as the server does`() {
        val vectors = vectors("keys")
        assertTrue(vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val key = vector.getString("key")
            val scrubbed = PiiScrubber.scrub(mapOf(key to "x")) as Map<String, Any?>
            assertEquals(key, if (vector.getBoolean("redacted")) PiiScrubber.REDACTED else "x", scrubbed[key])
        }
    }
}

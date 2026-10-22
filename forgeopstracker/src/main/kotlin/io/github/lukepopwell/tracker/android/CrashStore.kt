package io.github.lukepopwell.tracker.android

import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists event payloads to disk under [Configuration.crashReportsDirectory] and reads them back:
 * this SDK's "queue," in the sense every other client's own delivery queue is its own, except
 * this one survives the process dying, which for a mobile crash reporter is the one guarantee
 * that actually matters: the app is about to be killed by the OS, possibly with no chance to run
 * any more code at all, so anything not already durably written before that happens is lost.
 * Named `.json`, and stores the payload as already-serialized JSON text: there's no need to
 * re-parse a payload back into a structured value for anything other than re-serializing it
 * unchanged for delivery.
 */
class CrashStore(private val configuration: Configuration) {
    fun write(payload: JSONObject): Boolean = try {
        configuration.crashReportsDirectory.mkdirs()
        val file = File(configuration.crashReportsDirectory, "${UUID.randomUUID()}.json")
        file.writeText(payload.toString())
        true
    } catch (e: Exception) {
        configuration.log("crash store write failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /**
     * Every pending payload's file, oldest first. Anything older than [MAX_PENDING_AGE_MILLIS], and
     * the oldest beyond [MAX_PENDING_FILES], is deleted here first: a report that keeps failing to
     * upload (see [Reporter.uploadPendingReports]) must not pile up or be retried for good.
     */
    fun pendingFiles(): List<File> {
        val files = configuration.crashReportsDirectory.listFiles { file -> file.extension == "json" } ?: return emptyList()
        val sorted = files.sortedBy { it.lastModified() }

        // lastModified() is 0 when it can't be read: such a file is never counted as too old, only
        // toward the cap.
        val cutoff = System.currentTimeMillis() - MAX_PENDING_AGE_MILLIS
        val (expired, fresh) = sorted.partition { it.lastModified() in 1 until cutoff }
        val excess = fresh.take(maxOf(0, fresh.size - MAX_PENDING_FILES))
        (expired + excess).forEach { delete(it) }

        if (expired.isNotEmpty()) {
            configuration.log("dropped ${expired.size} crash reports older than 7 days that could not be delivered")
        }
        if (excess.isNotEmpty()) {
            configuration.log("dropped the ${excess.size} oldest crash reports: more than $MAX_PENDING_FILES were waiting to be delivered")
        }
        return fresh.drop(excess.size)
    }

    fun read(file: File): JSONObject? = try {
        JSONObject(file.readText())
    } catch (e: Exception) {
        null
    }

    fun delete(file: File) {
        file.delete()
    }

    companion object {
        const val MAX_PENDING_FILES = 20
        const val MAX_PENDING_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

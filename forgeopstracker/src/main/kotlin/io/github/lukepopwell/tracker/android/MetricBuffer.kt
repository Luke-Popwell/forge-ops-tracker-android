package io.github.lukepopwell.tracker.android

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Collects individual [ForgeOpsTracker.captureMetric]/[ForgeOpsTracker.captureInfrastructureMetric]
 * calls in-process and periodically flushes them as one batch, rather than one network call per
 * capture. Unlike [PerformanceFlusher] this keeps a *list* of individually meaningful entries instead
 * of summing them into buckets: a customer's own signup or payment is exactly the kind of thing they
 * will want a genuinely accurate count/sum of later, so the server stores one row per entry as-is.
 * Ported from `sdks/kotlin`'s own `MetricBuffer` (itself from `gems/forge_ops_tracker`'s two
 * near-identical buffer classes: one class instantiated twice here).
 *
 * Four deliberate differences from the Ruby buffers:
 *
 * - A flush snapshots the first N entries and, on success, removes exactly those N, instead of
 *   resetting the whole list, so an entry recorded while the request is in flight (the lock is
 *   released around the network call) is kept for the next flush rather than lost.
 * - The buffer is capped at [MAX_ENTRIES], and once full further entries are dropped until a flush
 *   succeeds or lets its batch go: an unreachable server fails every flush, and an uncapped buffer
 *   would then grow for as long as the process lives. Dropping the newest rather than the oldest
 *   keeps the entries a flush is delivering at the front of the list, which is what makes removing
 *   exactly them afterward exact.
 * - A batch is not retried without end. One the server rejects (a 4xx other than 408 and 429, such
 *   as the 403 a plan without the feature answers) is dropped at once, and one that has failed
 *   [MAX_CONSECUTIVE_FAILURES] flushes in a row is dropped too, each with a line to
 *   [Configuration.logger].
 * - A NaN or infinite value is dropped at record time: `org.json` refuses to encode one, and one bad
 *   entry would make every batch behind it fail to send.
 *
 * The same daemon [ScheduledExecutorService] as [PerformanceFlusher]. There is no JVM shutdown hook
 * on Android (the OS can kill the process at any moment), so call [ForgeOpsTracker.flushMetrics] from
 * `Activity.onStop` or wherever the app is about to go to the background.
 */
class MetricBuffer internal constructor(
    private val configuration: Configuration,
    private val intervalMillis: () -> Long,
    // The HTTP status of delivering one batch, or 0 when there was no response: see DeliveryOutcome.
    private val deliverStatus: (JSONArray) -> Int,
) {
    /**
     * `deliver` takes a batch and returns whether delivery succeeded. A `false` is a failure that
     * may work later: the batch is kept, up to [MAX_CONSECUTIVE_FAILURES] flushes in a row.
     */
    constructor(configuration: Configuration, deliver: (JSONArray) -> Boolean, intervalMillis: () -> Long) :
        this(configuration, intervalMillis, { entries -> if (deliver(entries)) 200 else 0 })

    private val lock = Any()
    private val entries = ArrayList<JSONObject>()
    private var consecutiveFailures = 0

    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** Adds one entry (everything but `recorded_at`, which is stamped here); returns whether it was kept. */
    fun record(entry: JSONObject, value: Double): Boolean {
        if (!value.isFinite()) {
            configuration.log("dropped a metric with a non-finite value")
            return false
        }

        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                configuration.log("metric buffer full, dropping a metric")
                return false
            }
            // A fresh SimpleDateFormat per call: java.time needs API 26 and this library's minSdk is
            // 21, and SimpleDateFormat isn't thread-safe.
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            entries.add(entry.put("recorded_at", format.format(Date())))
        }
        ensureScheduled()
        return true
    }

    /**
     * Delivers everything buffered so far as one batch. A delivery that failed for now keeps every
     * entry, so the next flush's batch just grows; a rejected batch, or one that has failed
     * [MAX_CONSECUTIVE_FAILURES] flushes in a row, is dropped.
     */
    fun flush() {
        val snapshot: List<JSONObject>
        synchronized(lock) {
            if (entries.isEmpty()) return
            snapshot = ArrayList(entries)
        }

        val status = deliverStatus(JSONArray(snapshot))

        synchronized(lock) {
            when (DeliveryOutcome.of(status)) {
                DeliveryOutcome.DELIVERED -> consecutiveFailures = 0
                DeliveryOutcome.REJECTED -> {
                    consecutiveFailures = 0
                    configuration.log("dropped ${snapshot.size} metrics: ${DeliveryOutcome.dropReason(status)}")
                }
                DeliveryOutcome.FAILED -> {
                    consecutiveFailures += 1
                    if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return
                    consecutiveFailures = 0
                    configuration.log(
                        "dropped ${snapshot.size} metrics after $MAX_CONSECUTIVE_FAILURES failed flushes in a row: " +
                            DeliveryOutcome.dropReason(status),
                    )
                }
            }
            // Exactly the entries just delivered or dropped: anything recorded while the request was in flight
            // sits after them and stays for the next flush.
            entries.subList(0, snapshot.size).clear()
        }
    }

    /** Stops the schedule and drops every entry without delivering anything. */
    fun discard() {
        executor?.shutdownNow()
        executor = null
        synchronized(lock) {
            entries.clear()
            consecutiveFailures = 0
        }
    }

    /** Test-only: how many entries are currently buffered. */
    internal fun size(): Int = synchronized(lock) { entries.size }

    private fun ensureScheduled() {
        if (executor != null) return
        synchronized(this) {
            if (executor != null) return
            val scheduled = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "forgeops-tracker-metric-flusher").apply { isDaemon = true }
            }
            val interval = maxOf(1L, intervalMillis())
            scheduled.scheduleWithFixedDelay(::flushSafely, interval, interval, TimeUnit.MILLISECONDS)
            executor = scheduled
        }
    }

    private fun flushSafely() {
        try {
            flush()
        } catch (e: RuntimeException) {
            // One bad flush must not kill every flush after it: a scheduled task that throws is
            // cancelled by ScheduledExecutorService, so this is what keeps the schedule alive.
            configuration.log("metric flush error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        const val MAX_ENTRIES = 1000

        /** A batch that has failed this many flushes in a row is dropped rather than carried further. */
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}

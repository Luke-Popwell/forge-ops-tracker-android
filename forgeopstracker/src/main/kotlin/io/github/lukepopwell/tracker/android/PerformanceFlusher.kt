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
 * Times work in-process, bucketed by transaction name (see [ForgeOpsTracker.timeTransaction]), and
 * periodically flushes each distinct bucket as one small aggregate report, rather than one network
 * call per timed call. Ported from `sdks/kotlin`'s own `PerformanceFlusher` (itself ported from
 * `sdks/java`'s and `gems/forge_ops_tracker`'s), including the one thing those learned the hard way:
 * see [flush]'s own comment on why it subtracts what it delivered instead of clearing the buckets.
 *
 * A daemon [ScheduledExecutorService] started lazily on first record, **with no JVM shutdown hook**,
 * unlike `sdks/kotlin`: an Android process is killed by the OS, not exited, and the JVM's shutdown
 * hooks are not run for that, so a hook here would be dead code that looks like a safety net. The
 * last partial window is instead the host app's to send, from `Activity.onStop` or wherever suits
 * (see [ForgeOpsTracker.flushPerformance]).
 */
class PerformanceFlusher(
    private val configuration: Configuration,
    private val client: Client,
) {
    private class Bucket(
        var count: Long = 0,
        var durationSumMs: Double = 0.0,
        var maxDurationMs: Double = 0.0,
        val histogram: MutableMap<String, Long> = LinkedHashMap(),
    )

    private val lock = Any()
    private val buckets = LinkedHashMap<String, Bucket>()
    private var periodStartedAt = Date()
    private var consecutiveFailures = 0

    @Volatile
    private var executor: ScheduledExecutorService? = null

    /** Does nothing (and starts no thread) when `trackPerformance` is off or reporting isn't enabled for this environment. */
    fun record(transactionName: String, durationMs: Double) {
        if (!configuration.trackPerformance || !configuration.isEnabled) return
        ensureScheduled()
        // Under strict privacy a raw path (the fallback when no route matched) loses its ids.
        val name = if (configuration.strictPrivacy) StrictPrivacy.spanName(transactionName) else transactionName
        synchronized(lock) {
            val bucket = buckets.getOrPut(name) { Bucket() }
            bucket.count++
            bucket.durationSumMs += durationMs
            if (durationMs > bucket.maxDurationMs) bucket.maxDurationMs = durationMs
            // The distribution count/sum/max can't reconstruct: see HistogramBucketer for why the
            // server approximates a percentile from these bucket counts.
            val label = HistogramBucketer.bucketFor(durationMs)
            bucket.histogram[label] = (bucket.histogram[label] ?: 0L) + 1
        }
    }

    /**
     * Snapshots the buffered buckets and delivers them as one batch (on the calling thread: never
     * call this from Android's main thread, see [ForgeOpsTracker.flushPerformance]). A delivery
     * that failed for now (a network error, a timeout, a 5xx, 408 or 429) keeps every bucket where
     * it is, so the next flush's batch just grows instead of losing what was already tallied:
     * there's no other copy of this data anywhere. That is bounded: the batch is dropped when the
     * server rejects it (any other 4xx, which no retry can fix) or once [MAX_CONSECUTIVE_FAILURES]
     * flushes in a row have failed, and [Configuration.logger] is told so.
     *
     * Only exactly what this snapshot delivered (or dropped) is removed afterward, subtracted from whatever is in
     * each bucket by then, never the whole map cleared outright. [record] can run concurrently with
     * this (that's what the lock is for), and delivery here happens with the lock released, so a
     * [record] call for a transaction already in the snapshot, or a brand-new one, can land in the
     * exact window between the snapshot and delivery succeeding. Clearing afterward, as if delivery
     * had covered everything now in the map, would silently discard that data forever. This is a
     * real bug `sdks/go` had and fixed, and that `gems/forge_ops_tracker`'s and `sdks/java`'s
     * flushers still have; see this package's own test for a deterministic reproduction.
     */
    fun flush() {
        val snapshot: Map<String, Bucket>
        val periodStart: Date
        val periodEnd: Date
        synchronized(lock) {
            if (buckets.isEmpty()) return
            periodStart = periodStartedAt
            periodEnd = Date()
            snapshot = buckets.mapValues { (_, b) -> Bucket(b.count, b.durationSumMs, b.maxDurationMs, LinkedHashMap(b.histogram)) }
        }

        // java.time needs API 26 and this library's minSdk is 21, so SimpleDateFormat; a fresh
        // instance per flush rather than one shared, since SimpleDateFormat isn't thread-safe and a
        // flush can run on the schedule's thread or a caller's.
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val samples = JSONArray()
        for ((name, bucket) in snapshot) {
            samples.put(
                JSONObject()
                    .put("transaction_name", name)
                    .put("environment", configuration.environment)
                    .put("release", configuration.release ?: JSONObject.NULL)
                    .put("period_started_at", format.format(periodStart))
                    .put("period_ended_at", format.format(periodEnd))
                    .put("request_count", bucket.count)
                    .put("duration_sum_ms", bucket.durationSumMs)
                    .put("max_duration_ms", bucket.maxDurationMs)
                    .put("histogram", JSONObject(bucket.histogram as Map<*, *>)),
            )
        }

        val status = client.performanceSamplesStatus(samples)

        synchronized(lock) {
            when (DeliveryOutcome.of(status)) {
                DeliveryOutcome.DELIVERED -> consecutiveFailures = 0
                DeliveryOutcome.REJECTED -> {
                    consecutiveFailures = 0
                    configuration.log("dropped ${samples.length()} performance samples: ${DeliveryOutcome.dropReason(status)}")
                }
                DeliveryOutcome.FAILED -> {
                    consecutiveFailures += 1
                    if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) return
                    consecutiveFailures = 0
                    configuration.log(
                        "dropped ${samples.length()} performance samples after $MAX_CONSECUTIVE_FAILURES failed flushes in a row: " +
                            DeliveryOutcome.dropReason(status),
                    )
                }
            }
            for ((name, sent) in snapshot) {
                val current = buckets[name] ?: continue
                current.count -= sent.count
                current.durationSumMs = maxOf(0.0, current.durationSumMs - sent.durationSumMs)
                for ((label, count) in sent.histogram) {
                    val remaining = (current.histogram[label] ?: 0L) - count
                    if (remaining > 0) current.histogram[label] = remaining else current.histogram.remove(label)
                }
                if (current.count <= 0) buckets.remove(name)
                // maxDurationMs is deliberately left as whatever is currently on the bucket, sent
                // or not: unlike count/durationSumMs, a max can't be correctly "subtracted" back
                // out (the true max of what's left is anything at or below it, not knowable from
                // the two numbers alone), and leaving it never overstates the next period's own
                // max, only potentially understates how far back it was actually set.
            }
            periodStartedAt = periodEnd
        }
    }

    /** Test-only: `(count, durationSumMs, maxDurationMs)` per transaction, as currently tallied. */
    internal fun tallies(): Map<String, Triple<Long, Double, Double>> = synchronized(lock) {
        buckets.mapValues { (_, b) -> Triple(b.count, b.durationSumMs, b.maxDurationMs) }
    }

    /** Stops the schedule and drops every bucket without delivering anything. */
    fun discard() {
        executor?.shutdownNow()
        executor = null
        synchronized(lock) {
            buckets.clear()
            consecutiveFailures = 0
        }
    }

    private fun ensureScheduled() {
        if (executor != null) return
        synchronized(this) {
            if (executor != null) return
            val scheduled = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "forgeops-tracker-performance-flusher").apply { isDaemon = true }
            }
            val intervalMillis = maxOf(1L, configuration.performanceFlushIntervalMillis)
            scheduled.scheduleWithFixedDelay(::flushSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
            executor = scheduled
        }
    }

    private fun flushSafely() {
        try {
            flush()
        } catch (e: RuntimeException) {
            // One bad flush must not kill every flush after it: a scheduled task that throws is
            // cancelled by ScheduledExecutorService, so this is what keeps the schedule alive.
            configuration.log("performance flush error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        /** A batch that has failed this many flushes in a row is dropped rather than carried further. */
        const val MAX_CONSECUTIVE_FAILURES = 5
    }
}

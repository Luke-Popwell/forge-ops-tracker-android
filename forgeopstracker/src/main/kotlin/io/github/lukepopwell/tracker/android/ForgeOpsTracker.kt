package io.github.lukepopwell.tracker.android

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point. Typical usage, in your `Application.onCreate()`:
 *
 *     ForgeOpsTracker.init(this) { config ->
 *         config.dsn = "https://<api_key>@getforgeops.net/api/v1/events"
 *         config.environment = "production"
 *     }
 *
 * `init` takes a [Context] (an `Application` is the obvious choice, but any `Context` works,
 * only `applicationContext` is ever retained, never the `Context` passed in itself, so this can't
 * leak an `Activity`) since several `Configuration` fields need one to derive sensible defaults
 * (the app's own package name, its files directory): see `Configuration`'s own comments.
 *
 * See README.md for what gets captured automatically (an uncaught exception, an ANR) versus what
 * needs an explicit [captureException] call, and why a report always uploads on the *next* launch
 * rather than live during the crash itself.
 */
object ForgeOpsTracker {
    private val lock = Any()

    // Set once the "not sending from this environment" warning has been logged, so it's logged
    // once per process.
    private val notSendingWarned = AtomicBoolean()

    @Volatile private var configuration: Configuration? = null

    @Volatile private var reporter: Reporter? = null
    private var anrWatchdog: AnrWatchdog? = null
    private var performanceFlusher: PerformanceFlusher? = null
    @Volatile private var spanQueue: SpanQueue? = null
    @Volatile private var metricBuffer: MetricBuffer? = null
    @Volatile private var infrastructureMetricBuffer: MetricBuffer? = null
    @Volatile private var changeQueue: ChangeQueue? = null
    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
    private var handlersInstalled = false

    // The affected user set via setUser below, if any. @Volatile, not a full thread-local: a
    // mobile app install is effectively single-user (unlike a server handling many concurrent
    // requests at once, the reason gems/forge_ops_tracker needs Thread.current instead), so
    // cross-thread visibility is the only real concern, the same level of care every other
    // @Volatile field in this object already gets.
    @Volatile internal var currentUser: Map<String, Any?>? = null
        private set

    // The shared breadcrumb trail: see BreadcrumbBuffer for why one shared trail, not a
    // thread-local. Created by init, alongside the configuration it reads.
    @Volatile private var breadcrumbBuffer: BreadcrumbBuffer? = null

    /** The trail as it stands right now, read by the uncaught-exception handler, the ANR watchdog,
     * and [captureException]. */
    internal val currentBreadcrumbs: List<Map<String, Any?>>
        get() = breadcrumbBuffer?.all() ?: emptyList()

    // Every trace started and not yet finished, oldest first. A trace is an explicit object rather
    // than per-thread state (see Trace), so "the trace an error belongs to" is the most recently
    // started one still open: the same one-app, one-flow-at-a-time reasoning currentUser above uses.
    private val openTraces = ArrayList<Trace>()
    private const val MAX_OPEN_TRACES = 32

    /** The most recently started trace that hasn't finished, if any: what an error captured right now belongs to. */
    internal val currentTrace: Trace?
        get() = synchronized(openTraces) { openTraces.lastOrNull() }

    // Uploads run here rather than on the calling thread: HttpURLConnection blocks, and neither
    // installHandlers (called from Application.onCreate, on the main thread) nor a host app's own
    // explicit uploadPendingReports call should have to know that.
    private val uploadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "forgeops-tracker-upload").apply { isDaemon = true }
    }

    fun init(context: Context, configure: (Configuration) -> Unit = {}): Configuration = synchronized(lock) {
        val config = configuration ?: Configuration(context.applicationContext).also { configuration = it }
        configure(config)
        reporter = Reporter(config)
        performanceFlusher?.discard()
        performanceFlusher = PerformanceFlusher(config, Client(config))
        spanQueue?.discard()
        spanQueue = SpanQueue(Client(config))
        changeQueue?.discard()
        changeQueue = ChangeQueue(Client(config))
        metricBuffer?.discard()
        infrastructureMetricBuffer?.discard()
        val metricClient = Client(config)
        metricBuffer = MetricBuffer(config, { config.metricFlushIntervalMillis }, metricClient::metricsStatus)
        infrastructureMetricBuffer =
            MetricBuffer(config, { config.infrastructureMetricFlushIntervalMillis }, metricClient::infrastructureMetricsStatus)
        breadcrumbBuffer = breadcrumbBuffer ?: BreadcrumbBuffer(config)
        warnIfNotSending(config, notSendingWarned)
        config
    }

    /**
     * Logs [Configuration.notSendingWarning] to Logcat (tag `ForgeOps`, as a warning) at most once
     * per process (tracked by [warned]): a DSN that quietly sends nothing is the one setup
     * mistake a new install can't otherwise see. Silent without a DSN, and never throws into the
     * caller.
     */
    internal fun warnIfNotSending(config: Configuration, warned: AtomicBoolean) {
        try {
            val warning = config.notSendingWarning ?: return
            if (!warned.compareAndSet(false, true)) return
            Log.w("ForgeOps", "[ForgeOps] $warning")
        } catch (e: RuntimeException) {
            // Logging is never a reason to fail init.
        }
    }

    /**
     * Installs the uncaught-exception handler and/or ANR watchdog (per
     * [Configuration.installUncaughtExceptionHandler]/[Configuration.installAnrWatchdog], both on
     * by default), then uploads any reports left over from a previous launch on a background
     * thread. Call once, after [init]. Safe to call more than once: later calls are a no-op.
     */
    fun installHandlers() = synchronized(lock) {
        if (handlersInstalled) return
        handlersInstalled = true

        val config = requireConfiguration()
        val activeReporter = requireReporter()

        if (config.installUncaughtExceptionHandler) {
            previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                activeReporter.reportThrowable(error, mapOf("thread" to thread.name), currentUser, currentBreadcrumbs, currentTraceId())
                // Chain to whatever handler (if any) was already installed: another crash
                // reporter, or the host app's own: rather than silently replacing it, the same
                // "rethrow, don't swallow" invariant every other framework integration in this
                // repo holds to. Reproduces the JVM's own default stderr output if there wasn't
                // one, so installing this changes nothing observable besides also reporting it.
                val previous = previousUncaughtExceptionHandler
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    System.err.println("Exception in thread \"${thread.name}\" ${stackTraceString(error)}")
                }
            }
        }

        if (config.installAnrWatchdog) {
            val watchdog = AnrWatchdog(config, activeReporter)
            watchdog.start()
            anrWatchdog = watchdog
        }

        uploadExecutor.execute { activeReporter.uploadPendingReports() }
    }

    /**
     * Report an exception you've already caught. [user] defaults to whatever [setUser] last
     * set, if anything; pass one explicitly to override that for this one report. [trace] defaults
     * to the most recently started trace that hasn't finished yet (see [currentTraceId]); its id goes
     * on the report as `trace_id`, linking this error to one your backend reported for the same
     * request. Pass the trace explicitly when several overlap, or `null` to leave it off.
     */
    fun captureException(error: Throwable, context: Map<String, Any?> = emptyMap(), user: Map<String, Any?>? = currentUser, trace: Trace? = currentTrace) {
        val activeReporter = requireReporter()
        activeReporter.reportThrowable(error, context, user, currentBreadcrumbs, trace?.traceId)
        // Unlike an uncaught exception/ANR, this one didn't crash the process: upload now
        // rather than waiting for a next launch that (having not crashed) has no particular
        // reason to come soon. Still off the calling thread, for the same reason as
        // installHandlers above.
        uploadExecutor.execute { activeReporter.uploadPendingReports() }
    }

    /**
     * Manually attaches an affected user to whatever gets reported from here on (an explicit
     * [captureException] call, an uncaught exception, an ANR): there's no way to automatically
     * detect "the current user" on Android, so call this yourself, e.g. right after sign-in.
     * `id`/`email`/`username` are all independently optional; call with an empty map (or `null`)
     * to clear whatever was set, e.g. on sign-out.
     */
    fun setUser(user: Map<String, Any?>?) = synchronized(lock) {
        currentUser = if (user.isNullOrEmpty()) null else user
    }

    /**
     * Records one breadcrumb: an entry in a small, bounded trail of recent events attached to
     * whatever gets reported next (an explicit [captureException] call, an uncaught exception, or
     * an ANR), so an issue's detail page can show what led up to it. [category]/[level] default to
     * `"custom"`/`"info"`; [data] is any small map of extra detail. Only the most recent
     * [Configuration.maxBreadcrumbs] (30) are kept, oldest dropped first; a no-op when
     * [Configuration.trackBreadcrumbs] is false, or when called before [init] (a breadcrumb call
     * should never be the thing that throws into your app). Safe to call from any thread.
     *
     * Nothing records one automatically: there's no request/controller lifecycle in a crash
     * reporter to time one from, so every breadcrumb is one you add by hand, wherever it's
     * meaningful (a screen opening, a network call starting):
     *
     *     ForgeOpsTracker.addBreadcrumb("charging card", category = "payment", data = mapOf("orderId" to order.id))
     */
    fun addBreadcrumb(message: String, category: String = "custom", level: String = "info", data: Map<String, Any?> = emptyMap()) {
        breadcrumbBuffer?.add(message, category, level, data)
    }

    /** Empties the breadcrumb trail. A mobile app is effectively single-flow (one shared trail,
     * like [setUser]'s one shared user), so this is only needed to start a new logical unit of
     * work (say, a new sign-in session) with a fresh trail. */
    fun clearBreadcrumbs() {
        breadcrumbBuffer?.clear()
    }

    /**
     * Records one timed call's duration, in milliseconds, under [transactionName]: tallied
     * in-process (count, total, max) and flushed every [Configuration.performanceFlushIntervalMillis]
     * (60s) as one small aggregate report per transaction, for the Performance page's
     * per-transaction table, not one network call per call. A no-op when
     * [Configuration.trackPerformance] is `false` or reporting isn't enabled for this environment,
     * and before [init]. Safe to call from any thread.
     *
     * This client has no web framework integration, so nothing is timed automatically: wrap whatever
     * you want on the Performance page yourself, with [timeTransaction], or call this directly with
     * a duration you measured. Keep [transactionName] low-cardinality (`"HomeScreen.load"`, not one
     * per item id): every distinct name is its own row.
     */
    fun recordPerformance(transactionName: String, durationMs: Double) {
        performanceFlusher?.record(transactionName, durationMs)
    }

    /**
     * Runs [block], records how long it took under [transactionName] (see [recordPerformance]), and
     * returns whatever [block] returned. Recorded even if [block] throws: the duration up to the
     * throw is still a real duration, and a call that fails is exactly one worth seeing on the
     * Performance page.
     *
     *     val user = ForgeOpsTracker.timeTransaction("HomeScreen.load") { repository.loadUser() }
     */
    inline fun <T> timeTransaction(transactionName: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            recordPerformance(transactionName, (System.nanoTime() - startedAt) / 1_000_000.0)
        }
    }

    /**
     * Delivers whatever has been tallied so far, on the upload background thread (never the caller's:
     * an Android app's main thread must not do network I/O). A schedule flushes on its own every
     * [Configuration.performanceFlushIntervalMillis] while the process lives, but the OS can kill it
     * at any moment and nothing is flushed then (a JVM shutdown hook does not run for that), so call
     * this from `Activity.onStop` (or wherever the app is about to go to the background) or the last
     * window is lost. See [flushPerformanceSync] for a version that blocks until delivery finishes.
     */
    fun flushPerformance() {
        val flusher = performanceFlusher ?: return
        uploadExecutor.execute { flusher.flush() }
    }

    /** Same as [flushPerformance], on the calling thread: for a host app that wants its own control over scheduling (a WorkManager job, say). */
    fun flushPerformanceSync() {
        performanceFlusher?.flush()
    }

    /**
     * Custom metrics and infrastructure monitoring: two explicit calls (nothing is automatic, so there
     * is no `trackMetrics` flag). [captureMetric] records a named business event (a signup, a payment,
     * anything you want to name): [value] defaults to 1.0 for a bare counter; pass one for a real
     * magnitude, and it may be negative (a refund). [captureInfrastructureMetric] records one reading
     * (cpu, memory, disk, anything else a program of yours reads) from one of your own hosts;
     * [hostname] defaults to [Configuration.serverName]. Both are buffered and flushed as one batch
     * every [Configuration.metricFlushIntervalMillis]/[Configuration.infrastructureMetricFlushIntervalMillis]
     * (60s) on a daemon scheduled executor, never the caller's thread. Every entry is stored as
     * captured (a signup is a row, not a running total), so a count or sum computed later is exact.
     * Both are a no-op when reporting isn't enabled for this environment or before [init], and a NaN or
     * infinite value is dropped. A buffer holds at most [MetricBuffer.MAX_ENTRIES] entries and drops
     * further ones until a flush succeeds; a delivery that failed for now keeps every entry (for up to
     * five flushes in a row, then the batch is dropped), a batch the server rejects is dropped at
     * once, and an entry captured while a delivery is in flight is kept.
     *
     *     ForgeOpsTracker.captureMetric("signup")
     *     ForgeOpsTracker.captureMetric("payment", 49.0)
     *     ForgeOpsTracker.captureInfrastructureMetric("battery", 0.42)
     */
    fun captureMetric(name: String, value: Double = 1.0) {
        val config = configuration ?: return
        if (!config.isEnabled) return
        metricBuffer?.record(
            JSONObject()
                .put("metric_name", name)
                .put("value", value)
                .put("environment", config.environment)
                .put("release", config.release ?: JSONObject.NULL),
            value,
        )
    }

    fun captureInfrastructureMetric(name: String, value: Double, hostname: String? = null) {
        val config = configuration ?: return
        if (!config.isEnabled) return
        infrastructureMetricBuffer?.record(
            JSONObject()
                .put("metric_name", name)
                .put("value", value)
                .put("hostname", hostname ?: config.serverName ?: ""),
            value,
        )
    }

    /**
     * Delivers every buffered metric and infrastructure reading, on the upload background thread (never
     * the caller's: an Android app's main thread must not do network I/O). The OS can kill the process
     * at any moment and nothing is flushed then, so call this from `Activity.onStop` (or wherever the
     * app is about to go to the background). See [flushMetricsSync] for a version that blocks.
     */
    fun flushMetrics() {
        val custom = metricBuffer
        val infrastructure = infrastructureMetricBuffer
        uploadExecutor.execute {
            custom?.flush()
            infrastructure?.flush()
        }
    }

    /** Same as [flushMetrics], on the calling thread: for a host app that wants its own control over scheduling (a WorkManager job, say). */
    fun flushMetricsSync() {
        metricBuffer?.flush()
        infrastructureMetricBuffer?.flush()
    }

    /**
     * Records one change to what the app is running: a feature flag flipped, a remote config value
     * updated, anything that could explain a shift in crashes or errors. ForgeOps shows it on the
     * timeline next to the errors around it. [kind] is one of [Change.KINDS] (anything else is sent
     * as `"other"`); [title] is required and cut to 200 characters. [details] is a small JSON-shaped
     * map, [environment] defaults to [Configuration.environment], [url] must be http(s), and [id] is
     * an idempotency key.
     *
     * Returns immediately: the change is sent on a private daemon thread, never the caller's (so it is
     * safe to call from the main thread), and nothing here ever throws, whether the request fails or
     * the plan doesn't include change tracking. A no-op when reporting isn't enabled or before [init].
     *
     *     remoteConfig.addOnConfigUpdateListener { keys ->
     *         ForgeOpsTracker.recordChange("config", "remote config updated", mapOf("keys" to keys.toList()))
     *     }
     */
    fun recordChange(
        kind: String,
        title: String,
        details: Map<String, Any?>? = null,
        environment: String? = null,
        service: String? = null,
        actor: String? = null,
        url: String? = null,
        id: String? = null,
        occurredAt: java.util.Date? = null,
    ) {
        try {
            val config = configuration ?: return
            val queue = changeQueue ?: return
            if (!config.isEnabled) return
            val payload = Change.payload(config, kind, title, details, environment, service, actor, url, id, occurredAt)
            if (payload == null) {
                config.log("dropped a change with no title")
                return
            }
            queue.push(payload)
        } catch (e: Exception) {
            configuration?.log("recordChange failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Blocks until every change recorded so far has been sent: call it from a background thread, never
     * the main thread. The OS can kill the process at any moment and nothing is flushed then.
     */
    fun flushChanges() {
        changeQueue?.waitUntilDelivered()
    }

    /**
     * Distributed tracing: one flow's own call tree (a screen load, a sign-in, a network round trip
     * and what it triggered), sent to ForgeOps only when the whole thing took at least
     * [Configuration.traceCaptureThresholdMillis] (1s), so fast flows cost nothing on the wire. Make
     * each request with [Trace.measureHttpSpan] or [Trace.startHttpSpan] and your backend continues the
     * trace from their `traceparent` header; errors captured while a trace is open carry its id.
     *
     *     ForgeOpsTracker.trace("HomeScreen.load") { trace ->
     *         val feed = trace.measureSpan("fetch feed", kind = "http") { api.fetchFeed() }
     *         trace.measureSpan("decode", data = mapOf("items" to feed.size)) { decode(feed) }
     *     }
     *
     * Or hold the trace yourself across threads and finish it when the flow ends:
     *
     *     val trace = ForgeOpsTracker.startTrace("Checkout.pay")
     *     ...                       // any thread: trace.measureSpan("charge") { ... }
     *     trace.finish()
     *
     * Returns null when reporting isn't enabled for this environment, or before [init]; with
     * [Configuration.trackTracing] false it returns a trace that records nothing but still has an id
     * for errors and the `traceparent` header. [Trace.measureSpan], [Trace.recordSpan],
     * [Trace.measureHttpSpan], [Trace.startHttpSpan] and [Trace.finish] are also defined on the
     * nullable, so callers never null-check. `kind` is one of controller, service,
     * database, redis, http, job, other (anything else is sent as `"other"`). This client has no web
     * framework integration, so nothing starts a trace or records a span automatically. Delivery runs
     * on a private daemon thread, bounded, never the caller's.
     */
    fun startTrace(name: String): Trace? {
        val config = configuration ?: return null
        val queue = spanQueue ?: return null
        if (!config.isEnabled) return null
        val trace = Trace(name, config, recording = config.trackTracing, onFinish = { finished -> synchronized(openTraces) { openTraces.remove(finished) } }) { payload ->
            queue.push(payload)
        }
        synchronized(openTraces) {
            openTraces.add(trace)
            // A trace that is never finished would otherwise be held forever; the oldest goes first.
            if (openTraces.size > MAX_OPEN_TRACES) openTraces.removeAt(0)
        }
        return trace
    }

    /** The id of the most recently started trace that hasn't finished (32 lowercase hex characters), or null. */
    fun currentTraceId(): String? = currentTrace?.traceId

    /**
     * Runs [block] with a new trace and finishes it afterward, even if [block] throws (the error
     * propagates unchanged). [block] receives null when tracing is off.
     */
    inline fun <T> trace(name: String, block: (Trace?) -> T): T {
        val trace = startTrace(name)
        try {
            return block(trace)
        } finally {
            trace.finish()
        }
    }

    /**
     * Delivers every finished trace that is still queued, blocking the calling thread until it is
     * done: an Android app's main thread must not do network I/O, so call this from a background
     * thread, `Activity.onStop` handing off to one, or a WorkManager job. The OS can kill the process
     * at any moment and nothing is flushed then.
     */
    fun flushSpans() {
        spanQueue?.waitUntilDelivered()
    }

    /** Uploads every pending report immediately, on the calling thread. Exposed for a host app
     * that wants its own control over scheduling (e.g. only on Wi-Fi, or on a WorkManager job):
     * [installHandlers] and [captureException] already trigger this on their own background
     * thread for the common case. */
    fun uploadPendingReportsSync() {
        requireReporter().uploadPendingReports()
    }

    private fun requireConfiguration(): Configuration =
        configuration ?: throw IllegalStateException("ForgeOpsTracker.init(context) must be called before installHandlers()")

    private fun requireReporter(): Reporter =
        reporter ?: throw IllegalStateException("ForgeOpsTracker.init(context) must be called first")

    private fun stackTraceString(error: Throwable): String {
        val writer = StringWriter()
        error.printStackTrace(PrintWriter(writer))
        return writer.toString().trimEnd()
    }

    /** Not part of the public API: resets module state between test cases, the same
     * `resetForTesting` convention every other client in this repo has. */
    fun resetForTesting() = synchronized(lock) {
        if (handlersInstalled) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtExceptionHandler)
            anrWatchdog?.stop()
        }
        configuration = null
        reporter = null
        anrWatchdog = null
        previousUncaughtExceptionHandler = null
        handlersInstalled = false
        currentUser = null
        breadcrumbBuffer = null
        synchronized(openTraces) { openTraces.clear() }
        performanceFlusher?.discard()
        performanceFlusher = null
        spanQueue?.discard()
        spanQueue = null
        changeQueue?.discard()
        changeQueue = null
        metricBuffer?.discard()
        infrastructureMetricBuffer?.discard()
        metricBuffer = null
        infrastructureMetricBuffer = null
    }
}

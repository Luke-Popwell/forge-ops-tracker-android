package io.github.lukepopwell.tracker.android

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One trace: a tree of timed spans (a screen load, a sign-in flow, one network round trip and what
 * it triggered) that is sent to ForgeOps only when the whole thing took at least
 * [Configuration.traceCaptureThresholdMillis] (1s), so fast flows cost nothing on the wire. Start one
 * with [ForgeOpsTracker.startTrace] or [ForgeOpsTracker.trace], never directly.
 *
 * Unlike the server SDKs, where one request owns one thread, an Android flow hops between the main
 * thread and background threads or coroutines, so a trace is an explicit object you pass around (or
 * capture in a lambda) rather than ambient per-thread state, and it is safe to use from any thread.
 * Nesting is tracked per thread: a span opened by [measureSpan] becomes the parent of any span
 * recorded on the same thread inside its block, and a span recorded from another thread parents under
 * the root.
 *
 * Kinds are the closed set the ingestion API accepts (controller, service, database, redis, http,
 * job, other): anything else is sent as `"other"`, since one bad kind would make the server reject
 * the whole trace. A trace holds at most [MAX_SPANS] spans including the root.
 *
 * [traceId] is W3C shaped (32 lowercase hex characters) and goes out to your backend in the
 * `traceparent` header [startHttpSpan]/[measureHttpSpan] produce, and on every error captured while
 * this trace is open. When [Configuration.trackTracing] is off, [recording] is false: spans are
 * neither kept nor sent, but the id still does both of those jobs, since it is also what links an
 * error here to one in the backend.
 */
class Trace internal constructor(
    private val name: String,
    private val configuration: Configuration,
    private val recording: Boolean = true,
    private val onFinish: (Trace) -> Unit = {},
    private val deliver: (JSONObject) -> Unit,
) {
    val traceId: String = TraceParent.generateTraceId()
    private val rootSpanId = TraceParent.generateSpanId()
    private val startedAtMillis = System.currentTimeMillis()
    private val timerNanos = System.nanoTime()
    private val lock = Any()
    private val spans = ArrayList<JSONObject>()
    private var finished = false
    private val openSpans = ThreadLocal<ArrayList<String>>()

    /**
     * Times [block] as a span (a child of whatever span is open on this thread, or of the root),
     * recording it even if [block] throws, and returns whatever [block] returned.
     */
    inline fun <T> measureSpan(name: String, kind: String = "service", data: Map<String, Any?>? = null, block: () -> T): T {
        val id = openSpan()
        val parent = parentOf(id)
        val startedAt = System.currentTimeMillis()
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            closeSpan(id, parent, name, kind, startedAt, (System.nanoTime() - started) / 1_000_000.0, data)
        }
    }

    /**
     * Same as [measureSpan], for a `"database"` span that also carries the SQL it ran (a local
     * SQLite query, say) and which database it was ([dbSystem], such as `"sqlite"`): sent in the
     * span's data as `db.statement`, with every string and number literal replaced by `?` first (so
     * values never leave the device) and cut at 4000 characters, and `db.system`, lowercased. Both
     * are ignored on any other kind.
     *
     *     val rows = trace.measureSpan("Load messages", "database", statement = sql, dbSystem = "sqlite") { db.rawQuery(sql, null) }
     */
    inline fun <T> measureSpan(
        name: String,
        kind: String = "service",
        data: Map<String, Any?>? = null,
        statement: String?,
        dbSystem: String? = null,
        block: () -> T,
    ): T = measureSpan(name, kind, withSql(kind, data, statement, dbSystem), block)

    /** Records a span you timed yourself, under whatever is open on this thread (or the root). */
    fun recordSpan(name: String, kind: String = "service", startedAtMillis: Long, durationMs: Double, data: Map<String, Any?>? = null) {
        store(build(TraceParent.generateSpanId(), currentParent(), name, kind, startedAtMillis, durationMs, data))
    }

    /** Same as [recordSpan], with a `"database"` span's SQL and database system, sent as described on the [measureSpan] that takes them. */
    fun recordSpan(
        name: String,
        kind: String = "service",
        startedAtMillis: Long,
        durationMs: Double,
        data: Map<String, Any?>? = null,
        statement: String?,
        dbSystem: String? = null,
    ) {
        recordSpan(name, kind, startedAtMillis, durationMs, withSql(kind, data, statement, dbSystem))
    }

    // Adds a "database" span's statement and system to its data, still raw: build() below masks
    // every database span's db.statement (one passed in data directly included) before it is kept.
    @PublishedApi internal fun withSql(kind: String, data: Map<String, Any?>?, statement: String?, dbSystem: String?): Map<String, Any?>? {
        if (kind != "database" || (statement == null && dbSystem == null)) return data
        val result = LinkedHashMap(data ?: emptyMap())
        if (statement != null) result["db.statement"] = statement
        if (dbSystem != null) result["db.system"] = dbSystem
        return result
    }

    /**
     * Times a blocking HTTP call made by [block] as an `http` span named `"<METHOD> <host>"` (never
     * the path or query, which can carry ids or tokens), handing [block] the headers to add to that
     * request: a `traceparent` whose parent id is this span's own id, so the backend's root span nests
     * under it. Empty when [Configuration.propagateTraces] is off or the host isn't in
     * [Configuration.tracePropagationTargets]. Recorded even if [block] throws, which is rethrown
     * unchanged. For a callback-style client, use [startHttpSpan] instead.
     *
     *     val response = trace.measureHttpSpan("POST", url) { headers ->
     *         val connection = URL(url).openConnection() as HttpURLConnection
     *         headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
     *         connection.inputStream.use { it.readBytes() }
     *     }
     */
    inline fun <T> measureHttpSpan(method: String, url: String, data: Map<String, Any?>? = null, block: (headers: Map<String, String>) -> T): T {
        val id = openSpan(TraceParent.generateSpanId())
        val parent = parentOf(id)
        val host = hostOf(url)
        val startedAt = System.currentTimeMillis()
        val started = System.nanoTime()
        try {
            return block(headersFor(id, host))
        } finally {
            closeSpan(id, parent, httpSpanName(method, host), "http", startedAt, (System.nanoTime() - started) / 1_000_000.0, data)
        }
    }

    /**
     * Starts timing an HTTP call you finish yourself, for a client that calls back on another thread
     * (an OkHttp `enqueue`, Retrofit's `Callback`, Volley): add [HttpSpan.headers] to the request, then
     * call [HttpSpan.finish] from its success and failure callbacks. The span is parented under
     * whatever is open on this thread right now (or the root); nothing started on the callback's
     * thread nests under it.
     *
     *     val span = trace.startHttpSpan("POST", url)
     *     val request = Request.Builder().url(url).apply { span.headers.forEach { (k, v) -> header(k, v) } }.post(body).build()
     *     client.newCall(request).enqueue(object : Callback {
     *         override fun onResponse(call: Call, response: Response) { span.finish(mapOf("status" to response.code)); ... }
     *         override fun onFailure(call: Call, e: IOException) { span.finish(); ... }
     *     })
     */
    fun startHttpSpan(method: String, url: String): HttpSpan {
        val id = TraceParent.generateSpanId()
        val host = hostOf(url)
        return HttpSpan(this, id, currentParent(), httpSpanName(method, host), headersFor(id, host))
    }

    /** The trace's `traceparent` headers naming [spanId] as the parent, or none when [host] shouldn't get them. */
    @PublishedApi internal fun headersFor(spanId: String, host: String?): Map<String, String> =
        if (configuration.shouldPropagateTrace(host)) mapOf(TraceParent.HEADER to TraceParent.build(traceId, spanId)) else emptyMap()

    @PublishedApi internal fun hostOf(url: String): String? = try {
        java.net.URI(url).host
    } catch (e: java.net.URISyntaxException) {
        null
    }

    @PublishedApi internal fun httpSpanName(method: String, host: String?): String = "${method.uppercase(Locale.US)} ${host ?: "unknown"}"

    internal fun recordHttpSpan(id: String, parent: String, name: String, startedAtMillis: Long, durationMs: Double, data: Map<String, Any?>?) {
        store(build(id, parent, name, "http", startedAtMillis, durationMs, data))
    }

    /**
     * Ends the trace and sends it if the root took long enough. Idempotent: a second call does
     * nothing, and spans recorded after it are dropped.
     */
    fun finish() {
        val recorded: List<JSONObject>
        synchronized(lock) {
            if (finished) return
            finished = true
            recorded = ArrayList(spans)
        }
        onFinish(this)
        if (!recording) return

        val durationMs = (System.nanoTime() - timerNanos) / 1_000_000.0
        if (durationMs < configuration.traceCaptureThresholdMillis) return

        val all = org.json.JSONArray()
        all.put(build(rootSpanId, null, name, "controller", startedAtMillis, durationMs, null))
        recorded.forEach { all.put(it) }
        deliver(JSONObject().put("trace_id", traceId).put("spans", all))
    }

    @PublishedApi internal fun openSpan(): String = openSpan(TraceParent.generateSpanId())

    @PublishedApi internal fun openSpan(id: String): String {
        var stack = openSpans.get()
        if (stack == null) {
            stack = ArrayList()
            openSpans.set(stack)
        }
        stack.add(id)
        return id
    }

    // The parent of a span just opened: whatever was open beneath it on this thread, or the root.
    @PublishedApi internal fun parentOf(id: String): String {
        val stack = openSpans.get() ?: return rootSpanId
        val index = stack.lastIndexOf(id)
        return if (index > 0) stack[index - 1] else rootSpanId
    }

    @PublishedApi internal fun closeSpan(id: String, parent: String, name: String, kind: String, startedAtMillis: Long, durationMs: Double, data: Map<String, Any?>?) {
        openSpans.get()?.remove(id)
        store(build(id, parent, name, kind, startedAtMillis, durationMs, data))
    }

    private fun currentParent(): String = openSpans.get()?.lastOrNull() ?: rootSpanId

    private fun store(span: JSONObject) {
        synchronized(lock) {
            if (recording && !finished && spans.size < MAX_SPANS - 1) spans.add(span) // leave room for the root
        }
    }

    // Strict privacy: an outbound call is named by its method and host only, never a path that could
    // name a person, and data keeps only the masked statement and the database system.
    private fun build(id: String, parent: String?, name: String, kind: String, startedAtMillis: Long, durationMs: Double, data: Map<String, Any?>?): JSONObject =
        JSONObject()
            .put("span_id", id)
            .put("parent_span_id", parent ?: JSONObject.NULL)
            .put("name", if (configuration.strictPrivacy) StrictPrivacy.spanName(name) else name)
            .put("kind", if (kind in KINDS) kind else "other")
            .put("started_at", timestamp(startedAtMillis))
            .put("duration_ms", Math.round(durationMs * 100.0) / 100.0)
            .put("environment", configuration.environment)
            .put("release", configuration.release ?: JSONObject.NULL)
            .put("data", JSONObject(sentData(kind, data)?.mapValues { it.value ?: JSONObject.NULL } ?: emptyMap<String, Any>()))

    private fun sentData(kind: String, data: Map<String, Any?>?): Map<String, Any?>? {
        val masked = spanData(kind, data)
        return if (configuration.strictPrivacy && masked != null) StrictPrivacy.spanData(masked) else masked
    }

    companion object {
        const val MAX_SPANS = 500

        /**
         * A span's data, with a `"database"` span's `db.statement` masked (cut at 4000 characters)
         * and its `db.system` lowercased, so raw SQL can never go out on a span.
         */
        internal fun spanData(kind: String, data: Map<String, Any?>?): Map<String, Any?>? {
            if (kind != "database" || data == null) return data
            val result = LinkedHashMap(data)
            val raw = result.remove("db.statement") as? String
            val system = (result.remove("db.system") as? String)?.trim()
            SqlStatement.mask(raw, system)?.let { result["db.statement"] = it }
            if (!system.isNullOrEmpty()) result["db.system"] = system.lowercase(Locale.US)
            return result
        }
        private val KINDS = setOf("controller", "service", "database", "redis", "http", "job", "other")

        // java.time needs API 26 and this library's minSdk is 21, so SimpleDateFormat; one per thread
        // since SimpleDateFormat isn't thread-safe and spans finish on whichever thread ran them.
        private val FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        internal fun timestamp(millis: Long): String = FORMAT.get()!!.format(Date(millis))
    }
}

/**
 * [ForgeOpsTracker.startTrace] returns null when tracing is off or reporting isn't enabled, so these
 * forward to the block unchanged in that case: callers never need a null check.
 */
inline fun <T> Trace?.measureSpan(name: String, kind: String = "service", data: Map<String, Any?>? = null, block: () -> T): T =
    if (this != null) measureSpan(name, kind, data, block) else block()

fun Trace?.recordSpan(name: String, kind: String = "service", startedAtMillis: Long, durationMs: Double, data: Map<String, Any?>? = null) {
    this?.recordSpan(name, kind, startedAtMillis, durationMs, data)
}

inline fun <T> Trace?.measureSpan(
    name: String,
    kind: String = "service",
    data: Map<String, Any?>? = null,
    statement: String?,
    dbSystem: String? = null,
    block: () -> T,
): T = if (this != null) measureSpan(name, kind, data, statement, dbSystem, block) else block()

fun Trace?.recordSpan(
    name: String,
    kind: String = "service",
    startedAtMillis: Long,
    durationMs: Double,
    data: Map<String, Any?>? = null,
    statement: String?,
    dbSystem: String? = null,
) {
    this?.recordSpan(name, kind, startedAtMillis, durationMs, data, statement, dbSystem)
}

fun Trace?.finish() {
    this?.finish()
}

/** Runs [block] with no headers when there is no trace, so callers never need a null check. */
inline fun <T> Trace?.measureHttpSpan(method: String, url: String, data: Map<String, Any?>? = null, block: (headers: Map<String, String>) -> T): T =
    if (this != null) measureHttpSpan(method, url, data, block) else block(emptyMap())

/** A no-op [HttpSpan] with no headers when there is no trace. */
fun Trace?.startHttpSpan(method: String, url: String): HttpSpan = this?.startHttpSpan(method, url) ?: HttpSpan.NONE

/**
 * One outgoing HTTP call started with [Trace.startHttpSpan]: add [headers] to the request, then call
 * [finish] once it completes or fails. Safe to finish from any thread; only the first call counts.
 */
class HttpSpan internal constructor(
    private val trace: Trace?,
    /** This span's own id, which [traceparent] names as the backend's parent. */
    val spanId: String?,
    private val parentSpanId: String?,
    private val name: String,
    /** The headers to add to the request: `traceparent`, or empty when it shouldn't be sent. */
    val headers: Map<String, String>,
) {
    private val startedAtMillis = System.currentTimeMillis()
    private val timerNanos = System.nanoTime()
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The `traceparent` header value, or null when none should be sent. */
    val traceparent: String?
        get() = headers[TraceParent.HEADER]

    /** Records the span with its duration so far; [data] is any small map of detail, a status code say. */
    fun finish(data: Map<String, Any?>? = null) {
        if (trace == null || spanId == null || parentSpanId == null || !finished.compareAndSet(false, true)) return
        trace.recordHttpSpan(spanId, parentSpanId, name, startedAtMillis, (System.nanoTime() - timerNanos) / 1_000_000.0, data)
    }

    companion object {
        internal val NONE = HttpSpan(null, null, null, "", emptyMap())
    }
}

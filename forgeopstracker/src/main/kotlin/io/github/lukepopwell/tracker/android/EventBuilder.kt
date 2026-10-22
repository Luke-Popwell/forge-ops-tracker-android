package io.github.lukepopwell.tracker.android

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a caught [Throwable]: or, for an ANR report, a raw main-thread [StackTraceElement]
 * array with no [Throwable] at all (see [AnrWatchdog]): into the JSON payload shape the
 * ingestion API expects. Uses `org.json.JSONObject`/`JSONArray`, part of the Android platform
 * itself since API 1 (not a Maven dependency, unlike every other JSON-less language in this
 * repo's own clients, which each had to hand-roll an encoder): see README.md's "Dependencies"
 * section.
 *
 * Backtrace parsing is the same structured [Throwable.getStackTrace] `sdks/kotlin`/`sdks/java`
 * already use, not a regex over a string: a Kotlin/Java exception on Android is still a JVM
 * `Throwable` under the hood.
 *
 * Also calls [attachSourceContext] for every frame: see that function's own doc comment for why
 * it's a deliberate, documented no-op on this platform rather than a real disk read.
 */
object EventBuilder {
    private const val MAX_FRAMES = 500

    // How many lines of source a real implementation would grab on either side of a frame's
    // culprit line, and the longest a single captured line would be allowed to be before getting
    // truncated: matches gems/forge_ops_tracker's own CONTEXT_LINES/MAX_CONTEXT_LINE_LENGTH.
    // Kept here, named consistently, purely for parity with every other SDK's constants even
    // though attachSourceContext below never actually reads a line on this platform: see that
    // function's own comment.
    private const val CONTEXT_LINES = 5
    private const val MAX_CONTEXT_LINE_LENGTH = 500

    // Identifies this client to the server's auto language-detection on the project the event
    // lands in (see Project#note_sdk_platform server-side); matches this repo's own sdks/android
    // directory name, the same convention every other language's client follows.
    private const val SDK_NAME = "android"
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun buildForThrowable(configuration: Configuration, error: Throwable, context: Map<String, Any?>?, user: Map<String, Any?>? = null, breadcrumbs: List<Map<String, Any?>> = emptyList(), traceId: String? = null): JSONObject =
        build(configuration, error.javaClass.name, error.message ?: "", error.stackTrace, context, user, breadcrumbs, traceId, error)

    /** For an ANR report: see [AnrWatchdog]'s own comment for why there's no [Throwable] to
     * build one from. */
    fun buildForAnr(configuration: Configuration, mainThreadStackTrace: Array<StackTraceElement>, user: Map<String, Any?>? = null, breadcrumbs: List<Map<String, Any?>> = emptyList(), traceId: String? = null): JSONObject =
        build(configuration, "ANR", "Application Not Responding: main thread blocked for at least ${configuration.anrThresholdMillis}ms", mainThreadStackTrace, null, user, breadcrumbs, traceId)

    private fun build(configuration: Configuration, exceptionClass: String, message: String, stackTrace: Array<StackTraceElement>, context: Map<String, Any?>?, user: Map<String, Any?>?, breadcrumbs: List<Map<String, Any?>>, traceId: String?, error: Throwable? = null): JSONObject {
        if (configuration.strictPrivacy) return strictBuild(configuration, exceptionClass, message, stackTrace, context, user, traceId, error)
        val payload = JSONObject()
        payload.put("exception_class", exceptionClass)
        payload.put("message", message)
        payload.put("backtrace", backtraceJson(configuration, stackTrace))
        payload.put("occurred_at", TIMESTAMP_FORMAT.format(Date()))
        payload.put("environment", configuration.environment)
        payload.put("release", configuration.release ?: JSONObject.NULL)
        payload.put("server_name", configuration.serverName ?: JSONObject.NULL)
        payload.put("context", JSONObject(context ?: emptyMap<String, Any?>()))
        payload.put("tags", JSONObject())
        payload.put("sdk_name", SDK_NAME)
        if (!user.isNullOrEmpty()) {
            payload.put("user", JSONObject(user))
        }
        // Omitted entirely (never sent as an empty array) when there's nothing to report.
        if (breadcrumbs.isNotEmpty()) {
            payload.put("breadcrumbs", JSONArray(breadcrumbs.map { JSONObject(it) }))
        }
        // The open trace's W3C id, so the server can link this error to one in your backend that
        // handled the same request. Left out entirely when no trace was open.
        if (traceId != null) {
            payload.put("trace_id", traceId)
        }
        attachSql(configuration, payload, error)

        return if (configuration.scrubPii) scrub(payload) else payload
    }

    /**
     * The same event under [Configuration.strictPrivacy]: no message (only its fingerprint), no
     * breadcrumbs or statement, the user's id only, and context/tags reduced to the allowed keys,
     * each left out entirely when nothing is left. An ANR's message is fingerprinted too, the same
     * as any other.
     */
    private fun strictBuild(configuration: Configuration, exceptionClass: String, message: String, stackTrace: Array<StackTraceElement>, context: Map<String, Any?>?, user: Map<String, Any?>?, traceId: String?, error: Throwable?): JSONObject {
        val allowedFields = configuration.privacyAllowedFields
        val payload = JSONObject()
        payload.put("exception_class", exceptionClass)
        payload.put("message", "")
        // Taken from the message as it would otherwise have been sent (scrubbed, when scrubbing is
        // on), so it matches what the server fingerprints from a client without strict privacy.
        payload.put("message_fingerprint", StrictPrivacy.messageFingerprint(if (configuration.scrubPii) PiiScrubber.scrub(message) as String else message))
        payload.put("backtrace", backtraceJson(configuration, stackTrace))
        payload.put("occurred_at", TIMESTAMP_FORMAT.format(Date()))
        payload.put("environment", configuration.environment)
        payload.put("release", configuration.release ?: JSONObject.NULL)
        payload.put("server_name", configuration.serverName ?: JSONObject.NULL)
        StrictPrivacy.allowed(context, allowedFields)?.let { payload.put("context", JSONObject(it)) }
        StrictPrivacy.allowed(emptyMap(), allowedFields)?.let { payload.put("tags", JSONObject(it)) }
        payload.put("sdk_name", SDK_NAME)
        StrictPrivacy.user(user)?.let { payload.put("user", JSONObject(it)) }
        if (traceId != null) {
            payload.put("trace_id", traceId)
        }
        attachSql(configuration, payload, error)

        return if (configuration.scrubPii) scrub(payload) else payload
    }

    // See SqlStatement for what's read off the error and how it's masked. The statement itself only
    // goes out when captureSqlStatement is on (and strict privacy is off); the extracted names go out on their own
    // (captureSqlObjects) so an issue can still name the table or view involved.
    private fun attachSql(configuration: Configuration, payload: JSONObject, error: Throwable?) {
        if (error == null || (!configuration.captureSqlObjects && !configuration.captureSqlStatement)) return

        val masked = SqlStatement.mask(SqlStatement.findIn(error)) ?: return
        val objects = SqlStatement.objects(masked)
        if (objects != null && configuration.captureSqlObjects) {
            val json = JSONObject()
            objects.forEach { (key, value) -> json.put(key, if (value is List<*>) JSONArray(value) else value) }
            payload.put("sql_objects", json)
        }
        if (configuration.captureSqlStatement && !configuration.strictPrivacy) payload.put("sql_statement", masked)
    }

    private fun backtraceJson(configuration: Configuration, stackTrace: Array<StackTraceElement>): JSONArray {
        val frames = JSONArray()
        for (element in stackTrace.take(MAX_FRAMES)) {
            val frame = JSONObject()
            frame.put("file", element.fileName ?: JSONObject.NULL)
            frame.put("line", element.lineNumber)
            frame.put("method", "${element.className}#${element.methodName}")
            val inApp = isInApp(configuration, element)
            frame.put("in_app", inApp)
            attachSourceContext(configuration, frame, element, inApp)
            frames.put(frame)
        }
        return frames
    }

    /** [StackTraceElement.getFileName] is always a bare file name (`"Foo.kt"`), never a full
     * path: same situation as `sdks/kotlin`/`sdks/java`, so this compares the frame's
     * declaring class package against [Configuration.packageName] instead (which, unlike those
     * two clients, defaults to a real value here: see that field's own comment). */
    private fun isInApp(configuration: Configuration, element: StackTraceElement): Boolean {
        val packageName = configuration.packageName
        if (packageName.isNullOrBlank()) return false
        return element.className.startsWith(packageName)
    }

    /**
     * Would attach `context_line`/`pre_context`/`post_context` (see
     * [Configuration.captureSourceContext]'s own doc comment) to [frame] the same way
     * `gems/forge_ops_tracker`'s Ruby EventBuilder, `sdks/unity`'s EventBuilder, and every other
     * ForgeOps SDK with a real on-device source tree do: read [CONTEXT_LINES] lines of source on
     * either side of the culprit line straight off disk, gated on
     * [Configuration.captureSourceContext] being on and [inApp] being true.
     *
     * **This is a deliberate, documented no-op on Android, always.** [StackTraceElement.getFileName]
     * on the JVM never returns anything but a bare file name (`"Foo.kt"`, see [isInApp]'s own
     * comment above) (never an absolute or otherwise resolvable path) so there is no third
     * condition ("a real, openable file path is actually available") this could ever satisfy. Nor
     * would treating that bare name as a relative path and attempting to open it anyway be honest:
     * even a debug build never packages `.java`/`.kt` source files onto the device at all (only
     * compiled, and typically R8/ProGuard-obfuscated, `.dex` bytecode ships), so there is no source
     * tree on the device this code runs on for any path, real or fabricated, to ever resolve
     * against. [element] and [inApp] are accepted only so this function's shape (and the one call
     * site above) mirror every other SDK's real implementation, keeping the option itself
     * (`Configuration.captureSourceContext`) meaningful as a piece of API surface even though this
     * particular platform can never act on it.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun attachSourceContext(configuration: Configuration, frame: JSONObject, element: StackTraceElement, inApp: Boolean) {
        // No-op: see this function's own doc comment above.
    }

    // exception_class/occurred_at/environment/release/server_name/sdk_name/user/trace_id are left alone:
    // structured fields this client sets deliberately, not free text an error or its context
    // could accidentally spill sensitive data into. user specifically is a deliberate exemption,
    // not an oversight: PiiScrubber's own email pattern would otherwise redact the exact thing
    // this field exists to carry (this function never touches that key at all).
    private fun scrub(payload: JSONObject): JSONObject {
        payload.put("message", PiiScrubber.scrub(payload.getString("message")) as String)

        val backtrace = payload.getJSONArray("backtrace")
        for (i in 0 until backtrace.length()) {
            val frame = backtrace.getJSONObject(i)
            if (frame.opt("file") is String) frame.put("file", PiiScrubber.scrub(frame.getString("file")) as String)
            frame.put("method", PiiScrubber.scrub(frame.getString("method")) as String)
        }

        // A breadcrumb's message and data are free text the host app wrote; its category, level,
        // and timestamp are structured values set deliberately, so they are left alone, the same
        // split as the top-level fields above.
        payload.optJSONArray("breadcrumbs")?.let { breadcrumbs ->
            for (i in 0 until breadcrumbs.length()) {
                val crumb = breadcrumbs.getJSONObject(i)
                crumb.put("message", PiiScrubber.scrub(crumb.getString("message")) as String)
                crumb.put("data", scrubJsonValue(crumb.getJSONObject("data"), null))
            }
        }

        // Under strict privacy context and tags are left out when nothing allowed is left in them.
        payload.optJSONObject("context")?.let { payload.put("context", scrubJsonValue(it, null)) }
        payload.optJSONObject("tags")?.let { payload.put("tags", scrubJsonValue(it, null)) }
        payload.optString("sql_statement").takeIf { it.isNotEmpty() }?.let {
            payload.put("sql_statement", PiiScrubber.scrub(it) as String)
        }
        return payload
    }

    /** [PiiScrubber.scrub] operates on Kotlin `Map`/`List`: this is the same recursive
     * redaction logic adapted for `org.json`'s own `JSONObject`/`JSONArray` tree instead, since
     * a JSON payload built from `org.json` never round-trips through a Kotlin `Map`/`List` here. */
    private fun scrubJsonValue(value: Any?, key: String?): Any? {
        if (value != null && value != JSONObject.NULL && PiiScrubber.isSensitiveKey(key)) {
            return PiiScrubber.REDACTED
        }
        return when (value) {
            is JSONObject -> {
                val out = JSONObject()
                for (k in value.keys()) out.put(k, scrubJsonValue(value.get(k), k))
                out
            }
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until value.length()) out.put(scrubJsonValue(value.get(i), key))
                out
            }
            is String -> PiiScrubber.scrub(value) as String
            else -> value
        }
    }
}

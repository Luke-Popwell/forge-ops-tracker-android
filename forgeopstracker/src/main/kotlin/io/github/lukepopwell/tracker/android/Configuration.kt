package io.github.lukepopwell.tracker.android

import android.content.Context
import android.os.Build
import java.io.File
import java.net.URI
import java.net.URISyntaxException

/**
 * A single DSN string carries both the ingestion URL and the project's api_key:
 * `https://<api_key>@host/api/v1/events`. Mirrors `sdks/kotlin`'s own Configuration: this
 * class exists as a separate, Android-flavored version rather than reusing that one directly
 * because several fields here need a [Context] to derive sensible defaults (see [packageName]
 * and [crashReportsDirectory] below), which the plain-JVM Kotlin client has no equivalent of.
 */
class Configuration(context: Context) {
    var dsn: String? = System.getenv("FORGE_OPS_DSN")
    /**
     * `FORGE_OPS_ENVIRONMENT` when set and not blank, otherwise `"production"`, so an app given a
     * DSN and nothing else sends, the same as every other ForgeOps client. It was `"development"`
     * before 0.12.0, which isn't an enabled environment, so an app that set only a DSN sent
     * nothing and said nothing. A debug build isn't taken for development: a build type isn't an
     * environment, and a debug build on a tester's device is exactly where a first error should
     * show up. Set it to `"development"` yourself wherever you don't want errors sent.
     */
    var environment: String = System.getenv("FORGE_OPS_ENVIRONMENT")?.takeIf { it.isNotBlank() } ?: "production"
    var release: String? = System.getenv("FORGE_OPS_RELEASE")

    /** `"Manufacturer Model"`, e.g. `"Google Pixel 8"`: the closest meaningful "server_name"
     * equivalent on a device that isn't a server at all. */
    var serverName: String? = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * The app's own package name, used to mark backtrace frames as `in_app`: unlike
     * `sdks/kotlin`'s own `Configuration.appPackagePrefix`, which has to be set explicitly (a
     * plain JVM app has no single well-known "this is my package" answer), an Android app
     * always knows its own package name via its [Context], so this defaults to
     * `context.packageName` rather than requiring the host app to set it itself.
     */
    var packageName: String? = context.packageName

    /** Where pending (not-yet-uploaded) event reports are written: always available without
     * any storage permission, unlike external storage. */
    var crashReportsDirectory: File = File(context.filesDir, "forgeops-tracker/pending-reports")

    /**
     * Whether [EventBuilder] attempts to read a few lines of source off disk around an in-app
     * frame's culprit line and attach them to that frame (see [EventBuilder]'s own
     * `attachSourceContext`). Defaults to true, mirroring every other ForgeOps client: the
     * durable protection against literal source code leaving a device isn't this flag at all,
     * it's the per-project setting on the ForgeOps server itself, which overrides whatever any
     * given SDK instance has configured locally regardless of what this is set to here.
     *
     * On Android specifically, this option exists purely for API-shape consistency with every
     * other ForgeOps SDK: [attachSourceContext][EventBuilder] is a documented no-op here, always.
     * See [EventBuilder]'s own comment on `attachSourceContext` and README.md's "Source context"
     * section for why: in short, [StackTraceElement.getFileName] never returns anything but a
     * bare file name on the JVM (see [EventBuilder.isInApp]'s own comment), and a compiled Android
     * build (debug or release) never ships `.java`/`.kt` source onto the device in the first
     * place, so there is never a real, openable path for this flag to gate a read against.
     */
    var captureSourceContext: Boolean = true

    /**
     * When an error carries the SQL behind a failed database call (a SQLite `SQLiteException`'s
     * `while compiling:` text, Room, or anything exposing `getSQL`/`getSql`), send the names of
     * the table and view it touched, so an issue says where to start looking. Names are
     * identifiers, never values, which is why this defaults on. [captureSqlStatement] is the
     * separate, opt-in step of also sending the statement itself, with every string and number
     * replaced by `?`; off by default because even a masked statement describes your schema, and
     * ForgeOps' own per-project setting is what durably governs whether the server stores it.
     */
    var captureSqlObjects: Boolean = true

    var captureSqlStatement: Boolean = false

    /**
     * Strict privacy, for apps that can't let personal or health data leave the device: events
     * carry only structured facts (exception class, stack frames, release, environment), never
     * free text. The message is replaced by `message_fingerprint` (see
     * [StrictPrivacy.messageFingerprint]), breadcrumbs aren't recorded or sent, `sql_statement`
     * isn't sent, the user is sent as its id only, and context and tags keep only the
     * [privacyAllowedFields] with plain values. Spans keep only their method and host for outbound
     * calls, and only `db.statement` and `db.system` as data. Off by default;
     * `FORGE_OPS_STRICT_PRIVACY=true` turns it on.
     */
    var strictPrivacy: Boolean = resolveStrictPrivacy(System::getenv)

    /**
     * The context and tag keys strict privacy lets through anyway (your own error codes, a feature
     * name), when their value is a string, number or boolean. Empty by default;
     * `FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature` sets it from the environment.
     */
    var privacyAllowedFields: Set<String> = resolvePrivacyAllowedFields(System::getenv)

    /** Whether [ForgeOpsTracker.addBreadcrumb] records anything at all. On by default, matching
     * every other client in this repo. */
    var trackBreadcrumbs: Boolean = true

    /** How many of the most recent breadcrumbs are kept, oldest dropped first. 30, matching every
     * other client's own default. */
    var maxBreadcrumbs: Int = 30

    /**
     * Whether [ForgeOpsTracker.recordPerformance]/[ForgeOpsTracker.timeTransaction] time anything at
     * all. On by default, the same "on unless you turn it off" posture error reporting itself
     * already has. This client has no web framework integration, so nothing is timed
     * automatically: this only gates the manual API.
     */
    var trackPerformance: Boolean = true

    /** How often the in-process tallies are flushed as one small aggregate report, in milliseconds, rather than one network call per timed call. 60s, matching `gems/forge_ops_tracker`'s own default. */
    var performanceFlushIntervalMillis: Long = 60_000

    /**
     * Intervals between flushes of the buffered [ForgeOpsTracker.captureMetric] /
     * [ForgeOpsTracker.captureInfrastructureMetric] entries (see [MetricBuffer]). There is no
     * `trackMetrics` flag the way [trackPerformance] has one: these are explicit calls the host app's
     * own code makes, not automatic instrumentation, so there is nothing to turn off that simply not
     * calling them doesn't already do.
     */
    var metricFlushIntervalMillis: Long = 60_000
    var infrastructureMetricFlushIntervalMillis: Long = 60_000

    /**
     * Whether traces started by [ForgeOpsTracker.startTrace]/[ForgeOpsTracker.trace] record spans and
     * send slow traces. On by default. With it off a trace still exists and keeps its id, attached to
     * errors captured while it is open and sent in the `traceparent` header (see [propagateTraces]),
     * since that id is also what links an error here to one in your backend; nothing else is recorded
     * or sent. This client has no web framework integration, so nothing starts a trace automatically.
     */
    var trackTracing: Boolean = true

    /** A trace is only sent when its root took at least this many milliseconds. 1000, matching every other client's default. */
    var traceCaptureThresholdMillis: Long = 1_000

    /**
     * Whether [Trace.startHttpSpan]/[Trace.measureHttpSpan] produce a W3C `traceparent` header for
     * the outgoing request, so your backend continues this trace and an error there links to one
     * here. On by default, matching `gems/forge_ops_tracker`.
     */
    var propagateTraces: Boolean = true

    /**
     * Which hosts get that header. `null` (the default) means every host. Otherwise a list of host
     * strings, each matching that host and its subdomains on a dot boundary (`"example.com"` matches
     * `"api.example.com"`, never `"badexample.com"`), and/or [Regex]es (or `java.util.regex.Pattern`s)
     * searched for anywhere in the host, so anchor them yourself. Anything else in the list matches
     * nothing. Set this to your own API's host so third-party SDKs and CDNs never see the header.
     */
    var tracePropagationTargets: List<Any>? = null

    /** Whether an outgoing request to [host] should carry a `traceparent` header; case-insensitive, since hostnames are. */
    fun shouldPropagateTrace(host: String?): Boolean {
        if (!propagateTraces) return false
        val targets = tracePropagationTargets ?: return true
        val normalized = host.orEmpty().lowercase(java.util.Locale.US)
        return targets.any { target ->
            when (target) {
                is Regex -> target.containsMatchIn(normalized)
                is java.util.regex.Pattern -> target.matcher(normalized).find()
                is String -> {
                    val domain = target.lowercase(java.util.Locale.US).removePrefix(".")
                    domain.isNotEmpty() && (normalized == domain || normalized.endsWith(".$domain"))
                }
                else -> false
            }
        }
    }

    var enabledEnvironments: Set<String> = setOf("production", "staging")
    var connectTimeoutMillis: Int = 5_000 // longer than a live-request client's ~2s: see the Objective-C/Swift/C clients' own identical reasoning: this often uploads on the *next* app launch, not inline with a live request
    var readTimeoutMillis: Int = 5_000
    var scrubPii: Boolean = true

    /** Whether [ForgeOpsTracker.installHandlers] installs the uncaught-exception handler and
     * ANR watchdog described in README.md. On by default; set false to opt out of either (see
     * [installUncaughtExceptionHandler] and [installAnrWatchdog] for per-mechanism control). */
    var installUncaughtExceptionHandler: Boolean = true
    var installAnrWatchdog: Boolean = true

    /** How long the main thread can go unresponsive before the ANR watchdog reports it: see
     * [AnrWatchdog]'s own comment for why 5 seconds specifically. */
    var anrThresholdMillis: Long = 5_000

    /** Called with a debug-level message on delivery failures; `null` (the default) discards them. */
    var logger: ((String) -> Unit)? = null

    fun log(message: String) {
        logger?.invoke("[ForgeOpsTracker] $message")
    }

    private fun parsedDsn(): URI? {
        val currentDsn = dsn
        if (currentDsn.isNullOrBlank()) return null
        return try {
            val uri = URI(currentDsn)
            if (uri.host != null) uri else null
        } catch (e: URISyntaxException) {
            null
        }
    }

    val apiKey: String?
        get() = parsedDsn()?.userInfo

    val ingestionUri: URI?
        get() {
            val parsed = parsedDsn() ?: return null
            return try {
                URI(parsed.scheme, null, parsed.host, parsed.port, parsed.path, null, null)
            } catch (e: URISyntaxException) {
                null
            }
        }

    /**
     * Same derivation as [ingestionUri], with the trailing `/events` swapped for
     * `/performance_samples`: one DSN, two endpoints, matching the Ruby gem's own
     * `Configuration#performance_samples_uri`.
     */
    val performanceSamplesUri: URI?
        get() {
            val uri = ingestionUri ?: return null
            val path = uri.path
            if (path == null || !path.endsWith("/events")) return uri
            return try {
                URI(uri.scheme, null, uri.host, uri.port, path.removeSuffix("/events") + "/performance_samples", null, null)
            } catch (e: URISyntaxException) {
                null
            }
        }

    /** Same derivation again, swapping the trailing `/events` for `/custom_metrics`. */
    val customMetricsUri: URI?
        get() = swapEventsSuffix("/custom_metrics")

    /** Same derivation again, swapping the trailing `/events` for `/infrastructure_metrics`. */
    val infrastructureMetricsUri: URI?
        get() = swapEventsSuffix("/infrastructure_metrics")

    /** Same derivation again, swapping the trailing `/events` for `/changes`. */
    val changesUri: URI?
        get() = swapEventsSuffix("/changes")

    private fun swapEventsSuffix(replacement: String): URI? {
        val uri = ingestionUri ?: return null
        val path = uri.path
        if (path == null || !path.endsWith("/events")) return uri
        return try {
            URI(uri.scheme, null, uri.host, uri.port, path.removeSuffix("/events") + replacement, null, null)
        } catch (e: URISyntaxException) {
            null
        }
    }

    /** Same derivation again, swapping the trailing `/events` for `/spans`. */
    val spansUri: URI?
        get() {
            val uri = ingestionUri ?: return null
            val path = uri.path
            if (path == null || !path.endsWith("/events")) return uri
            return try {
                URI(uri.scheme, null, uri.host, uri.port, path.removeSuffix("/events") + "/spans", null, null)
            } catch (e: URISyntaxException) {
                null
            }
        }

    val isEnabled: Boolean
        get() = !dsn.isNullOrBlank() && !apiKey.isNullOrBlank() && environment in enabledEnvironments

    /**
     * The explanation [ForgeOpsTracker.init] logs when this configuration has a DSN but its
     * environment isn't one of [enabledEnvironments], so nothing will be sent; null when there's
     * nothing to warn about (no DSN, or the environment is enabled). The same wording every other
     * ForgeOps client uses.
     */
    val notSendingWarning: String?
        get() {
            if (dsn.isNullOrBlank() || apiKey.isNullOrBlank() || environment in enabledEnvironments) return null
            val enabled = if (enabledEnvironments.isEmpty()) {
                "no environments are enabled"
            } else {
                "only ${enabledEnvironments.joinToString(", ")} are enabled"
            }
            return "Not sending: this environment is \"$environment\", and $enabled. " +
                "Set config.environment = \"production\" (or add \"$environment\" to the enabled environments) " +
                "to send from here."
        }

    internal companion object {
        /** `FORGE_OPS_STRICT_PRIVACY`: `true` or `1` (any case) turns strict privacy on. */
        fun resolveStrictPrivacy(getenv: (String) -> String?): Boolean =
            getenv("FORGE_OPS_STRICT_PRIVACY")?.trim()?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: false

        /** `FORGE_OPS_PRIVACY_ALLOWED_FIELDS`: comma separated, blanks around each name ignored. */
        fun resolvePrivacyAllowedFields(getenv: (String) -> String?): Set<String> =
            getenv("FORGE_OPS_PRIVACY_ALLOWED_FIELDS").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}

# forgeopstracker (Android)

Kotlin error/crash reporting client for [ForgeOps](https://getforgeops.net), for Android apps.
Requires Android API 21+ (Android 5.0), Kotlin 2.0+.

> For health or personal data, turn on [strict privacy](#strict-privacy): the SDK then sends only structured facts about each error (its type, stack trace, release and route), never the message, a raw path, breadcrumbs, or context you haven't allowed.

This client is built specifically for the Android runtime: it takes a `Context` to derive the
app's own package name and files directory, uses `HttpURLConnection` for HTTP delivery so it works
across the full range of supported API levels, and includes ANR (Application Not Responding)
detection alongside crash reporting. Because a crash or ANR leaves the process in an unstable
state that the OS may kill at any moment, automatic reports are written to durable on-disk storage
first and delivered on the next launch (or on explicit demand) rather than attempting a live
network call from a dying process: see "Why a report always uploads on the *next* launch" below.

## Installation

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.luke-popwell.tracker.android:forgeopstracker:0.17.0")
}
```

The dependency coordinate has a hyphen; the actual Kotlin package (below) doesn't, since Kotlin
identifiers can't contain one: Sonatype Central's namespace verification is tied to the exact
GitHub username string (`io.github.luke-popwell`), which is what the published coordinate has to
match, while the package name is free to be anything valid.

### Dependencies

Zero runtime dependencies. `HttpURLConnection` (`java.net`, present since API 1) covers HTTP
delivery: not `java.net.http.HttpClient`, which Android didn't support until a very recent API
level, too high a floor for a library meant to work in real-world apps. Kotlin's own `Regex`
covers PII scrubbing. `org.json.JSONObject`/`JSONArray`: bundled with the Android platform
itself since API 1, not a Maven dependency: covers JSON encoding.

## Configuration

Set a DSN (from a project's settings page in ForgeOps), in your `Application.onCreate()`:

```kotlin
import io.github.lukepopwell.tracker.android.ForgeOpsTracker

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ForgeOpsTracker.init(this) { config ->
            config.dsn = "https://<api_key>@getforgeops.net/api/v1/events"
            config.environment = "production"
        }
        ForgeOpsTracker.installHandlers()
    }
}
```

The environment is `FORGE_OPS_ENVIRONMENT` when set, otherwise `production` (it was `development`
before 0.12.0), and only `production` and `staging` send, so the `config.environment` line above is
optional. Set it to `"development"` where you don't want errors sent. When a DSN is set but the
environment isn't one that sends, `init` logs one warning to Logcat under the tag `ForgeOps`, starting
`[ForgeOps] Not sending`.

`init` takes a `Context` (an `Application` is the obvious choice, but any `Context` works: only
`applicationContext` is ever retained, never the `Context` passed in itself, so this can't leak an
`Activity`) since several `Configuration` fields need one to derive sensible defaults: the app's
own package name (for `in_app` backtrace frames) and its files directory (for the crash store,
below) are both derived automatically this way.

## What gets reported automatically, and what doesn't

**An uncaught exception on any thread needs no further wiring at all**, once `installHandlers()`
has run: installs a `Thread.UncaughtExceptionHandler`, chaining to whatever handler (if any) was
already installed rather than replacing it.

**An ANR (Application Not Responding: the main thread blocked long enough that Android itself
would show the user a dialog) is detected client-side**, the standard technique real Android crash
reporters all use: a background watchdog thread posts a trivial ping to the main thread on a fixed
interval and reports if it goes unacknowledged for `Configuration.anrThresholdMillis` (5s default,
matching the OS's own historical input-event ANR threshold). See `AnrWatchdog`'s own class comment
for the full mechanism. Both this and the uncaught-exception handler are on by default; set
`Configuration.installUncaughtExceptionHandler`/`installAnrWatchdog` to `false` to opt out of
either independently.

**An exception your own code catches and handles is different**: report it explicitly, right at
the catch site:

```kotlin
try {
    chargeCard(order)
} catch (e: CardDeclinedException) {
    ForgeOpsTracker.captureException(e, mapOf("order_id" to order.id))
    logger.warn("card declined", e)
}
```

## Identifying users

```kotlin
ForgeOpsTracker.captureException(error, user = mapOf("id" to user.id, "email" to user.email))
```

Or `setUser(Map<String, Any?>?)` to attach it to every subsequently reported error (an explicit
`captureException` call, an uncaught exception, an ANR) until changed or cleared, rather than
passing it to every call by hand, e.g. right after sign-in:

```kotlin
ForgeOpsTracker.setUser(mapOf("id" to user.id, "email" to user.email))
// on sign-out:
ForgeOpsTracker.setUser(null)
```

There's no way to automatically detect "the current user" on Android, so this is manual either
way. A mobile app install is effectively single-user (unlike a server handling many concurrent
requests at once), so this is a plain `@Volatile` field, not a full thread-local. `id`/`email`/
`username` are all independently optional. Shows up on an issue's own detail page, and as its own
affected-users count alongside the regular event count.

## Breadcrumbs

A small, bounded trail of recent events attached to whatever gets reported next, so an issue's
detail page can show what led up to it, not just the moment it happened:

```kotlin
ForgeOpsTracker.addBreadcrumb("charging card", category = "payment", data = mapOf("orderId" to order.id))
ForgeOpsTracker.addBreadcrumb("opened checkout") // category "custom", level "info"
```

Only the 30 most recent (`Configuration.maxBreadcrumbs`) are kept, oldest dropped first; turn it
off with `trackBreadcrumbs = false`. Safe to call from any thread. `message` and `data` are
PII-scrubbed like the rest of the payload; `category`, `level`, and `timestamp` are structured values
and never touched. Omitted from the payload entirely when the trail is empty. A call made before
`init` is a silent no-op rather than a throw.

There's no request/controller lifecycle in a crash reporter to record one from automatically, so
every breadcrumb is one you add by hand. Unlike `sdks/kotlin`'s per-thread trail (a server handles
one request per thread), this is one trail shared by the whole app behind a lock: breadcrumbs come
from whatever thread is running (main, a coroutine dispatcher, a network callback), while the
uncaught-exception handler reads it on whichever thread crashed and the ANR watchdog reads it from
its own thread, so a per-thread trail would always be missing exactly the events that led up to a
report built on a different thread. Call `ForgeOpsTracker.clearBreadcrumbs()` to start a new logical
unit of work (a new sign-in session, say) with a fresh one.

An uncaught exception and an ANR both carry the trail. Neither needs it persisted to disk (unlike
this repo's Objective-C/Swift clients, whose fatal-signal handlers can't read app state): both run
ordinary JVM code in the still-live process, which builds the whole payload, breadcrumbs included,
and writes it to the crash store itself. A native (NDK) crash isn't covered by this client at all.

## Performance monitoring

Times whatever you wrap and reports one small aggregate per transaction (how many times it ran,
total and maximum duration) every `Configuration.performanceFlushIntervalMillis` (60s by default),
for the Performance page's per-transaction table. Not one network call per timed call.

Each aggregate also carries a small latency histogram (a count per fixed latency bucket: 50, 100,
250, 500, 1000, 2500, 5000 and 10000ms, plus an overflow bucket), so ForgeOps can show an
approximate p50/p95/p99 per transaction, not just an average. Percentiles are accurate to the width
of whichever bucket a duration falls into; the SDK never stores the individual durations.

```kotlin
// Wrap a block; recorded even if it throws (the error propagates unchanged), and its value comes back:
val user = ForgeOpsTracker.timeTransaction("HomeScreen.load") { repository.loadUser() }

// Or record a duration you measured yourself, in milliseconds:
ForgeOpsTracker.recordPerformance("Checkout.pay", elapsedMs.toDouble())
```

This client has no web framework integration, so **nothing is timed automatically**: you choose what
to wrap. Keep transaction names low-cardinality (`"HomeScreen.load"`, not one per item id): every
distinct name is its own row. Safe to call from any thread; a call before `init` is a silent no-op.
Turn it off with `trackPerformance = false`; it also does nothing (and starts no thread) when
reporting isn't enabled for the current environment.

**Call `ForgeOpsTracker.flushPerformance()` when your app goes to the background** (from
`Activity.onStop`, say). A daemon schedule flushes on the interval while the process lives, but an
Android process is killed by the OS rather than exited, and the JVM's shutdown hooks do not run for
that, so unlike `sdks/kotlin` there is deliberately no shutdown hook here (it would be dead code
that looks like a safety net): whatever was tallied since the last tick is lost otherwise.
`flushPerformance()` delivers on the upload background thread, never the caller's, since an Android
app's main thread must not do network I/O; `flushPerformanceSync()` blocks the calling thread until
delivery finishes, for a host app that wants its own scheduling (a WorkManager job, say).

A delivery that failed for now (a network error, a timeout, a 5xx, 408 or 429) keeps every tally, so
the next flush's window just grows, for up to five flushes in a row; after the fifth the batch is
dropped. A batch the server rejects (any other 4xx) is dropped at once, since sending it again could
never work. Either way `config.logger`, if set, is told what was dropped and why. What a flush delivered
is *subtracted* from the tallies afterward, never the whole map cleared: a record from another
thread that lands while the network call is in flight (the lock is deliberately released around it)
would otherwise be silently discarded, a real bug `sdks/go` had and fixed and that
`gems/forge_ops_tracker`'s and `sdks/java`'s flushers still have. A deterministic test pins this.

## Distributed tracing

One flow's own call tree (a screen load, a sign-in, a network round trip and what it triggered),
shown as a span tree on ForgeOps. A trace is sent only when the whole flow took at least
`traceCaptureThresholdMillis` (1000 by default), so fast flows cost nothing on the wire. A trace can
also be followed into your backend (see "Following a request into your backend" below).

```kotlin
ForgeOpsTracker.trace("HomeScreen.load") { trace ->
    val feed = trace.measureSpan("fetch feed", kind = "http") { api.fetchFeed() }
    trace.measureSpan("decode", data = mapOf("items" to feed.size)) { decode(feed) }
}

// Or hold the trace across threads and finish it when the flow ends:
val trace = ForgeOpsTracker.startTrace("Checkout.pay")
thread { trace.recordSpan("charge", kind = "http", startedAtMillis = started, durationMs = ms) }
trace.finish()
```

Unlike the server SDKs, an Android flow hops between the main thread and background threads or
coroutines, so a `Trace` is an explicit object you pass around or capture in a lambda, not ambient
per-thread state, and it is safe to use from any thread. Nesting is tracked per thread: a span opened
by `measureSpan` is the parent of any span recorded on the same thread inside its block, and a span
recorded from another thread parents under the root. A `ThreadLocal` follows a thread, not a
coroutine, so inside `suspend` code that may resume on another thread, pass the parent flow's spans
through `recordSpan` instead of nesting `measureSpan` across a suspension point. `startTrace`
returns `null` when reporting isn't enabled or before `init`; `measureSpan`, `recordSpan`,
`measureHttpSpan`, `startHttpSpan` and `finish` are also defined on the nullable, so callers never
null-check. `kind` is one of `controller`, `service`, `database`, `redis`, `http`, `job`, `other`
(anything else is sent as `other`, since the server rejects a whole trace over one unknown kind).
`measureSpan` records even if its block throws, which propagates unchanged. A trace holds at most 500
spans.

This client has no web framework integration, so **nothing starts a trace or records a span
automatically**. Delivery runs on a private daemon thread with a bounded queue, never the caller's.
The OS can kill the process at any moment and nothing is flushed then, so call
`ForgeOpsTracker.flushSpans()` (it blocks, so from a background thread or a WorkManager job) once a
flow ends right before the app backgrounds. Turn span recording off with
`config.trackTracing = false`: a trace then still has an id (for errors and the header below), but
records and sends nothing.

### Database spans with their SQL

A `database` span can carry the SQL it ran (a query against the app's local SQLite database, say)
and which database it was. Every string and number literal is replaced by `?` before it leaves the
device (so `WHERE email = 'a@b.co'` is sent as `WHERE email = ?`), the statement is cut at 4000
characters, and ForgeOps masks it again on arrival. It is sent in the span's data as `db.statement`
and `db.system`, and ForgeOps shows it on the span. Both are ignored on any other kind.

```kotlin
val sql = "SELECT * FROM messages WHERE thread_id = 42 AND read = 0"
val cursor = trace.measureSpan("Load messages", kind = "database", statement = sql, dbSystem = "sqlite") {
    database.rawQuery(sql, null)
}
// Sent as db.statement "SELECT * FROM messages WHERE thread_id = ? AND read = ?", db.system "sqlite".

// Or for a query you timed yourself:
trace.recordSpan("Load messages", kind = "database", startedAtMillis = started, durationMs = ms, statement = sql, dbSystem = "sqlite")
```

Masking covers backslash-escaped quotes (`'o\'brien'`), prefixed strings (`E'...'`, `X'DEADBEEF'`,
`N'...'`, `B'...'`, `U&'...'`), and hex, binary, exponent and leading-dot numbers (`0x1F`, `0b101`,
`1.5E-3`, `.5`), never the digits inside a name like `orders2` or a `$1` placeholder. With
`dbSystem` `"mysql"` or `"mariadb"`, double-quoted text is masked too, since those databases read
`"..."` as a string; for any other database, SQLite included, it's a name and is left as is.

### Following a request into your backend

Traces use the [W3C Trace Context](https://www.w3.org/TR/trace-context/) standard (a `traceparent`
header). Make each request inside a trace with `measureHttpSpan` (a blocking call) or
`startHttpSpan` (a callback-style client): either records the request as an `http` span named after
the method and host (never the path or query) and gives you the headers to add, whose parent id is
that span's own id, so your backend's spans nest under it. An error captured while the trace is open
carries its id as `trace_id`, so ForgeOps can show it next to the backend error from the same
request. A checkout flow:

```kotlin
fun pay(order: Order) = thread {
    ForgeOpsTracker.trace("Checkout.pay") { trace ->
        try {
            val status = trace.measureHttpSpan("POST", "https://api.example.com/orders/${order.id}/pay") { headers ->
                val connection = URL("https://api.example.com/orders/${order.id}/pay").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.responseCode
            }
            if (status >= 400) throw PaymentFailedException("payment returned $status")
        } catch (e: Exception) {
            ForgeOpsTracker.captureException(e, mapOf("orderId" to order.id)) // carries this trace's trace_id
            showPaymentError()
        }
    }
}
```

With a callback client, start the span before the call and finish it in both callbacks:

```kotlin
val span = trace.startHttpSpan("POST", url)
val request = Request.Builder().url(url).post(body).apply { span.headers.forEach { (name, value) -> header(name, value) } }.build()
client.newCall(request).enqueue(object : Callback {
    override fun onResponse(call: Call, response: Response) { span.finish(mapOf("status" to response.code)) }
    override fun onFailure(call: Call, e: IOException) { span.finish(); ForgeOpsTracker.captureException(e, trace = trace) }
})
```

`captureException` attaches the most recently started trace that hasn't finished (also what
`ForgeOpsTracker.currentTraceId()` returns, and what a crash or ANR report carries); pass
`trace = ...` explicitly when flows overlap or when capturing after `finish`, or `trace = null` to
leave it off. Errors captured with no open trace are unchanged. Always `finish` a trace you start
yourself: an unfinished one keeps tagging errors until 32 newer ones push it out.

Your backend must also report to ForgeOps (the Ruby SDK continues the trace automatically from
0.12.0), and the app's and backend's projects must be linked in ForgeOps to see them connected.

Only send the header to your own API, not to third-party SDKs or CDNs:

```kotlin
ForgeOpsTracker.init(this) { config ->
    config.propagateTraces = false // never produce traceparent (default true)

    // Default null: every host. A String matches that host and its subdomains on a dot boundary
    // ("example.com" matches "api.example.com", not "badexample.com"); a Regex (or Pattern) is
    // searched for in the host, so anchor it yourself.
    config.tracePropagationTargets = listOf("api.example.com", Regex("^10\\.0\\."))
}
```

## Custom metrics and infrastructure monitoring

Two explicit calls (nothing is automatic, so there is no `trackMetrics` flag): a business event you
name yourself, and a reading from the device or from one of your own hosts.

```kotlin
ForgeOpsTracker.captureMetric("signup")                 // value defaults to 1.0: a bare counter
ForgeOpsTracker.captureMetric("payment", 49.0)          // a real magnitude; it may be negative (a refund)

ForgeOpsTracker.captureInfrastructureMetric("battery", 0.42)                    // hostname defaults to serverName
ForgeOpsTracker.captureInfrastructureMetric("disk", 0.81, hostname = "db-1")
ForgeOpsTracker.flushMetrics()                          // send now, on the upload thread
```

Each capture is buffered and flushed as one batch every `metricFlushIntervalMillis` /
`infrastructureMetricFlushIntervalMillis` (60,000 by default) on a daemon scheduled executor, never
the caller's thread. **The OS can kill the process at any moment and there is no shutdown hook on
Android**, so call `flushMetrics()` (which hands off to the upload thread; `flushMetricsSync()` blocks)
from `Activity.onStop` or wherever the app is about to go to the background. Every entry is stored as
it was captured (a signup is a row, not a running total), so a count or sum you compute later is
exact. Both are a no-op when reporting isn't enabled for the environment or before `init`.

A delivery that failed for now (a network error, a timeout, a 5xx, 408 or 429) keeps every entry for
the next flush, and an entry captured while a delivery is in flight is kept too (the Ruby gem's own
buffer loses it; a test pins this with a gated delivery). A batch the server rejects (any other 4xx,
such as the 403 of a plan without the feature) is dropped at once, and so is one that has failed five
flushes in a row; `config.logger`, if set, is told each time. Each buffer holds at most 1000 entries
and drops further ones until a flush succeeds or lets its batch go, since an unreachable server would
otherwise grow it for as long as the process lives. A NaN or
infinite value is dropped at capture: `org.json` refuses to encode one. Requires a ForgeOps plan that
includes custom metrics / infrastructure monitoring.

## Recording changes

When a feature flag flips or a remote config value changes, tell ForgeOps, and it shows the change on
the timeline next to the errors around it, so "crashes started right after `new_checkout` turned on"
is one glance instead of an investigation. Call `recordChange` from your flag or config client's
change callback:

```kotlin
import io.github.lukepopwell.tracker.android.ForgeOpsTracker

ForgeOpsTracker.init(this) { config ->
    config.dsn = "https://<api_key>@getforgeops.net/api/v1/events"
    config.environment = "production"
}

// Your flag client's change listener: whatever it calls with the key and the old and new values.
flagClient.addFlagChangeListener { key: String, oldValue: Boolean, newValue: Boolean ->
    ForgeOpsTracker.recordChange(
        "feature_flag",
        "$key turned ${if (newValue) "on" else "off"}",
        details = mapOf("key" to key, "from" to oldValue, "to" to newValue),
        actor = "flag-service",
    )
}
```

`kind` is one of `"feature_flag"`, `"config"`, `"migration"`, `"dependency"`, `"infrastructure"`, or
`"other"` (`Change.KINDS`); anything else is sent as `"other"`. The title is required and cut to 200
characters. Optional named arguments: `details` (a small JSON-shaped map of strings, numbers, booleans,
lists, and maps; one `org.json` can't encode, such as a NaN, is left out), `environment` (defaults to
the configured one), `service`, `actor`, `url` (http or https), `id` (an idempotency key, so recording
the same change twice keeps one), and `occurredAt` (a `Date`, default now).

`recordChange` returns immediately and sends the change on a private daemon thread, so it's safe to
call from the main thread. It never throws, whether the request fails or your plan doesn't include
change tracking (that 403 is silent), and it's a no-op when reporting isn't enabled or before `init`.
`flushChanges()` blocks until everything recorded so far is sent; call it from a background thread.

## Why a report always uploads on the *next* launch (or the next explicit call), not live

An uncaught exception or an ANR means the app is in a bad state, possibly about to be killed by
the OS: there's no safe way to guarantee a live network call completes from there. Instead,
every automatic report (crash or ANR) is written to disk first (`CrashStore`, a small durable
"queue" under `Configuration.crashReportsDirectory` that survives the process dying, unlike an
in-memory delivery queue) and only actually sent by `Reporter.uploadPendingReports`, called
automatically:

- Once by `installHandlers()`, covering whatever crashed/ANR'd on a *previous* launch.
- Immediately (on a background thread) after every `captureException` call: unlike a crash, that
  one didn't just terminate the app and has no particular reason to wait for the next launch.

Call `ForgeOpsTracker.uploadPendingReportsSync()` yourself for your own control over scheduling
(e.g. only on Wi-Fi, or from a `WorkManager` job): it's synchronous, so call it from a background
thread, not the main one.

An upload that failed for now (a network error, a timeout, a 5xx, 408 or 429) leaves the file in
place for the next attempt. A report the server rejects (any other 4xx) is deleted, since sending it
again could never work. At most 20 reports wait on disk (the oldest beyond that are deleted) and one
older than 7 days is deleted, so a report that can't be delivered is not retried for good.

## `in_app` backtrace frames

Because `StackTraceElement.getFileName()` on the JVM only ever returns a bare file name, never a
full path, `in_app` here is determined by comparing a frame's declaring class package against
`Configuration.packageName`. Since an Android app always knows its own package name, this defaults
automatically to `context.packageName` rather than requiring it to be set explicitly.

## Source context (not implemented on Android, by design): read this before setting `captureSourceContext`

`Configuration.captureSourceContext` exists (defaults to `true`) purely for API-shape consistency
with every other ForgeOps SDK, several of which do attach a few lines of source around an in-app
frame's culprit line, read straight off disk at capture time. **On Android this option does
nothing: `EventBuilder`'s `attachSourceContext` is a documented no-op, always**, regardless of how
this flag is set. Setting it to `false` is harmless, but it isn't turning off a real feature:
there was never a read happening to turn off.

Two separate facts make a real implementation impossible here, not just difficult:

- `StackTraceElement.getFileName()`: the only per-frame "file" this client ever has, on the JVM,
  never returns anything but a bare file name (`"Foo.kt"`), never an absolute or otherwise
  resolvable path. This is exactly why `in_app` classification (above) already has to fall back to
  a package-name check instead of a path check; the same gap rules out source context too.
- Even if a real path were available, a compiled Android build (debug or release) never packages
  `.java`/`.kt` source files onto the device at all: only compiled, and typically
  R8/ProGuard-obfuscated, `.dex` bytecode ships. There is no on-device source tree for any path to
  ever resolve against, on any build variant.

If this ever becomes meaningful (e.g. a future local-debugging-only code path with access to a real
project checkout), the constants a real implementation would use already exist and are named
consistently with every other ForgeOps SDK: `EventBuilder.CONTEXT_LINES` (5 lines either side of
the culprit line) and `EventBuilder.MAX_CONTEXT_LINE_LENGTH` (500 characters, truncated with a
trailing `...`).

## PII scrubbing

The message, backtrace, and any context you attach are scanned for likely personal data (email
addresses, formatted SSNs/credit cards, known API key/token formats, and anything under a
suspiciously-named key like `password`, `api_key`, or `ssn`) and redacted before the
payload ever leaves the device. ForgeOps itself scrubs again on arrival regardless, so this is a
second, earlier layer, not the only one. The user attached via `captureException`'s `user`
parameter or `setUser` above is a deliberate exception: it's never scrubbed, since redacting it
would defeat the whole point of identifying users in the first place.

To disable it: `ForgeOpsTracker.init(context) { it.scrubPii = false }`.

## Strict privacy

For apps that can't let personal or health data (PII, PHI) leave the device. With strict privacy
on, a crash, ANR or captured error carries only structured facts, never free text that could hold
a name, an email or a diagnosis:

- The message isn't sent. `message_fingerprint` goes instead: a one-way hash of the message (after
  PII scrubbing, when that's on) with its numbers, hex ids and UUIDs taken out, so ForgeOps still
  groups errors without seeing the text.
- Breadcrumbs aren't recorded or sent, and neither is the SQL statement (even with
  `captureSqlStatement = true`). Source context is never sent on Android anyway.
- The user is sent as its `id` only, never `email` or `username`.
- Context and tags keep only the keys you allow, and only when the value is a string, number or
  boolean.
- Spans are named by method and host only for outbound calls (`GET api.example.com`), a span named
  by a raw path keeps only its first segment (`GET /patients/:path`, `/patients/:path`; a route
  template such as `GET /orders/{id}` is kept as it is), and span data keeps only `db.statement`
  (already masked) and `db.system`.

The exception class, stack frames, release, environment, device name, trace id and SQL object
names are sent as before. Performance samples, metrics and changes are unchanged: they're already
counts and timings.

```kotlin
ForgeOpsTracker.init(this) { config ->
    config.dsn = "https://<api_key>@getforgeops.net/api/v1/events"
    config.strictPrivacy = true
    config.privacyAllowedFields = setOf("error_code", "feature")
}
```

`FORGE_OPS_STRICT_PRIVACY=true` and `FORGE_OPS_PRIVACY_ALLOWED_FIELDS=error_code,feature` (comma
separated) are read from the environment too, the same way `FORGE_OPS_DSN` is, though an app on a
device rarely has either; an option set in code wins. Both are off and empty by default.

ForgeOps applies the same rules again on arrival when the project's own Strict privacy setting is
on, so an app with an older or misconfigured client still can't get text stored for that project.

## Database errors

When an error carries the SQL behind a failed database call, the event includes the names of the tables and views (and any stored procedure) that SQL touched, so the issue tells you where to start looking. This is on by default and sends identifiers only, never values. On Android the statement is found in SQLite's own error text (`SQLiteException`, including Room's, ends `while compiling: SELECT ...`), or through a `getSQL()`/`getSql()` accessor on the exception or anything it wraps.

To also send the SQL statement itself, opt in. Every string and number is replaced by `?` before it
leaves your process (`WHERE email = 'a@b.co' AND id = 42` is sent as `WHERE email = ? AND id = ?`),
and ForgeOps masks it again on arrival. Escaped quotes, prefixed strings such as `X'DEADBEEF'`, and
hex, binary and exponent numbers are masked the same way:

```kotlin
ForgeOpsTracker.init(context) { config ->
    config.captureSqlStatement = true // default false
    // config.captureSqlObjects = false // default true; false stops even the names
}
```

Each ForgeOps project also has its own "Capture the SQL behind database errors" setting. Turn it off
there and the statement is never stored for that project, whatever this flag says; the names are
still kept. A view and a table are written the same way in SQL, so both show as tables/views; the
database's own error message usually settles which it was.

## Running the tests

```bash
export JAVA_HOME=/path/to/a/jdk-17-through-21   # see the note below
cd sdks/android
./gradlew :forgeopstracker:testDebugUnitTest
```

No emulator or physical device needed: tests run as plain JVM unit tests
(`src/test`, not `src/androidTest`), using [Robolectric](https://robolectric.org) to provide real
implementations of the Android-framework classes this library touches (`Context`, `Looper`/
`Handler` for the ANR watchdog) without a device. `Client`/`Reporter`/`ForgeOpsTracker` tests use
[MockWebServer](https://github.com/square/okhttp/tree/master/mockwebserver) to run a real local
HTTP server rather than mocking the HTTP layer, since AGP's unit-test compile classpath doesn't
expose `com.sun.net.httpserver.HttpServer` (confirmed directly: a compile error, not a runtime
one). `AnrWatchdog`'s own tests use Mockito, since `Reporter` is a concrete class that would be
impractical to hand-write a fake for.

**JDK version matters here too**: the Kotlin compiler this project depends on cannot parse a JDK
26+ version string at all (a hard compiler-internal crash, not a warning). Point `JAVA_HOME` at a
real JDK 17-21 install before running Gradle.

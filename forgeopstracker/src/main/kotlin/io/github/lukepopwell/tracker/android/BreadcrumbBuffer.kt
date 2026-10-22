package io.github.lukepopwell.tracker.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A bounded, in-order trail of what happened right before an error, shared by the whole app
 * (see [ForgeOpsTracker.addBreadcrumb]). Unlike `sdks/kotlin`'s own `BreadcrumbBuffer`, which
 * lives in a `ThreadLocal` because a server handles one request per thread, this one is a single
 * shared trail behind a lock: an Android app adds breadcrumbs from whatever thread happens to be
 * running (main, a coroutine dispatcher, a network callback), while the uncaught-exception handler
 * reads it on whichever thread crashed and the ANR watchdog reads it from its own background
 * thread, so a per-thread trail would always be missing exactly the events that led up to a report
 * built on a different thread. A mobile app is effectively single-flow (the same reasoning
 * [ForgeOpsTracker.setUser]'s one shared user already documents), so one trail is the right unit.
 *
 * No persistence to disk, unlike this repo's Objective-C/Swift clients: those had to write the
 * trail out because a fatal signal's report is uploaded by a different process and their signal
 * handler can't read the trail at all. Here, both automatic capture paths (an uncaught exception,
 * an ANR) run ordinary JVM code in the still-live process, which builds the whole payload,
 * breadcrumbs included, and writes it to [CrashStore] itself. What this client can't capture at all
 * is a native (NDK) crash, which is outside what it covers with or without breadcrumbs.
 *
 * Entries are plain `Map<String, Any?>`, matching `sdks/kotlin`'s own choice and this client's
 * [EventBuilder] convention, not a typed data class.
 */
class BreadcrumbBuffer(private val configuration: Configuration) {
    private val entries = mutableListOf<Map<String, Any?>>()

    fun add(message: String, category: String, level: String, data: Map<String, Any?>) {
        // Read fresh on every add, not captured once at construction: a config change made after
        // init should take effect on whatever's added next.
        // Strict privacy never sends breadcrumbs, so none are kept either: a message is free text.
        val maxSize = maxOf(configuration.maxBreadcrumbs, 0)
        if (!configuration.trackBreadcrumbs || configuration.strictPrivacy || maxSize == 0) return

        // java.time needs API 26 and this library's minSdk is 21, so SimpleDateFormat; a fresh
        // instance per call rather than one shared, since SimpleDateFormat isn't thread-safe and
        // this is called from any thread.
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val entry = linkedMapOf(
            "category" to category,
            "message" to message,
            "level" to level,
            "timestamp" to timestamp,
            "data" to data,
        )
        synchronized(entries) {
            entries.add(entry)
            while (entries.size > maxSize) {
                entries.removeAt(0)
            }
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** A copy, not the live list: the same "never hand out mutable internal state" contract every other client's own equivalent holds to. */
    fun all(): List<Map<String, Any?>> = synchronized(entries) { entries.toList() }
}

package io.github.lukepopwell.tracker.android

import org.json.JSONObject

/**
 * Ties Configuration, EventBuilder, and CrashStore together. Split into two halves (capture now,
 * upload later) rather than one call, same reasoning as this repo's own Objective-C/Swift/C
 * clients: a crash's two real responsibilities happen at two different, unrelated moments: the
 * crash itself (build the payload, write it to disk, nothing else; no live network call, see
 * README.md for why), and whenever [uploadPendingReports] next runs (typically the next app
 * launch, same as those three clients).
 */
class Reporter(
    private val configuration: Configuration,
    private val crashStore: CrashStore = CrashStore(configuration),
    private val client: Client = Client(configuration),
) {
    fun reportThrowable(error: Throwable, context: Map<String, Any?>? = null, user: Map<String, Any?>? = null, breadcrumbs: List<Map<String, Any?>> = emptyList(), traceId: String? = null) {
        report { EventBuilder.buildForThrowable(configuration, error, context, user, breadcrumbs, traceId) }
    }

    /** See [AnrWatchdog]'s own comment for why an ANR report has no [Throwable] to build one
     * from. */
    fun reportAnr(mainThreadStackTrace: Array<StackTraceElement>, user: Map<String, Any?>? = null, breadcrumbs: List<Map<String, Any?>> = emptyList(), traceId: String? = null) {
        report { EventBuilder.buildForAnr(configuration, mainThreadStackTrace, user, breadcrumbs, traceId) }
    }

    // An error reporter that itself throws while reporting an error is the worst possible
    // failure mode, so every path here is guarded: the same guarantee every other client in
    // this repo makes in its own equivalent class.
    private inline fun report(build: () -> JSONObject) {
        try {
            if (!configuration.isEnabled) return
            crashStore.write(build())
        } catch (e: Exception) {
            configuration.log("report failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Uploads every pending report left over from a previous launch, deleting each on success,
     * and each one the server rejects (a 4xx other than 408 and 429: sending it again could never
     * work). One that failed for now (a network error, a timeout, a 5xx, 408 or 429) is left in
     * place for the next attempt, within the count and age [CrashStore] keeps. Deliberately
     * synchronous: call this from a background thread, not the main thread.
     */
    fun uploadPendingReports() {
        if (!configuration.isEnabled) return

        for (file in crashStore.pendingFiles()) {
            val payload = crashStore.read(file)
            if (payload == null) {
                crashStore.delete(file) // unreadable/corrupt file: delete rather than retry forever
                continue
            }
            val status = client.deliverStatus(payload)
            when (DeliveryOutcome.of(status)) {
                DeliveryOutcome.DELIVERED -> crashStore.delete(file)
                DeliveryOutcome.REJECTED -> {
                    crashStore.delete(file) // the server will never take it: delete rather than retry forever
                    configuration.log("dropped a crash report: ${DeliveryOutcome.dropReason(status)}")
                }
                DeliveryOutcome.FAILED -> Unit // leave it in place; the next call retries it
            }
        }
    }
}

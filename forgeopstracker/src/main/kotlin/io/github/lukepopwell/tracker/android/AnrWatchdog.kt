package io.github.lukepopwell.tracker.android

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects an ANR (Application Not Responding: Android's term for the main thread being blocked
 * long enough that the OS itself would show the user a "isn't responding" dialog, historically
 * 5 seconds for an input event) client-side, the standard technique real Android crash reporters
 * all use: a dedicated background thread posts a
 * trivial `Runnable` to the main thread's own [Handler] on a fixed interval and waits up to
 * [Configuration.anrThresholdMillis] for it to actually run. If it doesn't, the main thread is
 * blocked: captured via [Looper.getThread]'s own [Thread.getStackTrace], the best available
 * substitute for a real exception here: an ANR has no [Throwable] at all (nothing was thrown,
 * the thread simply never got back to the event loop), so this is this SDK's one report type
 * built from a raw stack trace instead (see [EventBuilder.buildForAnr]).
 *
 * Once a hang is detected, this keeps checking (without spamming a fresh report every interval)
 * until the main thread actually recovers, then resumes normal watching: a single long ANR
 * should report once, not dozens of times over its duration.
 */
class AnrWatchdog(
    private val configuration: Configuration,
    private val reporter: Reporter,
    private val mainLooper: Looper = Looper.getMainLooper(),
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        val watchdogThread = Thread(::loop, "forgeops-tracker-anr-watchdog")
        watchdogThread.isDaemon = true
        watchdogThread.start()
        thread = watchdogThread
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    private fun loop() {
        val handler = Handler(mainLooper)

        while (running) {
            val acknowledged = AtomicBoolean(false)
            handler.post { acknowledged.set(true) }

            if (!sleep(configuration.anrThresholdMillis)) return

            if (!acknowledged.get() && running) {
                reporter.reportAnr(mainLooper.thread.stackTrace, ForgeOpsTracker.currentUser, ForgeOpsTracker.currentBreadcrumbs, ForgeOpsTracker.currentTraceId())

                // Keep waiting for actual recovery rather than immediately looping back into
                // another full anrThresholdMillis wait: a still-blocked main thread would
                // otherwise get a fresh, duplicate report every single interval.
                while (!acknowledged.get() && running) {
                    if (!sleep(1_000)) return
                }
            }
        }
    }

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (e: InterruptedException) {
        false
    }
}

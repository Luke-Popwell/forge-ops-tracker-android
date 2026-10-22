package io.github.lukepopwell.tracker.android

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Delivers finished traces to the spans endpoint, one at a time on a private daemon thread, so
 * finishing a slow trace never blocks the caller (an Android app's main thread must not do network
 * I/O). Bounded: once [LIMIT] traces are waiting, a new one is dropped rather than queued, since a
 * flow slow enough to be traced must not also grow this app's memory. The OS can kill the process at
 * any moment and nothing is flushed then, so a flow that ends right before the app backgrounds
 * should call [ForgeOpsTracker.flushSpans].
 */
class SpanQueue(private val client: Client) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "forgeops-tracker-spans").apply { isDaemon = true }
    }
    private val pending = AtomicInteger(0)
    private val discarded = AtomicBoolean(false)

    /** Returns false (dropping the trace) when the queue is full. */
    fun push(trace: JSONObject): Boolean {
        if (pending.incrementAndGet() > LIMIT) {
            pending.decrementAndGet()
            return false
        }
        executor.execute {
            try {
                if (!discarded.get()) client.deliverSpans(trace)
            } catch (e: Exception) {
                // One bad delivery must not stop every trace queued after it.
            } finally {
                pending.decrementAndGet()
            }
        }
        return true
    }

    /** Blocks until every queued trace has been delivered (or has failed). */
    fun waitUntilDelivered() {
        try {
            executor.submit {}.get()
        } catch (e: Exception) {
            // Interrupted or shut down: nothing left to wait for.
        }
    }

    /** Drops whatever is still waiting without delivering it, and stops the thread. */
    fun discard() {
        discarded.set(true)
        executor.shutdown()
    }

    companion object {
        const val LIMIT = 100
    }
}

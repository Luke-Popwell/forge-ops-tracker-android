package io.github.lukepopwell.tracker.android

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Same shape as [SpanQueue], delivering [ForgeOpsTracker.recordChange] changes to the changes
 * endpoint one at a time on a private daemon thread, so a flag callback on the main thread never
 * does network I/O. Bounded: once [LIMIT] changes are waiting, a new one is dropped.
 */
class ChangeQueue(private val client: Client) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "forgeops-tracker-changes").apply { isDaemon = true }
    }
    private val pending = AtomicInteger(0)
    private val discarded = AtomicBoolean(false)

    /** Returns false (dropping the change) when the queue is full. */
    fun push(change: JSONObject): Boolean {
        if (pending.incrementAndGet() > LIMIT) {
            pending.decrementAndGet()
            return false
        }
        executor.execute {
            try {
                if (!discarded.get()) client.deliverChange(change)
            } catch (e: Exception) {
                // One bad delivery must not stop every change queued after it.
            } finally {
                pending.decrementAndGet()
            }
        }
        return true
    }

    /** Blocks until every queued change has been delivered (or has failed). */
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

package io.github.lukepopwell.tracker.android

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * [Looper.getMainLooper] under Robolectric defaults to PAUSED mode: a `Runnable` posted to it
 * doesn't actually run until the test calls `shadowOf(Looper.getMainLooper()).idle()`: exactly
 * the control this test needs to simulate both a responsive main thread (idle it promptly) and a
 * genuinely hung one (never idle it at all) from a single-threaded test, without needing a real
 * device's own main thread.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AnrWatchdogTest {
    private var watchdog: AnrWatchdog? = null

    @After
    fun stopWatchdog() {
        watchdog?.stop()
    }

    private fun newConfiguration() = Configuration(ApplicationProvider.getApplicationContext()).apply {
        anrThresholdMillis = 150
    }

    @Test
    fun `does not report while the main thread keeps responding`() {
        val reporter = mock<Reporter>()
        val w = AnrWatchdog(newConfiguration(), reporter)
        watchdog = w
        w.start()

        // Idle the main looper several times, faster than anrThresholdMillis apart, simulating a
        // main thread that keeps processing its queue: the watchdog's own posted pings should
        // always be acknowledged before it ever considers reporting.
        repeat(5) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }

        verify(reporter, never()).reportAnr(any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `reports an anr when the main thread never responds`() {
        val reporter = mock<Reporter>()
        val w = AnrWatchdog(newConfiguration(), reporter)
        watchdog = w
        w.start()

        // Deliberately never idle the main looper: its posted ping is never acknowledged, the
        // same as a genuinely blocked main thread.
        verify(reporter, timeout(2000)).reportAnr(any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `an anr report carries the breadcrumb trail leading up to it`() {
        ForgeOpsTracker.resetForTesting()
        ForgeOpsTracker.init(ApplicationProvider.getApplicationContext()) { it.anrThresholdMillis = 150 }
        ForgeOpsTracker.addBreadcrumb("about to block the main thread", category = "ui")

        val reporter = mock<Reporter>()
        val w = AnrWatchdog(newConfiguration(), reporter)
        watchdog = w
        w.start()

        val captured = argumentCaptor<List<Map<String, Any?>>>()
        verify(reporter, timeout(2000)).reportAnr(any(), anyOrNull(), captured.capture(), anyOrNull())
        assertEquals(listOf("about to block the main thread"), captured.firstValue.map { it["message"] })
        ForgeOpsTracker.resetForTesting()
    }

    @Test
    fun `stop prevents any further reporting`() {
        val reporter = mock<Reporter>()
        val w = AnrWatchdog(newConfiguration(), reporter)
        watchdog = w
        w.start()
        w.stop()

        Thread.sleep(450) // 3x anrThresholdMillis
        verify(reporter, never()).reportAnr(any(), anyOrNull(), any(), anyOrNull())
    }
}

package io.github.lukepopwell.tracker.android

import org.junit.Assert.assertEquals
import org.junit.Test

class HistogramBucketerTest {
    @Test
    fun `returns the smallest boundary a duration fits under as a string`() {
        assertEquals("50", HistogramBucketer.bucketFor(10.0))
        assertEquals("50", HistogramBucketer.bucketFor(50.0))
        assertEquals("100", HistogramBucketer.bucketFor(50.5))
        assertEquals("5000", HistogramBucketer.bucketFor(4999.0))
    }

    @Test
    fun `returns inf for anything larger than the largest boundary`() {
        assertEquals("inf", HistogramBucketer.bucketFor(10_001.0))
        assertEquals("inf", HistogramBucketer.bucketFor(1_000_000.0))
    }

    @Test
    fun `puts a duration exactly on a boundary into that boundarys own bucket`() {
        for (boundary in HistogramBucketer.BOUNDARIES_MS) {
            assertEquals(boundary.toString(), HistogramBucketer.bucketFor(boundary.toDouble()))
        }
    }

    @Test
    fun `boundaries match the servers HistogramPercentile`() {
        // app/services/histogram_percentile.rb and every other SDK must agree on this exact list.
        assertEquals(listOf(50L, 100L, 250L, 500L, 1000L, 2500L, 5000L, 10_000L), HistogramBucketer.BOUNDARIES_MS)
    }
}

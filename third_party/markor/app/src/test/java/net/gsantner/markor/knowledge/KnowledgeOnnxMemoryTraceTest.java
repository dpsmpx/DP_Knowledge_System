/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

/** Pure JVM checks: never allocates actual model RAM or starts an ONNX session. */
public final class KnowledgeOnnxMemoryTraceTest {
    @Test public void recordsLargestObservedSampleIncludingOpenSession() {
        final KnowledgeOnnxMemoryTrace.Recorder recorder = new KnowledgeOnnxMemoryTrace.Recorder();
        recorder.begin(11000);
        recorder.observe(14500); // After FD bytes are resident.
        recorder.observe(22000); // While native session is open.
        recorder.complete(12500);
        final KnowledgeOnnxMemoryTrace report = recorder.result();
        assertEquals(11000L, report.beforeKiB);
        assertEquals(22000L, report.maxObservedKiB);
        assertEquals(12500L, report.afterCloseKiB);
        assertTrue(KnowledgeOnnxMemoryTrace.valid(report.beforeKiB,
                report.maxObservedKiB, report.afterCloseKiB));
    }

    @Test public void unavailableSamplesAreExplicitRatherThanZeroOrFakePeak() {
        final KnowledgeOnnxMemoryTrace.Recorder recorder = new KnowledgeOnnxMemoryTrace.Recorder();
        recorder.begin(KnowledgeOnnxMemoryTrace.UNKNOWN);
        recorder.observe(-5);
        recorder.complete(KnowledgeOnnxMemoryTrace.UNKNOWN);
        final KnowledgeOnnxMemoryTrace report = recorder.result();
        assertEquals(-1L, report.beforeKiB);
        assertEquals(-1L, report.maxObservedKiB);
        assertEquals(-1L, report.afterCloseKiB);
        assertTrue(KnowledgeOnnxMemoryTrace.valid(-1, -1, -1));
        assertTrue(KnowledgeOnnxMemoryTrace.valid(-1, 123, -1));
        assertFalse(KnowledgeOnnxMemoryTrace.valid(5, -1, 5));
        assertFalse(KnowledgeOnnxMemoryTrace.valid(101, 100, 50));
        assertFalse(KnowledgeOnnxMemoryTrace.valid(50, 100, 101));
        assertFalse(KnowledgeOnnxMemoryTrace.valid(0, 1L << 31, 0));
        assertFalse(KnowledgeOnnxMemoryTrace.valid(-2, 5, 0));
    }
}

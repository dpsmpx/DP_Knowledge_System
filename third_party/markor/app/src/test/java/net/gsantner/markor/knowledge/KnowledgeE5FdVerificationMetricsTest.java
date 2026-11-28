/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class KnowledgeE5FdVerificationMetricsTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void exactRoundTripRateIsOnlyAnIoDiagnostic() {
        final KnowledgeE5FdVerificationMetrics.Report report =
                KnowledgeE5FdVerificationMetrics.fromElapsedMillis(256 * MIB, 4000);
        assertEquals(256 * MIB, report.verifiedBytes);
        assertEquals(4000, report.elapsedMillis);
        assertEquals(64.0, report.effectiveMiBPerSecond, 0.000001);
        assertEquals(1.0, KnowledgeE5FdVerificationMetrics.fromElapsedMillis(
                MIB, 1000).effectiveMiBPerSecond, 0.000001);
    }

    @Test public void rejectsUnboundedAndInvalidMeasurements() {
        for (final long bytes : new long[]{-1, 0, 15,
                KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES + 1}) {
            try {
                KnowledgeE5FdVerificationMetrics.fromElapsedMillis(bytes, 1000);
                fail("Invalid transport size accepted");
            } catch (IllegalArgumentException expected) { }
        }
        for (final long elapsed : new long[]{-1, 0}) {
            try {
                KnowledgeE5FdVerificationMetrics.fromElapsedMillis(256 * MIB, elapsed);
                fail("Invalid elapsed time accepted");
            } catch (IllegalArgumentException expected) { }
        }
        assertTrue(Double.isFinite(KnowledgeE5FdVerificationMetrics.fromElapsedMillis(
                KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES, 1).effectiveMiBPerSecond));
    }
}

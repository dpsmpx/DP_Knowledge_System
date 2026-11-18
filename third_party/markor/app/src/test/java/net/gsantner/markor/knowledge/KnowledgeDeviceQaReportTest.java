/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public class KnowledgeDeviceQaReportTest {
    @Test
    public void reportHasReproducibleCountsAndDoesNotClaimManualCoverage() {
        final KnowledgeDeviceQaReport report = new KnowledgeDeviceQaReport(
                "quick", "com.dpsmpx.dpks", "2026.10.0", 35, "arm64-v8a");
        report.add("PASS", "MD-01", 2, "synthetic Markdown checked");
        report.add("WARN", "SCAN-PARTIAL", 3, "unreadableDirectories=1");
        report.add("FAIL", "CORE-01", 6, "exception=JSONException");
        report.add("SKIP", "MANUAL-01", 0, "requires explicit user consent");
        final String actual = report.render(11);
        assertEquals(1, report.failures());
        assertTrue(actual.startsWith("SUMMARY: PASS=1 WARN=1 FAIL=1 SKIP=1 TOTAL=4 elapsed=11 ms"));
        assertTrue(actual.contains("[PASS] MD-01 (2 ms): synthetic Markdown checked"));
        assertTrue(actual.contains("[SKIP] MANUAL-01 (0 ms): requires explicit user consent"));
        assertTrue(actual.contains("not implemented are NOT validated"));
        assertTrue(actual.contains("no note text, vault paths, API keys or network requests"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void reportRejectsUnsupportedOutcome() {
        new KnowledgeDeviceQaReport("quick", "com.dpsmpx.dpks", "v", 35, "arm64")
                .add("OK", "BROKEN", 0, "not a valid outcome");
    }
}

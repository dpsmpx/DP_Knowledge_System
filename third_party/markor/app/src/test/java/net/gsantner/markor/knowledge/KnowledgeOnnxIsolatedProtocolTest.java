/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

/** Pure JVM checks; the actual IPC/native service still needs Android QA. */
public final class KnowledgeOnnxIsolatedProtocolTest {
    @Test public void onlySmallStatusScalarsArePartOfTheContract() {
        assertEquals("1.31.1", KnowledgeOnnxIsolatedProtocol.safeVersion("1.31.1"));
        assertEquals("unknown", KnowledgeOnnxIsolatedProtocol.safeVersion(null));
        assertEquals("?sdcard?secret?path", KnowledgeOnnxIsolatedProtocol.safeVersion(
                "/sdcard/secret/path"));
        assertEquals(48, KnowledgeOnnxIsolatedProtocol.safeVersion(
                "12345678901234567890123456789012345678901234567890").length());
        assertTrue(KnowledgeOnnxIsolatedProtocol.validTimings(1, 150));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validTimings(-1, 1));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validTimings(0, -1));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validTimings(
                KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS + 1, 0));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validTimings(0,
                KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS * 1000L + 1));
    }

    @Test public void verifyOnlyLargeFdHasIndependentBoundedEnvelopeAndWatchdog() {
        assertEquals(16, KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_LARGE_FD);
        assertEquals(17, KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MATCH);
        assertEquals(18, KnowledgeOnnxIsolatedProtocol.RESULT_VERIFY_ONLY_MISMATCH);
        assertTrue(KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_WATCHDOG_MILLIS
                > KnowledgeOnnxIsolatedProtocol.WATCHDOG_MILLIS);
        final String sha = KnowledgeE5LargeFdFixture.SHA256;
        assertTrue(KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(16, sha));
        assertTrue(KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(
                KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES, sha));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(15, sha));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(
                KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES + 1, sha));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(16, null));
        assertFalse(KnowledgeOnnxIsolatedProtocol.validVerifyOnlyFdEnvelope(16,
                sha.toUpperCase()));
    }

    @Test public void debugDirectMetadataUsesSeparateCommandWithoutTrustResult() {
        assertEquals(19, KnowledgeOnnxIsolatedProtocol.DEBUG_INSPECT_DIRECT_E5_FD);
        assertNotEquals(KnowledgeOnnxIsolatedProtocol.RUN_DEBUG_E5_FD,
                KnowledgeOnnxIsolatedProtocol.DEBUG_INSPECT_DIRECT_E5_FD);
        assertNotEquals(KnowledgeOnnxIsolatedProtocol.VERIFY_ONLY_LARGE_FD,
                KnowledgeOnnxIsolatedProtocol.DEBUG_INSPECT_DIRECT_E5_FD);
        assertTrue(KnowledgeOnnxIsolatedProtocol.validDebugE5Variant(
                KnowledgeOnnxIsolatedProtocol.DEBUG_E5_TWO_INPUT_POOLER));
        assertTrue(KnowledgeOnnxIsolatedProtocol.validDebugE5Variant(
                KnowledgeOnnxIsolatedProtocol.DEBUG_E5_THREE_INPUT));
    }
}

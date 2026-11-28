/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class KnowledgeOnnxIoSummaryTest {
    @Test public void generatedIdentityHasOneFloatTensorOnEachSide() {
        final KnowledgeOnnxIoSummary identity = new KnowledgeOnnxIoSummary(
                1, 1, 1, 1, 0, 0, 0, 0, 1, 1);
        assertTrue(KnowledgeOnnxIoSummary.valid(identity, 1, 1));
    }

    @Test public void dynamicInt64InputAndFloatOutputAreBounded() {
        final KnowledgeOnnxIoSummary tokens = new KnowledgeOnnxIoSummary(
                2, 1, 0, 1, 2, 0, 2, 1, 2, 3);
        assertTrue(KnowledgeOnnxIoSummary.valid(tokens, 2, 1));
        assertFalse(KnowledgeOnnxIoSummary.valid(tokens, 1, 1));
    }

    @Test public void invalidCountsAndExcessiveRankAreRejected() {
        assertFalse(KnowledgeOnnxIoSummary.valid(null, 1, 1));
        assertFalse(KnowledgeOnnxIoSummary.valid(
                new KnowledgeOnnxIoSummary(1, 1, 2, 1, 0, 0, 0, 0, 1, 1), 1, 1));
        assertFalse(KnowledgeOnnxIoSummary.valid(
                new KnowledgeOnnxIoSummary(1, 1, 1, 1, 0, 0, 2, 0, 1, 1), 1, 1));
        assertFalse(KnowledgeOnnxIoSummary.valid(
                new KnowledgeOnnxIoSummary(1, 1, 1, 1, 0, 0, 0, 0, 17, 1), 1, 1));
        assertFalse(KnowledgeOnnxIoSummary.valid(
                new KnowledgeOnnxIoSummary(-1, 1, 0, 1, 0, 0, 0, 0, 1, 1), 1, 1));
        assertFalse(KnowledgeOnnxIoSummary.valid(
                new KnowledgeOnnxIoSummary(1, 1, 1, 1, 0, 0, 0, 0, 1, 1), 33, 1));
        // Malformed worker diagnostics must not overflow an int category sum
        // and become a falsely valid input count.
        assertFalse(KnowledgeOnnxIoSummary.valid(
                new KnowledgeOnnxIoSummary(1, 1, Integer.MAX_VALUE, 1,
                        Integer.MAX_VALUE, 0, 0, 0, 1, 1), 1, 1));
    }

    @Test public void optionalE5DigestRejectsMalformedBinderMetadata() {
        final String digest = new String(new char[64]).replace('\0', 'a');
        assertTrue(KnowledgeOnnxIoSummary.valid(new KnowledgeOnnxIoSummary(
                2, 1, 0, 1, 2, 0, 0, 0, 2, 3, digest), 2, 1));
        for (final String bad : new String[]{"", digest.toUpperCase(),
                digest.replace('a', 'z'), digest.substring(1), digest + "a"}) {
            assertFalse(KnowledgeOnnxIoSummary.valid(new KnowledgeOnnxIoSummary(
                    2, 1, 0, 1, 2, 0, 0, 0, 2, 3, bad), 2, 1));
        }
    }
}

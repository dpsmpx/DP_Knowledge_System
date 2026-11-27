/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class KnowledgeE5OnnxReferenceComparisonTest {
    private static final String WEIGHTS = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DESCRIPTOR = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String OTHER = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Test public void sameVerifiedWeightsAndIoMatch() {
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.MATCH,
                KnowledgeE5OnnxReferenceComparison.compare(WEIGHTS, DESCRIPTOR,
                        WEIGHTS, DESCRIPTOR));
    }

    @Test public void differentWeightsTakePrecedenceOverDescriptor() {
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.DIFFERENT_WEIGHTS,
                KnowledgeE5OnnxReferenceComparison.compare(WEIGHTS, DESCRIPTOR,
                        OTHER, OTHER));
        assertEquals(KnowledgeE5OnnxReferenceComparison.Verdict.DIFFERENT_DESCRIPTOR,
                KnowledgeE5OnnxReferenceComparison.compare(WEIGHTS, DESCRIPTOR,
                        WEIGHTS, OTHER));
    }

    @Test public void malformedLocalOrPastedDigestIsNeverAComparison() {
        for (final String invalid : new String[]{null, "", "a", WEIGHTS.toUpperCase(),
                "g" + WEIGHTS.substring(1), WEIGHTS + "0", " " + WEIGHTS,
                WEIGHTS + "\n"}) {
            assertFalse(KnowledgeE5OnnxReferenceComparison.validSha256(invalid));
            for (int position = 0; position < 4; position++) {
                final String[] values = {WEIGHTS, DESCRIPTOR, WEIGHTS, DESCRIPTOR};
                values[position] = invalid;
                try {
                    KnowledgeE5OnnxReferenceComparison.compare(
                            values[0], values[1], values[2], values[3]);
                    fail("Malformed hash accepted in position " + position);
                } catch (IllegalArgumentException expected) { }
            }
        }
        assertTrue(KnowledgeE5OnnxReferenceComparison.validSha256(WEIGHTS));
    }
}

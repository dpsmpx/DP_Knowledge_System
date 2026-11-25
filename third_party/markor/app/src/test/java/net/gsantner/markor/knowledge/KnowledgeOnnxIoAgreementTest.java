/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class KnowledgeOnnxIoAgreementTest {
    private static final KnowledgeOnnxIoSummary IDENTITY = new KnowledgeOnnxIoSummary(
            1, 1, 1, 1, 0, 0, 0, 0, 1, 1);

    @Test public void fullyTypedIdentityCountsAgreeWithoutClaimingShapeCompatibility() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.COUNTS_AND_TENSORS_AGREE,
                KnowledgeOnnxIoAgreement.compare(1, 1, 1, 1, 1, 1, IDENTITY));
    }

    @Test public void incompleteTypeDeclarationsRemainUnknown() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.COUNTS_AGREE_TYPES_PARTIAL,
                KnowledgeOnnxIoAgreement.compare(1, 1, 0, 1, 1, 1, IDENTITY));
    }

    @Test public void differentInputCountIsWarningNotRejectionOfSession() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.INPUT_OUTPUT_COUNTS_DIFFER,
                KnowledgeOnnxIoAgreement.compare(2, 1, 2, 1, 1, 1, IDENTITY));
    }

    @Test public void conflictingTensorTotalsAreSeparateFromCountMismatch() {
        final KnowledgeOnnxIoSummary sequence = new KnowledgeOnnxIoSummary(
                0, 1, 0, 1, 0, 0, 0, 0, 0, 1);
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.TENSOR_COUNTS_DIFFER,
                KnowledgeOnnxIoAgreement.compare(1, 1, 1, 1, 1, 1, sequence));
    }

    @Test public void contradictoryOrMissingAuditIsNotComparable() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                KnowledgeOnnxIoAgreement.compare(1, 1, 2, 1, 1, 1, IDENTITY));
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                KnowledgeOnnxIoAgreement.compare(-1, 1, 0, 1, 1, 1, IDENTITY));
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                KnowledgeOnnxIoAgreement.compare(1, 1, 1, 1, 1, 1, null));
    }
}

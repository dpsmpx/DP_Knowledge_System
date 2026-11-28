/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public final class KnowledgeOnnxIoAgreementTest {
    private static final KnowledgeOnnxIoSummary IDENTITY = new KnowledgeOnnxIoSummary(
            1, 1, 1, 1, 0, 0, 0, 0, 1, 1);

    @Test public void fullyTypedIdentityCountsAgreeWithoutClaimingShapeCompatibility() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.COUNTS_AND_TENSORS_AGREE,
                compare(1, 1, 1, 1, 1, 1, 0, 0, 1, 1, IDENTITY));
    }

    @Test public void incompleteTypeDeclarationsRemainUnknown() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.COUNTS_AGREE_TYPES_PARTIAL,
                compare(1, 1, 0, 1, 0, 1, 0, 0, 1, 1, IDENTITY));
    }

    @Test public void differentInputCountIsWarningNotRejectionOfSession() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.INPUT_OUTPUT_COUNTS_DIFFER,
                compare(2, 1, 2, 1, 1, 1, 0, 0, 1, 1, IDENTITY));
    }

    @Test public void conflictingTensorTotalsAreSeparateFromCountMismatch() {
        final KnowledgeOnnxIoSummary sequence = new KnowledgeOnnxIoSummary(
                0, 1, 0, 1, 0, 0, 0, 0, 0, 1);
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.TENSOR_COUNTS_DIFFER,
                compare(1, 1, 1, 1, 1, 1, 0, 0, 1, 1, sequence));
    }

    @Test public void aggregateFloat32OrInt64MismatchWarnsWithoutRejectingSession() {
        final KnowledgeOnnxIoSummary differing = new KnowledgeOnnxIoSummary(
                1, 1, 0, 1, 1, 0, 0, 0, 1, 1);
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.KNOWN_TYPE_COUNTS_DIFFER,
                compare(1, 1, 1, 1, 1, 1, 0, 0, 1, 1, differing));
    }

    @Test public void otherTypeCountsCannotCertifyIndividualElementTypes() {
        final KnowledgeOnnxIoSummary other = new KnowledgeOnnxIoSummary(
                1, 1, 0, 0, 0, 0, 0, 0, 1, 1);
        // Only the float32/int64 categories are comparable: matching zero
        // counts do NOT certify that unknown/future tensor types match.
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.COUNTS_AND_TENSORS_AGREE,
                compare(1, 1, 1, 1, 0, 0, 0, 0, 1, 1, other));
    }

    @Test public void contradictoryOrMissingAuditIsNotComparable() {
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                compare(1, 1, 2, 1, 1, 1, 0, 0, 1, 1, IDENTITY));
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                compare(-1, 1, 0, 1, 0, 1, 0, 0, 1, 1, IDENTITY));
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                compare(1, 1, 1, 1, 1, 1, 0, 0, 1, 1, null));
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                compare(1, 1, 1, 1, 2, 0, 0, 0, 1, 1, IDENTITY));
        assertEquals(KnowledgeOnnxIoAgreement.Verdict.UNKNOWN,
                compare(1, 1, 1, 1, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0,
                        1, 1, IDENTITY));
    }

    private static KnowledgeOnnxIoAgreement.Verdict compare(
            final long declaredInputs, final long declaredOutputs,
            final long typedInputs, final long typedOutputs,
            final long floatInputs, final long floatOutputs,
            final long int64Inputs, final long int64Outputs,
            final long runtimeInputs, final long runtimeOutputs,
            final KnowledgeOnnxIoSummary summary) {
        return KnowledgeOnnxIoAgreement.compare(declaredInputs, declaredOutputs,
                typedInputs, typedOutputs, floatInputs, floatOutputs, int64Inputs, int64Outputs,
                runtimeInputs, runtimeOutputs, summary);
    }
}

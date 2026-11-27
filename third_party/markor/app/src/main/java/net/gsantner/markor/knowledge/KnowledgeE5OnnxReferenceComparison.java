/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * One-shot comparison of an audited local E5 ONNX file and a manually pasted
 * independent host ORT metadata-only result. Never stores trust or readiness.
 */
final class KnowledgeE5OnnxReferenceComparison {
    enum Verdict { MATCH, DIFFERENT_WEIGHTS, DIFFERENT_DESCRIPTOR }

    private KnowledgeE5OnnxReferenceComparison() { }

    static Verdict compare(final String localWeightsSha, final String localDescriptorSha,
                           final String referenceWeightsSha, final String referenceDescriptorSha) {
        if (!validSha256(localWeightsSha) || !validSha256(localDescriptorSha)
                || !validSha256(referenceWeightsSha) || !validSha256(referenceDescriptorSha)) {
            throw new IllegalArgumentException("Invalid E5 ONNX reference digest");
        }
        if (!localWeightsSha.equals(referenceWeightsSha)) return Verdict.DIFFERENT_WEIGHTS;
        return localDescriptorSha.equals(referenceDescriptorSha)
                ? Verdict.MATCH : Verdict.DIFFERENT_DESCRIPTOR;
    }

    static boolean validSha256(final String sha) {
        if (sha == null || sha.length() != 64) return false;
        for (int i = 0; i < sha.length(); i++) {
            final char value = sha.charAt(i);
            if ((value < '0' || value > '9') && (value < 'a' || value > 'f')) return false;
        }
        return true;
    }
}

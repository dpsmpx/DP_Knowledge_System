/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * Read-only completion metrics for the explicitly requested E5 FD integrity
 * check. Wall time includes binding, package audit, transfer and both SHA
 * passes; it is NOT an ONNX load, inference or model performance benchmark.
 */
final class KnowledgeE5FdVerificationMetrics {
    static final class Report {
        final long verifiedBytes;
        final long elapsedMillis;
        final double effectiveMiBPerSecond;

        private Report(final long bytes, final long millis, final double rate) {
            verifiedBytes = bytes;
            elapsedMillis = millis;
            effectiveMiBPerSecond = rate;
        }
    }

    private KnowledgeE5FdVerificationMetrics() { }

    static Report fromElapsedMillis(final long bytes, final long millis) {
        if (bytes < 16 || bytes > KnowledgeE5LargeFdTransfer.MAX_TRANSFER_BYTES
                || millis < 1) {
            throw new IllegalArgumentException("Invalid E5 verify-only diagnostic");
        }
        // No long multiplications: 1 GiB / 1 ms stays finite as a double.
        final double mibPerSecond = (bytes / 1048576.0) / (millis / 1000.0);
        if (!Double.isFinite(mibPerSecond) || mibPerSecond < 0.0) {
            throw new IllegalArgumentException("Non-finite verify-only rate");
        }
        return new Report(bytes, millis, mibPerSecond);
    }
}

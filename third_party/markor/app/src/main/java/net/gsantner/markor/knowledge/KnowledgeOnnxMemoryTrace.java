/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * A few WORKER-process PSS snapshots in KiB, never a continuous/peak-RSS monitor.
 * Unknown (-1) means Android did not supply a usable value. No paths or data leave the worker.
 */
final class KnowledgeOnnxMemoryTrace {
    static final long UNKNOWN = -1L;
    // Reject corrupt IPC measurements, not a physical memory capacity policy.
    private static final long MAX_PSS_KIB = 1L << 30;

    final long beforeKiB;
    final long maxObservedKiB;
    final long afterCloseKiB;

    KnowledgeOnnxMemoryTrace(final long before, final long observed, final long after) {
        beforeKiB = before;
        maxObservedKiB = observed;
        afterCloseKiB = after;
    }

    static boolean valid(final long before, final long observed, final long after) {
        if (!validValue(before) || !validValue(observed) || !validValue(after)) return false;
        if (observed == UNKNOWN) return before == UNKNOWN && after == UNKNOWN;
        return (before == UNKNOWN || before <= observed)
                && (after == UNKNOWN || after <= observed);
    }

    private static boolean validValue(final long value) {
        return value == UNKNOWN || (value >= 0 && value <= MAX_PSS_KIB);
    }

    static final class Recorder {
        private long before = UNKNOWN;
        private long maximum = UNKNOWN;
        private long after = UNKNOWN;

        void begin(final long sample) {
            before = validValue(sample) ? sample : UNKNOWN;
            observe(before);
        }

        void observe(final long sample) {
            if (validValue(sample) && sample != UNKNOWN) maximum = Math.max(maximum, sample);
        }

        void complete(final long sample) {
            after = validValue(sample) ? sample : UNKNOWN;
            observe(after);
        }

        KnowledgeOnnxMemoryTrace result() {
            return new KnowledgeOnnxMemoryTrace(before, maximum, after);
        }
    }
}

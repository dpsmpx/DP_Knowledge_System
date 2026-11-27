/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * Read-only E5 Small CPU resource preflight. Estimates are intentionally
 * conservative diagnostics, NOT native memory reservations or proof of safety.
 * No model bytes, paths, notes or user data are accessed here.
 */
final class KnowledgeE5ResourceEstimate {
    static final long MIN_AVAILABLE_BYTES = 512L * 1024L * 1024L;
    static final long OS_HEADROOM_BYTES = 256L * 1024L * 1024L;
    static final long NATIVE_OVERHEAD_HINT_BYTES = 128L * 1024L * 1024L;

    static final class Plan {
        final long inputTensorBytes;
        final long hiddenTensorBytes;
        final long estimatedWorkingSetBytes;
        final long availableBytes;
        final boolean memoryDenied;
        final boolean memoryWarning;

        private Plan(final long input, final long hidden, final long working,
                     final long available, final boolean denied, final boolean warning) {
            inputTensorBytes = input;
            hiddenTensorBytes = hidden;
            estimatedWorkingSetBytes = working;
            availableBytes = available;
            memoryDenied = denied;
            memoryWarning = warning;
        }
    }

    private KnowledgeE5ResourceEstimate() { }

    /**
     * Catalog pre-download hint, not a proof of model size or a session gate.
     * Only official E5 weight candidates are covered. The published decimal MB
     * value is approximate; unknown external weights are NOT included.
     */
    static Plan assessCurated(final KnowledgeModelCatalog.Artifact artifact,
                              final long availableBytes, final boolean lowMemory) {
        if (artifact == null || KnowledgeModelCatalog.e5Companions(
                artifact.role, artifact.expectedSha256).isEmpty()) return null;
        return assess(Math.multiplyExact((long) artifact.approximateMegabytes, 1_000_000L),
                0, KnowledgeEmbeddingTensorContract.MAX_SEQUENCE_LENGTH, true,
                availableBytes, lowMemory);
    }

    /**
     * No fixed model-size cap: big user-chosen models receive a warning.
     * A live OS low-memory state or <512 MiB available RAM is a hard veto.
     * 2x file bytes + external bytes + 128 MiB is a rough diagnostic only.
     */
    static Plan assess(final long inlineModelBytes, final long externalBytes,
                       final int sequence, final boolean hasTokenTypes,
                       final long availableBytes, final boolean lowMemory) {
        if (inlineModelBytes < 16 || externalBytes < 0 || availableBytes < 0
                || sequence < 3
                || sequence > KnowledgeEmbeddingTensorContract.MAX_SEQUENCE_LENGTH) {
            throw new IllegalArgumentException("E5 memory estimate input outside contract");
        }
        try {
            final long seq = sequence;
            final long inputs = Math.multiplyExact(Math.multiplyExact(seq,
                    hasTokenTypes ? 3 : 2), Long.BYTES);
            final long hidden = Math.multiplyExact(Math.multiplyExact(seq,
                    KnowledgeE5ConfigProfile.HIDDEN_SIZE), Float.BYTES);
            final long working = Math.addExact(Math.addExact(
                    Math.multiplyExact(inlineModelBytes, 2), externalBytes),
                    Math.addExact(NATIVE_OVERHEAD_HINT_BYTES,
                            Math.addExact(inputs, hidden)));
            final boolean denied = lowMemory || availableBytes < MIN_AVAILABLE_BYTES;
            final boolean warning = denied || working > Math.max(0L,
                    availableBytes - OS_HEADROOM_BYTES);
            return new Plan(inputs, hidden, working, availableBytes, denied, warning);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("E5 resource calculation overflow", overflow);
        }
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;

/**
 * Conservative, fail-closed available-RAM admission for a direct E5 model.
 * This is a live OS snapshot check, NOT a reservation, peak-memory estimate
 * or proof that an ORT allocation cannot OOM. No user/model bytes are read.
 */
final class KnowledgeE5DirectRamGate {
    // Keep 512 MiB available in addition to bytes needed for the next stage.
    // Native opening requires a further 2x model bytes AFTER the direct copy
    // was allocated: possible ORT copies, weights and allocator transient RAM.
    static final long RESERVED_BYTES = KnowledgeOnnxSessionGate.MIN_AVAILABLE_RAM_BYTES;

    private KnowledgeE5DirectRamGate() { }

    /**
     * Before the copy: 512 MiB + one model. Before native ORT: 512 MiB +
     * two models, with the direct copy ALREADY allocated and accounted for
     * in ActivityManager.availMem. No limit here changes model import policy.
     */
    static long requiredAvailableBytes(final long modelBytes, final boolean beforeNative) {
        if (modelBytes < 16 || modelBytes > KnowledgeE5DirectModelBuffer.MAX_DIRECT_BYTES) {
            throw new IllegalArgumentException("Direct E5 size outside native-memory envelope");
        }
        return Math.addExact(RESERVED_BYTES,
                Math.multiplyExact(modelBytes, beforeNative ? 2L : 1L));
    }

    /** Deny unknown/low/insufficient RAM with no allocation or side effects. */
    static void requireAvailable(final long modelBytes, final boolean beforeNative,
                                 final long availableBytes, final boolean lowMemory)
            throws IOException {
        final long required;
        try {
            required = requiredAvailableBytes(modelBytes, beforeNative);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("Invalid direct E5 memory admission input", invalid);
        }
        if (lowMemory || availableBytes < required) {
            throw new IOException("Insufficient RAM for optional direct E5 stage");
        }
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public final class KnowledgeE5DirectRamGateTest {
    private static final long MIB = 1024L * 1024L;

    @Test public void stagesReserveSeparateCopyAndNativeHeadroom() throws Exception {
        for (final long model : new long[]{16L, 17 * MIB + 73L,
                235_000_000L, 470_000_000L,
                KnowledgeE5DirectModelBuffer.MAX_DIRECT_BYTES}) {
            final long copy = KnowledgeE5DirectRamGate.requiredAvailableBytes(model, false);
            final long nativeOpen = KnowledgeE5DirectRamGate.requiredAvailableBytes(model, true);
            assertEquals(KnowledgeOnnxSessionGate.MIN_AVAILABLE_RAM_BYTES + model, copy);
            assertEquals(copy + model, nativeOpen);
            KnowledgeE5DirectRamGate.requireAvailable(model, false, copy, false);
            KnowledgeE5DirectRamGate.requireAvailable(model, true, nativeOpen, false);
            expectDenied(model, false, copy - 1, false);
            expectDenied(model, true, nativeOpen - 1, false);
        }
    }

    @Test public void lowMemoryAndUnknownAvailabilityAlwaysFailClosed() throws Exception {
        final long model = 17 * MIB + 73L;
        final long enough = KnowledgeE5DirectRamGate.requiredAvailableBytes(model, true);
        for (final boolean nativeOpen : new boolean[]{false, true}) {
            expectDenied(model, nativeOpen, enough, true);
            expectDenied(model, nativeOpen, -1, false);
            expectDenied(model, nativeOpen, 0, false);
        }
    }

    @Test public void invalidAndOverLimitModelsCannotEnterEitherStage() throws Exception {
        for (final long invalid : new long[]{-1L, 0L, 15L,
                KnowledgeE5DirectModelBuffer.MAX_DIRECT_BYTES + 1L, Long.MAX_VALUE}) {
            for (final boolean nativeOpen : new boolean[]{false, true}) {
                expectDenied(invalid, nativeOpen, Long.MAX_VALUE, false);
                try {
                    KnowledgeE5DirectRamGate.requiredAvailableBytes(invalid, nativeOpen);
                    fail("Invalid size accepted");
                } catch (IllegalArgumentException expected) { }
            }
        }
    }

    private static void expectDenied(final long size, final boolean nativeOpen,
                                     final long available, final boolean lowMemory) throws Exception {
        try {
            KnowledgeE5DirectRamGate.requireAvailable(size, nativeOpen, available, lowMemory);
            fail("Direct-memory admission did not fail closed");
        } catch (IOException expected) { }
    }
}

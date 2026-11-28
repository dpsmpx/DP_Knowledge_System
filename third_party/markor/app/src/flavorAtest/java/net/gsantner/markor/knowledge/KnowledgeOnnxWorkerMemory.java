/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;

import java.io.IOException;

/** Live checks/snapshots from the isolated UID itself, never the owning app process. */
final class KnowledgeOnnxWorkerMemory {
    static final class Denied extends IOException {
        Denied() { super("Worker memory snapshot unavailable or below safe limit"); }
    }

    static final class Snapshot {
        final long availableBytes;
        final boolean lowMemory;

        Snapshot(final long available, final boolean low) {
            availableBytes = available;
            lowMemory = low;
        }
    }

    private KnowledgeOnnxWorkerMemory() { }

    static void requireAvailable(final Context context, final boolean debugSimulateLowMemory)
            throws Denied {
        try {
            final Snapshot memory = snapshot(context);
            KnowledgeOnnxSessionGate.requireAvailableMemory(
                    debugSimulateLowMemory ? 0L : memory.availableBytes,
                    debugSimulateLowMemory || memory.lowMemory);
        } catch (IOException | RuntimeException blocked) {
            throw new Denied();
        }
    }

    /**
     * Direct allocation and native opening require distinct, current snapshots.
     * Run INSIDE the isolated UID immediately before each respective stage.
     * A successful snapshot is not a reservation or OOM-proof guarantee.
     */
    static void requireAvailableForDirect(final Context context, final long modelBytes,
                                          final boolean beforeNative,
                                          final boolean debugSimulateLowMemory) throws Denied {
        try {
            final Snapshot memory = snapshot(context);
            KnowledgeE5DirectRamGate.requireAvailable(modelBytes, beforeNative,
                    debugSimulateLowMemory ? 0L : memory.availableBytes,
                    debugSimulateLowMemory || memory.lowMemory);
        } catch (IOException | RuntimeException blocked) {
            throw new Denied();
        }
    }

    /** Same worker-UID snapshot also feeds the E5 advisory resource plan. */
    static Snapshot snapshot(final Context context) throws Denied {
        try {
            final ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new Denied();
            final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            if (memory.availMem < 0) throw new Denied();
            return new Snapshot(memory.availMem, memory.lowMemory);
        } catch (RuntimeException blocked) {
            throw new Denied();
        }
    }

    static long samplePssKiB() {
        try {
            // Available on API 14+. RSS API is 35+; PSS is used consistently
            // on all supported emulators. It is a snapshot, not peak RAM.
            final long pss = Debug.getPss();
            return pss >= 0 ? pss : KnowledgeOnnxMemoryTrace.UNKNOWN;
        } catch (RuntimeException | LinkageError unavailable) {
            return KnowledgeOnnxMemoryTrace.UNKNOWN;
        }
    }
}

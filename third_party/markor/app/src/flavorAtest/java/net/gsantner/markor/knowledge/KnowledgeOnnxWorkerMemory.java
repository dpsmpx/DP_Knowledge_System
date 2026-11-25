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

    private KnowledgeOnnxWorkerMemory() { }

    static void requireAvailable(final Context context, final boolean debugSimulateLowMemory)
            throws Denied {
        try {
            final ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new Denied();
            final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            KnowledgeOnnxSessionGate.requireAvailableMemory(
                    debugSimulateLowMemory ? 0L : memory.availMem,
                    debugSimulateLowMemory || memory.lowMemory);
        } catch (IOException | RuntimeException blocked) {
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

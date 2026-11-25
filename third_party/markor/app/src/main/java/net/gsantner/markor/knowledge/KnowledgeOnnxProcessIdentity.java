/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.app.Application;
import android.os.Build;
import android.os.Process;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Permission-free identity check before application settings and worker exit. */
public final class KnowledgeOnnxProcessIdentity {
    private static final String PACKAGE_PREFIX = "com.dpsmpx.dpks:dpks_onnx_cpu";

    private KnowledgeOnnxProcessIdentity() { }

    public static boolean isWorker() {
        if (Build.VERSION.SDK_INT >= 28) {
            final String process = Application.getProcessName();
            return workerName(process)
                    && Process.isIsolatedUid(Process.myUid());
        }
        // API 24-27 has no public Application.getProcessName() or
        // Process.isIsolatedUid(). /proc/self is local process metadata only.
        try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
            final byte[] header = new byte[256];
            final int count = input.read(header);
            if (count <= 0) return false;
            int length = 0;
            while (length < count && header[length] != 0) length++;
            return workerName(new String(header, 0, length, StandardCharsets.UTF_8));
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    /** Isolated service names may append an instance suffix after :dpks_onnx_cpu. */
    static boolean workerName(final String process) {
        return process != null && (process.equals(PACKAGE_PREFIX)
                || process.startsWith(PACKAGE_PREFIX + ":"));
    }
}

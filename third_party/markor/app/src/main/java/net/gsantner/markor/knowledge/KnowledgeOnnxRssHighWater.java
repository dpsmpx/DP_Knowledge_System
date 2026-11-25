/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Kernel VmHWM: highest resident set size of THIS process since its creation,
 * in KiB. Unlike sampled PSS, it includes short-lived RSS spikes, but is not
 * model-only memory and may be inaccessible under Android SELinux.
 */
final class KnowledgeOnnxRssHighWater {
    static final long UNKNOWN = -1L;
    private static final long MAX_KIB = 1L << 30;

    private KnowledgeOnnxRssHighWater() { }

    static boolean valid(final long value) {
        return value == UNKNOWN || (value > 0 && value <= MAX_KIB);
    }

    static long parseLine(final String line) {
        if (line == null || !line.startsWith("VmHWM:") || line.length() > 96) {
            return UNKNOWN;
        }
        final String raw = line.substring("VmHWM:".length()).trim();
        int gap = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isWhitespace(raw.charAt(i))) { gap = i; break; }
        }
        if (gap < 1 || !"kB".equals(raw.substring(gap).trim())) return UNKNOWN;
        final String digits = raw.substring(0, gap);
        if (digits.length() > 13) return UNKNOWN;
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') return UNKNOWN;
        }
        try {
            final long value = Long.parseLong(digits);
            return valid(value) && value != UNKNOWN ? value : UNKNOWN;
        } catch (NumberFormatException invalid) {
            return UNKNOWN;
        }
    }

    static long readSelf() {
        // A fixed self-only Linux pseudo-file; never read another PID or a model path.
        try (BufferedReader input = new BufferedReader(new FileReader("/proc/self/status"))) {
            for (int lines = 0; lines < 80; lines++) {
                final String line = input.readLine();
                if (line == null) break;
                if (line.startsWith("VmHWM:")) return parseLine(line);
            }
        } catch (IOException | RuntimeException unavailable) {
            // SELinux or vendor kernels may deny /proc even to this isolated UID.
        }
        return UNKNOWN;
    }
}

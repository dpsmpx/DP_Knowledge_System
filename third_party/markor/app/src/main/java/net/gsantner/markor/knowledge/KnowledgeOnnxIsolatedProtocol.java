/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/** Scalar-only Messenger contract. No user notes, model bytes or file paths. */
final class KnowledgeOnnxIsolatedProtocol {
    static final int RUN_SYNTHETIC = 1;
    static final int CANCEL_AND_EXIT = 2;
    static final int RESULT_PASSED = 3;
    static final int RESULT_FAILED = 4;
    static final long WATCHDOG_MILLIS = 30_000L;
    static final String VERSION = "v";
    static final String INIT_MILLIS = "init_ms";
    static final String RUN_MICROS = "run_us";
    static final String NATIVE_DELTA = "native_delta";

    private KnowledgeOnnxIsolatedProtocol() { }

    /** Runtime metadata is not user-supplied and never needs arbitrary text. */
    static String safeVersion(final String supplied) {
        if (supplied == null || supplied.isEmpty()) return "unknown";
        final StringBuilder clean = new StringBuilder();
        for (int i = 0; i < supplied.length() && clean.length() < 48; i++) {
            final char value = supplied.charAt(i);
            clean.append((value >= '0' && value <= '9')
                    || (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || value == '.' || value == '-' || value == '_' || value == '+'
                    ? value : '?');
        }
        return clean.toString();
    }

    static boolean validTimings(final long initMillis, final long runMicros) {
        return initMillis >= 0 && initMillis <= WATCHDOG_MILLIS
                && runMicros >= 0 && runMicros <= WATCHDOG_MILLIS * 1000L;
    }
}

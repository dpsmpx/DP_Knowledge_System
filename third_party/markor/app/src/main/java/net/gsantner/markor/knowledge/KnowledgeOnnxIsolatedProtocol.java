/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/** Scalar metadata plus one explicit bounded model FD; no note text or file paths. */
final class KnowledgeOnnxIsolatedProtocol {
    static final int RUN_SYNTHETIC = 1;
    static final int CANCEL_AND_EXIT = 2;
    static final int RESULT_PASSED = 3;
    static final int RESULT_FAILED = 4;
    static final int RUN_MODEL_SESSION = 5;
    static final int RESULT_SESSION_PASSED = 6;
    static final int RESULT_SESSION_FAILED = 7;
    // Never sent by production clients; accepted by the isolated service in debug builds only.
    static final int DEBUG_STALL = 8;
    static final int DEBUG_STALL_STARTED = 9;
    static final long WATCHDOG_MILLIS = 30_000L;
    static final long CLIENT_TIMEOUT_MILLIS = WATCHDOG_MILLIS + 5_000L;
    static final long DEBUG_WATCHDOG_MILLIS = 6_000L;
    static final String VERSION = "v";
    static final String INIT_MILLIS = "init_ms";
    static final String RUN_MICROS = "run_us";
    static final String NATIVE_DELTA = "native_delta";
    static final String PIPE_FD = "fd";
    static final String MODEL_SHA256 = "sha256";
    static final String INPUTS = "inputs";
    static final String OUTPUTS = "outputs";

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

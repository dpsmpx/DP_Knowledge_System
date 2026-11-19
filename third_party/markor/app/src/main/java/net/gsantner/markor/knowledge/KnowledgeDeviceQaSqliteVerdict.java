/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

/** Pure count-only assessment; safe to unit-test without Android or private files. */
final class KnowledgeDeviceQaSqliteVerdict {
    static final class Result {
        final String code;
        final boolean passed;
        final boolean readOnlyDiscrepancy;

        private Result(final String code, final boolean passed,
                       final boolean readOnlyDiscrepancy) {
            this.code = code;
            this.passed = passed;
            this.readOnlyDiscrepancy = readOnlyDiscrepancy;
        }
    }

    private KnowledgeDeviceQaSqliteVerdict() { }

    static Result assess(final int expected, final int indexed, final int hits,
                         final int sources, final int fts, final int missingFts,
                         final int orphanFts, final boolean structuralHealth,
                         final boolean readOnlyQuickOk, final boolean readWriteQuickOk) {
        if (indexed != expected) {
            return new Result("INDEXED_COUNT_MISMATCH", false, false);
        }
        if (sources != expected || fts != expected || missingFts != 0 || orphanFts != 0
                || !structuralHealth) {
            return new Result("INDEX_RELATION_MISMATCH", false, false);
        }
        if (hits < 1) {
            return new Result("FTS_QUERY_EMPTY", false, false);
        }
        if (!readWriteQuickOk) {
            return new Result("SQLITE_QUICK_CHECK_NOT_OK", false, false);
        }
        if (!readOnlyQuickOk) {
            // The underlying synthetic database passed the same check when
            // opened read/write, so the read-only PRAGMA result is suspect.
            return new Result("READ_ONLY_PRAGMA_DISCREPANCY", true, true);
        }
        return new Result("OK", true, false);
    }
}

package net.gsantner.markor.knowledge;

import org.junit.Test;

import static org.junit.Assert.*;

public class KnowledgeDeviceQaSqliteVerdictTest {
    @Test public void healthySyntheticIndexPasses() {
        final KnowledgeDeviceQaSqliteVerdict.Result r = KnowledgeDeviceQaSqliteVerdict.assess(
                3, 3, 3, 3, 3, 0, 0, true, true, true);
        assertTrue(r.passed);
        assertFalse(r.readOnlyDiscrepancy);
        assertEquals("OK", r.code);
    }

    @Test public void readOnlySqliteBugWarnsButDoesNotInvalidateSyntheticSearch() {
        final KnowledgeDeviceQaSqliteVerdict.Result r = KnowledgeDeviceQaSqliteVerdict.assess(
                20_000, 20_000, 8, 20_000, 20_000, 0, 0, true, false, true);
        assertTrue(r.passed);
        assertTrue(r.readOnlyDiscrepancy);
        assertEquals("READ_ONLY_PRAGMA_DISCREPANCY", r.code);
    }

    @Test public void genuineQuickCheckFailureStillFails() {
        final KnowledgeDeviceQaSqliteVerdict.Result r = KnowledgeDeviceQaSqliteVerdict.assess(
                3, 3, 3, 3, 3, 0, 0, true, false, false);
        assertFalse(r.passed);
        assertEquals("SQLITE_QUICK_CHECK_NOT_OK", r.code);
    }

    @Test public void allCountsAndSearchMustMatchRegardlessOfPragma() {
        assertEquals("INDEXED_COUNT_MISMATCH", KnowledgeDeviceQaSqliteVerdict.assess(
                3, 2, 1, 3, 3, 0, 0, true, true, true).code);
        assertEquals("INDEX_RELATION_MISMATCH", KnowledgeDeviceQaSqliteVerdict.assess(
                3, 3, 1, 3, 2, 1, 0, false, true, true).code);
        assertEquals("FTS_QUERY_EMPTY", KnowledgeDeviceQaSqliteVerdict.assess(
                3, 3, 0, 3, 3, 0, 0, true, true, true).code);
    }
}

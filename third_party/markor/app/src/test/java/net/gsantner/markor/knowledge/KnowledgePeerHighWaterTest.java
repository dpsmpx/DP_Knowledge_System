/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Synthetic journal lines and deterministic no-credential peer IDs. */
public class KnowledgePeerHighWaterTest {
    private static final String PEER = new String(new char[64]).replace('\0', 'a');
    private static final String OTHER = new String(new char[64]).replace('\0', 'b');

    @Test public void oldPrefixIsRejectedAfterInboxDeletionButForkIsReviewable()
            throws Exception {
        final Map<String, Set<String>> ledger = new HashMap<>();
        final String older = "{\"seq\":1}\n{\"seq\":2,\"branch\":\"common\"}\n";
        final String longer = older + "{\"seq\":3,\"branch\":\"left\"}\n";
        KnowledgePeerHighWater.recordProperPrefixes(ledger, PEER, longer);
        // Simulate user deleting every inbox record: only protected digest
        // commitments remain, so old branch replay must still be refused.
        assertTrue(KnowledgePeerHighWater.wasObservedAsProperPrefix(ledger, PEER, older));
        assertFalse(KnowledgePeerHighWater.wasObservedAsProperPrefix(ledger, PEER, longer));
        final String fork = older + "{\"seq\":3,\"branch\":\"right\"}\n";
        assertFalse(KnowledgePeerHighWater.wasObservedAsProperPrefix(ledger, PEER, fork));
        assertFalse(KnowledgePeerHighWater.wasObservedAsProperPrefix(ledger, OTHER, older));
        KnowledgePeerHighWater.recordProperPrefixes(ledger, PEER, fork);
        assertTrue(KnowledgePeerHighWater.wasObservedAsProperPrefix(ledger, PEER, older));
    }

    @Test public void neverTrustMalformedFingerprintOrOversizedJournal() throws Exception {
        final Map<String, Set<String>> ledger = new HashMap<>();
        try {
            KnowledgePeerHighWater.recordProperPrefixes(ledger, "wrong", "line\nnext\n");
            fail("Unbound signer accepted");
        } catch (SecurityException expected) { /* Fail closed. */ }
        assertTrue(ledger.isEmpty());
        try {
            KnowledgePeerHighWater.recordProperPrefixes(ledger, PEER,
                    new String(new char[KnowledgeJournalCrypto.MAX_JOURNAL_BYTES + 1])
                            .replace('\0', 'x'));
            fail("Unbounded ledger input accepted");
        } catch (java.io.IOException expected) { /* Fail closed. */ }
        assertTrue(ledger.isEmpty());
    }
}

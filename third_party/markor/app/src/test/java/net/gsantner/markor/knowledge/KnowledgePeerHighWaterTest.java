/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

    @Test public void portableCheckpointRoundTripMergesWithoutDowngrading() throws Exception {
        final Map<String, Set<String>> local = new HashMap<>();
        final Map<String, Set<String>> foreign = new HashMap<>();
        KnowledgePeerHighWater.recordProperPrefixes(local, PEER, "one\ntwo\nthree\n");
        KnowledgePeerHighWater.recordProperPrefixes(foreign, PEER, "one\ntwo\nfork\n");
        KnowledgePeerHighWater.recordProperPrefixes(foreign, OTHER, "a\nb\n");
        final byte[] portable = KnowledgePeerHighWater.encode(foreign);
        final Map<String, Set<String>> decoded = KnowledgePeerHighWater.decode(portable);
        assertEquals(foreign, decoded);
        final int localBefore = local.get(PEER).size();
        KnowledgePeerHighWater.mergeMonotonic(local, decoded);
        assertTrue(local.get(PEER).size() >= localBefore);
        assertTrue(local.containsKey(OTHER));
        assertTrue(KnowledgePeerHighWater.wasObservedAsProperPrefix(local, PEER,
                "one\ntwo\n"));
        final byte[] corrupted = portable.clone();
        corrupted[3] ^= 127;
        try { KnowledgePeerHighWater.decode(corrupted); fail("Bad checkpoint version accepted"); }
        catch (SecurityException expected) { /* Fail closed. */ }
    }

    @Test public void encryptedArchiveManifestCoversPortableHighWaterCheckpoint()
            throws Exception {
        final File parent = Files.createTempDirectory("stage21-highwater-archive-").toFile();
        final File vault = new File(parent, "vault");
        assertTrue(vault.mkdir());
        Files.write(new File(vault, "note.md").toPath(),
                "safe source fixture".getBytes(StandardCharsets.UTF_8));
        final Map<String, Set<String>> history = new HashMap<>();
        KnowledgePeerHighWater.recordProperPrefixes(history, PEER, "first\nsecond\n");
        final byte[] checkpoint = KnowledgePeerHighWater.encode(history);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final KnowledgePortableArchive.Summary written = KnowledgePortableArchive.write(
                vault, null, null, null, null, checkpoint, output, () -> false);
        assertTrue(written.highWater);
        final File zip = new File(parent, "checkpoint.zip");
        Files.write(zip.toPath(), output.toByteArray());
        try {
            assertTrue(KnowledgePortableArchive.verify(zip, () -> false).highWater);
            assertEquals(1, KnowledgePeerHighWater.portablePrefixCount(zip));
            assertEquals(1, KnowledgePeerHighWater.portableSignerCount(zip));
        } finally { assertTrue(zip.delete()); }
    }
}

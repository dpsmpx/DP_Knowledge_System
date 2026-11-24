/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class KnowledgeModelCatalogTest {
    @Test public void curatedEntriesAreImmutableAndHaveRealDirectWeightUrls() throws Exception {
        final Set<String> seenHashes = new HashSet<>();
        final Set<String> seenIds = new HashSet<>();
        assertEquals(7, KnowledgeModelCatalog.ARTIFACTS.size());
        for (final KnowledgeModelCatalog.Artifact item : KnowledgeModelCatalog.ARTIFACTS) {
            assertTrue(item.approximateMegabytes > 0);
            assertEquals(64, item.expectedSha256.length());
            assertTrue(seenHashes.add(item.expectedSha256));
            assertTrue(seenIds.add(item.id));
            assertTrue(item.sourcePage.startsWith("https://huggingface.co/"));
            assertTrue(item.downloadUrl.startsWith(item.sourcePage + "/resolve/main/"));
            assertFalse(item.license.isEmpty());
            assertNotNull(KnowledgeModelDownload.parseSource(item.downloadUrl));
            assertSame(item, KnowledgeModelCatalog.findInstalled(item.role, item.expectedSha256));
        }
        for (final KnowledgeModelPolicy.Role role : KnowledgeModelPolicy.Role.values()) {
            final List<KnowledgeModelCatalog.Artifact> entries = KnowledgeModelCatalog.forRole(role);
            for (final KnowledgeModelCatalog.Artifact entry : entries) assertEquals(role, entry.role);
            try {
                entries.clear();
                fail("Curated catalog must be immutable");
            } catch (UnsupportedOperationException expected) { }
        }
        assertEquals(2, KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.EMBEDDING).size());
        assertEquals(3, KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.GENERATION).size());
        assertEquals(2, KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.RERANKING).size());
        assertTrue(KnowledgeModelCatalog.forRole(KnowledgeModelPolicy.Role.PERSONALIZATION).isEmpty());
        assertNull(KnowledgeModelCatalog.findInstalled(KnowledgeModelPolicy.Role.EMBEDDING,
                "0000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test public void sourceAndDigestValidationRejectsUntrustedCatalogEntries() {
        final String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        try {
            new KnowledgeModelCatalog.Artifact(KnowledgeModelPolicy.Role.GENERATION,
                    "fake", "Fake", "https://huggingface.co/publisher/model",
                    "http://huggingface.co/publisher/model/resolve/main/model.gguf",
                    hash, "MIT", 20);
            fail("Accepted plain HTTP");
        } catch (IllegalArgumentException expected) { }
        try {
            new KnowledgeModelCatalog.Artifact(KnowledgeModelPolicy.Role.GENERATION,
                    "fake", "Fake", "https://huggingface.co/publisher/model",
                    "https://huggingface.co/other/model/resolve/main/model.gguf",
                    hash, "MIT", 20);
            fail("Accepted mismatched publisher");
        } catch (IllegalArgumentException expected) { }
        try {
            new KnowledgeModelCatalog.Artifact(KnowledgeModelPolicy.Role.GENERATION,
                    "fake", "Fake", "https://huggingface.co/publisher/model",
                    "https://huggingface.co/publisher/model/resolve/main/model.gguf",
                    "", "MIT", 20);
            fail("Accepted unverified weight hash");
        } catch (IllegalArgumentException expected) { }
    }
}

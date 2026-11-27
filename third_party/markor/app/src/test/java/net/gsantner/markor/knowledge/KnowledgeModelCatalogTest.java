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
            assertEquals(KnowledgeModelDownload.parseSource(item.downloadUrl).filename,
                    item.filename);
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

    @Test public void officialE5CompanionsRequireAnExactKnownEmbeddingWeight() {
        final List<KnowledgeModelCatalog.Artifact> e5 = KnowledgeModelCatalog.forRole(
                KnowledgeModelPolicy.Role.EMBEDDING);
        assertEquals(2, e5.size());
        for (final KnowledgeModelCatalog.Artifact weight : e5) {
            final List<KnowledgeModelCatalog.Companion> companions =
                    KnowledgeModelCatalog.e5Companions(weight.role, weight.expectedSha256);
            assertEquals(3, companions.size());
            assertEquals("sentencepiece.bpe.model", companions.get(0).filename);
            assertEquals("cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865",
                    companions.get(0).expectedSha256);
            assertEquals("config.json", companions.get(1).filename);
            assertEquals("bbb7c1333fc4b3e27fbc9cd5d2070aabcc1d4dfb99917c3633e772f97545a6b6",
                    companions.get(1).expectedSha256);
            assertEquals("tokenizer_config.json", companions.get(2).filename);
            assertEquals("a1d6bc8734a6f635dc158508bef000f8e2e5a759c7d92f984b2c86e5ff53425b",
                    companions.get(2).expectedSha256);
            for (final KnowledgeModelCatalog.Companion companion : companions) {
                assertEquals("https://huggingface.co/intfloat/multilingual-e5-small/"
                                + "blob/main/onnx/" + companion.filename, companion.sourcePage);
            }
            try {
                companions.clear();
                fail("The official companion catalog must be immutable");
            } catch (UnsupportedOperationException expected) { }
            assertTrue(KnowledgeModelCatalog.e5Companions(
                    KnowledgeModelPolicy.Role.GENERATION, weight.expectedSha256).isEmpty());
        }
        assertTrue(KnowledgeModelCatalog.e5Companions(KnowledgeModelPolicy.Role.EMBEDDING,
                "0000000000000000000000000000000000000000000000000000000000000000").isEmpty());
    }

    @Test public void officialCompanionsRejectUnsafePathsAndInvalidHashes() {
        for (final String upstream : new String[]{"../config.json", "onnx/../config.json",
                "https://elsewhere/config.json"}) {
            try {
                new KnowledgeModelCatalog.Companion("config.json", upstream,
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
                fail("Untrusted companion path accepted");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            new KnowledgeModelCatalog.Companion("config.json", "onnx/config.json", "unknown");
            fail("Unpinned companion accepted");
        } catch (IllegalArgumentException expected) { }
    }
}

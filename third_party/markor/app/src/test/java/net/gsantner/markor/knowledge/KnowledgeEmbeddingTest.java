/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class KnowledgeEmbeddingTest {
    @Test
    public void embeddingsUseTheSameRestrictedNetworkPolicyAsChat() {
        final URL endpoint = KnowledgeEmbeddingClient.endpoint("http://192.168.0.2:1234/v1");
        assertEquals("http://192.168.0.2:1234/v1/embeddings", endpoint.toString());
        assertEquals("https://api.example.org/v1/embeddings",
                KnowledgeEmbeddingClient.endpoint("https://api.example.org/v1/embeddings")
                        .toString());
        for (final String bad : Arrays.asList("http://8.8.8.8/v1",
                "http://model.local/v1", "http://127.0.0.1@evil.test/v1",
                "https://api.example.org/v1/../v1/embeddings",
                "https://api.example.org/v1/chat/completions")) {
            try {
                KnowledgeEmbeddingClient.endpoint(bad);
                fail("Invalid endpoint accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // The policy must be explicit; no DNS resolution on HTTP.
            }
        }
    }

    @Test
    public void fingerprintTiesVectorIndexToModelAndEndpoint() {
        final URL endpoint = KnowledgeEmbeddingClient.endpoint("http://127.0.0.1:1234/v1");
        final String a = KnowledgeEmbeddingClient.modelKey(endpoint, "nomic-embed-text");
        assertEquals(64, a.length());
        assertEquals(a, KnowledgeEmbeddingClient.modelKey(endpoint, "nomic-embed-text"));
        assertNotEquals(a, KnowledgeEmbeddingClient.modelKey(endpoint, "other-embeddings"));
        assertNotEquals(a, KnowledgeEmbeddingClient.modelKey(
                KnowledgeEmbeddingClient.endpoint("http://192.168.0.2:1234/v1"),
                "nomic-embed-text"));
    }

    @Test
    public void markdownChunksCoverLongNotesSequentiallyWithStableOffsets() {
        final String source = "begin".concat(new String(new char[9000]).replace('\0', 'x'))
                .concat("last-topic");
        final List<KnowledgeEmbeddingChunks.Chunk> chunks =
                KnowledgeEmbeddingChunks.fromNote(source);
        assertTrue(chunks.size() > 3);
        assertTrue(chunks.size() < KnowledgeEmbeddingChunks.MAX_CHUNKS_PER_NOTE);
        assertTrue(chunks.get(0).text.startsWith("begin"));
        assertTrue(chunks.get(chunks.size() - 1).text.contains("last-topic"));
        assertTrue(chunks.get(1).start > chunks.get(0).start);
        for (int i = 0; i < chunks.size(); i++) {
            final KnowledgeEmbeddingChunks.Chunk chunk = chunks.get(i);
            assertTrue(chunk.text.length() <= KnowledgeEmbeddingChunks.CHUNK_CHARS);
            assertEquals(source.substring(chunk.start, chunk.start + chunk.text.length()),
                    chunk.text);
            if (i > 0) {
                assertTrue(chunks.get(i - 1).end() > chunk.start); // deliberate overlap
            }
        }
        assertFalse(KnowledgeEmbeddingChunks.coverageTruncated(source, chunks));
        final KnowledgeEmbeddingChunks.Chunk last = chunks.get(chunks.size() - 1);
        assertTrue(KnowledgeEmbeddingChunks.atOffset(source, last.start, last.text.length())
                .contains("last-topic"));
        assertTrue(KnowledgeEmbeddingChunks.atOffset(source, source.length() + 1).isEmpty());
    }

    @Test
    public void prefersHeadingBoundaryWithoutSilentlyClaimingFullCoverage() {
        final String prefix = new String(new char[590]).replace('\0', 'a');
        final String chapter = "\n# Second chapter\nSection content";
        final String source = prefix + chapter + "\n"
                + new String(new char[650]).replace('\0', 'z');
        final List<KnowledgeEmbeddingChunks.Chunk> pieces =
                KnowledgeEmbeddingChunks.fromNote(source);
        assertTrue(pieces.size() >= 2);
        assertEquals(source.indexOf("# Second chapter"), pieces.get(0).end());
        final String huge = new String(new char[300_000]).replace('\0', 'b');
        final List<KnowledgeEmbeddingChunks.Chunk> capped =
                KnowledgeEmbeddingChunks.fromNote(huge);
        assertEquals(KnowledgeEmbeddingChunks.MAX_CHUNKS_PER_NOTE, capped.size());
        assertTrue(KnowledgeEmbeddingChunks.coverageTruncated(huge, capped));
    }

    @Test
    public void lshCandidatesIncludeAllExactAndOneBitNeighborBuckets() {
        final float[] normalized = KnowledgeEmbeddingVectors.normalize(
                new float[]{1, 2, 3, 4, 5, 6, 7, 8});
        final int[] buckets = KnowledgeEmbeddingLsh.buckets(normalized);
        final int[] probes = KnowledgeEmbeddingLsh.probes(normalized);
        assertEquals(4, buckets.length);
        assertEquals(44, probes.length);
        for (int table = 0; table < buckets.length; table++) {
            assertEquals(buckets[table], probes[table * 11]);
            for (int bit = 0; bit < 10; bit++) {
                assertEquals(buckets[table] ^ (1 << bit), probes[table * 11 + bit + 1]);
            }
        }
        assertArrayEquals(buckets, KnowledgeEmbeddingLsh.buckets(normalized));
    }

    @Test
    public void cosineSearchRanksSemanticVectorsAndRejectsInvalidBuffers() {
        final float[] direction = new float[]{3, 4, 0, 0, 0, 0, 0, 0};
        final float[] query = KnowledgeEmbeddingVectors.normalize(direction);
        final byte[] same = KnowledgeEmbeddingVectors.encode(query);
        final float[] unrelated = KnowledgeEmbeddingVectors.normalize(
                new float[]{0, 0, 1, 0, 0, 0, 0, 0});
        final float[] opposite = KnowledgeEmbeddingVectors.normalize(
                new float[]{-3, -4, 0, 0, 0, 0, 0, 0});
        assertEquals(1.0, KnowledgeEmbeddingVectors.similarity(query, same), 0.00001);
        assertArrayEquals(query, KnowledgeEmbeddingVectors.decode(same), 0.00001f);
        assertEquals(0.0, KnowledgeEmbeddingVectors.similarity(
                query, KnowledgeEmbeddingVectors.encode(unrelated)), 0.00001);
        assertEquals(-1.0, KnowledgeEmbeddingVectors.similarity(
                query, KnowledgeEmbeddingVectors.encode(opposite)), 0.00001);
        assertTrue(Double.isNaN(KnowledgeEmbeddingVectors.similarity(query, new byte[5])));
    }

    @Test
    public void malformedEmbeddingsCannotBecomeSearchEvidence() {
        for (final float[] invalid : new float[][]{
                {1, 2}, {0, 0, 0, 0, 0, 0, 0, 0},
                {1, Float.NaN, 0, 0, 0, 0, 0, 0},
                {1, Float.POSITIVE_INFINITY, 0, 0, 0, 0, 0, 0}}) {
            try {
                KnowledgeEmbeddingVectors.normalize(invalid);
                fail("Invalid vector accepted");
            } catch (IllegalArgumentException expected) {
                // Invalid vectors never enter the derived index.
            }
        }
    }
}

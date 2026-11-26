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
    public void androidHybridFixtureOppositeVectorsCannotLeakIntoLshProbeSet() {
        final float[] query = KnowledgeEmbeddingVectors.normalize(
                new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        final float[] opposite = KnowledgeEmbeddingVectors.normalize(
                new float[]{-1, 0, 0, 0, 0, 0, 0, 0});
        // Shared with scripts/benchmark_stage19_hybrid.py: a golden LSH
        // signature catches drift between the host SQL mirror and Android.
        assertArrayEquals(new int[]{1023, 1723, 3022, 3807},
                KnowledgeEmbeddingLsh.buckets(query));
        final int[] queryProbes = KnowledgeEmbeddingLsh.probes(query);
        final float[] near = KnowledgeEmbeddingVectors.normalize(
                new float[]{0.9f, 0.4358899f, 0, 0, 0, 0, 0, 0});
        boolean nearCandidateSeen = false;
        for (final int bucket : KnowledgeEmbeddingLsh.buckets(near)) {
            for (final int probe : queryProbes) {
                if (bucket == probe) nearCandidateSeen = true;
            }
        }
        assertTrue("The two-chunk Android fixture must expose its weaker first chunk",
                nearCandidateSeen);
        final int[] oppositeBuckets = KnowledgeEmbeddingLsh.buckets(opposite);
        for (final int bucket : oppositeBuckets) {
            for (final int probe : queryProbes) {
                assertNotEquals("The QA lexical-only vector must stay outside the query LSH",
                        bucket, probe);
            }
        }
        assertEquals(1, KnowledgeEmbeddingVectors.similarity(query,
                KnowledgeEmbeddingVectors.encode(query)), .00001);
        assertEquals(-1, KnowledgeEmbeddingVectors.similarity(query,
                KnowledgeEmbeddingVectors.encode(opposite)), .00001);
    }

    @Test
    public void overloadedCandidateFixtureIncludesExactNeighborOverlapButNotHiddenSource() {
        final float[] query = KnowledgeEmbeddingVectors.normalize(
                new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        final int[] exact = KnowledgeEmbeddingLsh.buckets(query);
        final int[] probes = KnowledgeEmbeddingLsh.probes(query);
        // Same 0.99/0.98/0.80 vectors as EMBED-OVERLAP-19 and the Python mirror.
        for (int i = 0; i < 3; i++) {
            final float cosine = i == 0 ? .99f : i == 1 ? .98f : .80f;
            final float[] direction = new float[8];
            direction[0] = cosine;
            direction[i == 1 ? 7 : 1] = (float) Math.sqrt(1 - cosine * cosine);
            final float[] candidate = KnowledgeEmbeddingVectors.normalize(direction);
            int exactMatches = 0;
            int neighborMatches = 0;
            for (final int bucket : KnowledgeEmbeddingLsh.buckets(candidate)) {
                for (final int value : exact) if (bucket == value) exactMatches++;
                for (int j = 0; j < probes.length; j++) {
                    if (j % (KnowledgeEmbeddingLsh.BITS + 1) != 0
                            && bucket == probes[j]) neighborMatches++;
                }
            }
            assertEquals(i == 0 ? 1 : 0, exactMatches);
            assertEquals(i == 0 ? 1 : i == 1 ? 4 : 0, neighborMatches);
        }
    }

    @Test
    public void sourceQuotaQaFixtureHasOneNearExactBucketAndLateOnlyTarget() {
        final float[] query = KnowledgeEmbeddingVectors.normalize(
                new float[]{1, 0, 0, 0, 0, 0, 0, 0});
        final float[] near = KnowledgeEmbeddingVectors.normalize(
                new float[]{0.99f, 0.14106736f, 0, 0, 0, 0, 0, 0});
        final float[] hidden = KnowledgeEmbeddingVectors.normalize(
                new float[]{0.8f, 0.6f, 0, 0, 0, 0, 0, 0});
        final int[] exact = KnowledgeEmbeddingLsh.buckets(query);
        int nearExactMatches = 0;
        int hiddenExactMatches = 0;
        for (final int bucket : KnowledgeEmbeddingLsh.buckets(near)) {
            for (final int signature : exact) if (bucket == signature) nearExactMatches++;
        }
        for (final int bucket : KnowledgeEmbeddingLsh.buckets(hidden)) {
            for (final int signature : exact) if (bucket == signature) hiddenExactMatches++;
        }
        assertEquals(1, nearExactMatches);
        assertEquals(0, hiddenExactMatches);
        final List<KnowledgeEmbeddingChunks.Chunk> longChunks =
                KnowledgeEmbeddingChunks.fromNote(
                        new String(new char[3000]).replace('\0', 'x'));
        assertEquals(4, longChunks.size());
        assertEquals(770, longChunks.get(1).start);
        assertEquals(1540, longChunks.get(2).start);
        assertTrue(KnowledgeEmbeddingVectors.similarity(query,
                KnowledgeEmbeddingVectors.encode(query)) > .999);
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

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
    public void longNotesSampleBeginningMiddleAndEndWithinBounds() {
        final String source = "begin".concat(new String(new char[9000]).replace('\0', 'x'))
                .concat("last-topic");
        final List<KnowledgeEmbeddingChunks.Chunk> chunks =
                KnowledgeEmbeddingChunks.fromNote(source);
        assertEquals(KnowledgeEmbeddingChunks.MAX_CHUNKS_PER_NOTE, chunks.size());
        assertTrue(chunks.get(0).text.startsWith("begin"));
        assertTrue(chunks.get(2).text.contains("last-topic"));
        assertTrue(chunks.get(1).start > chunks.get(0).start);
        for (final KnowledgeEmbeddingChunks.Chunk chunk : chunks) {
            assertTrue(chunk.text.length() <= KnowledgeEmbeddingChunks.CHUNK_CHARS);
            assertEquals(source.substring(chunk.start, chunk.start + chunk.text.length()),
                    chunk.text);
        }
        assertTrue(KnowledgeEmbeddingChunks.atOffset(source, chunks.get(2).start)
                .contains("last-topic"));
        assertTrue(KnowledgeEmbeddingChunks.atOffset(source, source.length() + 1).isEmpty());
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

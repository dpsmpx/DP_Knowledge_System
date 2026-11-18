/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

/**
 * Deterministic multi-table locality-sensitive hashing of normalized vectors.
 * A broad candidate shortlist is reranked by exact cosine similarity in Java.
 * The lookup is approximate: nearest neighbors are not guaranteed.
 */
public final class KnowledgeEmbeddingLsh {
    public static final int TABLES = 4;
    public static final int BITS = 10;
    public static final int PROBES = TABLES * (BITS + 1);
    private static final int SAMPLES_PER_PLANE = 24;
    private static final int SIGNATURES_PER_TABLE = 1 << BITS;

    private KnowledgeEmbeddingLsh() {
    }

    public static int[] buckets(final float[] normalized) {
        if (normalized == null || normalized.length < KnowledgeEmbeddingVectors.MIN_DIMENSIONS
                || normalized.length > KnowledgeEmbeddingVectors.MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Invalid LSH source vector");
        }
        final int[] result = new int[TABLES];
        for (int table = 0; table < TABLES; table++) {
            int signature = 0;
            for (int bit = 0; bit < BITS; bit++) {
                double projection = 0;
                for (int sample = 0; sample < SAMPLES_PER_PLANE; sample++) {
                    final int hash = mix(0x9e3779b9 * (table + 1)
                            ^ 0x85ebca6b * (bit + 1) ^ 0xc2b2ae35 * (sample + 1));
                    final int coordinate = (hash & 0x7fffffff) % normalized.length;
                    projection += ((hash & 0x80000000) == 0 ? 1 : -1)
                            * normalized[coordinate];
                }
                if (projection >= 0) {
                    signature |= 1 << bit;
                }
            }
            result[table] = table * SIGNATURES_PER_TABLE + signature;
        }
        return result;
    }

    /** Query exact signatures and all Hamming-distance-one neighbors. */
    public static int[] probes(final float[] normalized) {
        final int[] signatures = buckets(normalized);
        final int[] result = new int[PROBES];
        int position = 0;
        for (final int signature : signatures) {
            result[position++] = signature;
            final int tableBase = signature & ~(SIGNATURES_PER_TABLE - 1);
            final int code = signature & (SIGNATURES_PER_TABLE - 1);
            for (int bit = 0; bit < BITS; bit++) {
                result[position++] = tableBase + (code ^ (1 << bit));
            }
        }
        return result;
    }

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }
}

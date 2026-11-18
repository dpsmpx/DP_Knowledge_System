/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Portable fixed-size float32 vectors, normalized before storage and comparison. */
public final class KnowledgeEmbeddingVectors {
    public static final int MIN_DIMENSIONS = 8;
    public static final int MAX_DIMENSIONS = 4096;

    private KnowledgeEmbeddingVectors() {
    }

    public static float[] normalize(final float[] vector) {
        if (vector == null || vector.length < MIN_DIMENSIONS
                || vector.length > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("Embedding dimensions are out of bounds");
        }
        double squared = 0;
        for (final float value : vector) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("Embedding contains non-finite values");
            }
            squared += (double) value * value;
        }
        if (!Double.isFinite(squared) || squared <= 0.0000000001) {
            throw new IllegalArgumentException("Embedding has zero or invalid norm");
        }
        final float[] result = new float[vector.length];
        final double norm = Math.sqrt(squared);
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }

    public static byte[] encode(final float[] normalized) {
        final ByteBuffer bytes = ByteBuffer.allocate(normalized.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (final float number : normalized) {
            bytes.putFloat(number);
        }
        return bytes.array();
    }

    /** Invalid or mismatched cached vectors must never affect the ranking. */
    public static double similarity(final float[] normalizedQuery, final byte[] encoded) {
        if (normalizedQuery == null || encoded == null
                || encoded.length != normalizedQuery.length * Float.BYTES) {
            return Double.NaN;
        }
        final ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        double sum = 0;
        for (final float value : normalizedQuery) {
            final float next = bytes.getFloat();
            if (!Float.isFinite(next)) {
                return Double.NaN;
            }
            sum += value * next;
        }
        return Double.isFinite(sum) ? sum : Double.NaN;
    }
}

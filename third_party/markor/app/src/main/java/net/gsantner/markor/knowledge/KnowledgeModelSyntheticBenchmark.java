/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;

/**
 * MODEL-M2 weight-free, deterministic CPU microbenchmarks for four optional
 * roles. Never reads installed weights, files, notes, journal or network.
 * These kernels are NOT model inference and cannot predict a model's speed,
 * memory peak, accuracy, tokenizer cost or CPU/GPU/NPU compatibility.
 */
final class KnowledgeModelSyntheticBenchmark {
    static final int TRIALS = 7;
    private static final int INPUT_VALUES = 16384;
    private static final long TRIAL_LIMIT_NANOS = 2_000_000_000L;

    static final class Report {
        final KnowledgeModelPolicy.Role role;
        final int trials;
        final int unitsPerTrial;
        final long medianNanos;
        final long p95Nanos;
        final long heapBeforeBytes;
        final long maxObservedHeapBytes;
        final long heapAfterBytes;
        final int checksum;

        Report(final KnowledgeModelPolicy.Role kind, final int units,
               final long[] elapsed, final long before, final long high,
               final long after, final int digest) {
            role = kind;
            trials = elapsed.length;
            unitsPerTrial = units;
            final long[] ordered = elapsed.clone();
            Arrays.sort(ordered);
            medianNanos = ordered[ordered.length / 2];
            p95Nanos = ordered[(int) Math.ceil(ordered.length * 0.95) - 1];
            heapBeforeBytes = before;
            maxObservedHeapBytes = high;
            heapAfterBytes = after;
            checksum = digest;
        }
    }

    private KnowledgeModelSyntheticBenchmark() { }

    static Report run(final KnowledgeModelPolicy.Role role) throws IOException {
        if (role == null) throw new IOException("Missing synthetic benchmark role");
        checkActive(0);
        final float[] input = new float[INPUT_VALUES];
        for (int i = 0; i < input.length; i++) {
            input[i] = ((i * 73 + 17) % 997 - 498) / 997.0f;
        }
        // One untimed warm-up before reporting CPU latencies.
        kernel(role, input, System.nanoTime() + TRIAL_LIMIT_NANOS);
        final Runtime runtime = Runtime.getRuntime();
        final long heapBefore = heapUsed(runtime);
        long observedHigh = heapBefore;
        final long[] elapsed = new long[TRIALS];
        int lastChecksum = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            checkActive(0);
            final long start = System.nanoTime();
            lastChecksum = kernel(role, input, start + TRIAL_LIMIT_NANOS);
            elapsed[trial] = Math.max(1L, System.nanoTime() - start);
            observedHigh = Math.max(observedHigh, heapUsed(runtime));
        }
        final long heapAfter = heapUsed(runtime);
        return new Report(role, units(role), elapsed, heapBefore,
                Math.max(observedHigh, heapAfter), heapAfter, lastChecksum);
    }

    static int units(final KnowledgeModelPolicy.Role role) {
        switch (role) {
            case EMBEDDING: return 96 * 128; // pairwise similarity scores
            case GENERATION: return 512; // greedy synthetic token choices
            case RERANKING: return 128 * 128; // reranked candidates
            case PERSONALIZATION: return 32 * 256; // synthetic training examples
            default: throw new IllegalArgumentException("Unexpected model role");
        }
    }

    private static long heapUsed(final Runtime runtime) {
        return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
    }

    private static void checkActive(final long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Synthetic benchmark cancelled");
        }
        if (deadline > 0 && System.nanoTime() - deadline >= 0) {
            throw new IOException("Synthetic benchmark trial exceeded time limit");
        }
    }

    private static int kernel(final KnowledgeModelPolicy.Role role, final float[] values,
                              final long deadline) throws IOException {
        switch (role) {
            case EMBEDDING: return embed(values, deadline);
            case GENERATION: return generate(values, deadline);
            case RERANKING: return rerank(values, deadline);
            case PERSONALIZATION: return personalize(values, deadline);
            default: throw new IOException("Unsupported synthetic role");
        }
    }

    private static int embed(final float[] values, final long deadline) throws IOException {
        float bestTotal = 0;
        for (int query = 0; query < 96; query++) {
            checkActive(deadline);
            float best = -2;
            for (int candidate = 0; candidate < 128; candidate++) {
                float dot = 0, qNorm = 0, cNorm = 0;
                for (int dimension = 0; dimension < 64; dimension++) {
                    final float q = values[(query * 67 + dimension) & (INPUT_VALUES - 1)];
                    final float c = values[(candidate * 131 + dimension) & (INPUT_VALUES - 1)];
                    dot += q * c;
                    qNorm += q * q;
                    cNorm += c * c;
                }
                best = Math.max(best, (float) (dot / Math.sqrt(qNorm * cNorm + 0.000001)));
            }
            bestTotal += best;
        }
        return Float.floatToIntBits(bestTotal);
    }

    private static int generate(final float[] values, final long deadline) throws IOException {
        int token = 17;
        for (int step = 0; step < 512; step++) {
            checkActive(deadline);
            float best = -Float.MAX_VALUE;
            int chosen = 0;
            for (int candidate = 0; candidate < 512; candidate++) {
                final float score = values[(token * 31 + candidate * 17 + step)
                        & (INPUT_VALUES - 1)] + candidate * 0.000001f;
                if (score > best) {
                    best = score;
                    chosen = candidate;
                }
            }
            token = chosen;
        }
        return token;
    }

    private static int rerank(final float[] values, final long deadline) throws IOException {
        float sum = 0;
        final float[] scores = new float[128];
        for (int query = 0; query < 128; query++) {
            checkActive(deadline);
            for (int candidate = 0; candidate < scores.length; candidate++) {
                float dot = 0;
                for (int dimension = 0; dimension < 64; dimension++) {
                    dot += values[(query * 67 + dimension) & (INPUT_VALUES - 1)]
                            * values[(candidate * 127 + dimension) & (INPUT_VALUES - 1)];
                }
                scores[candidate] = dot;
            }
            Arrays.sort(scores);
            sum += scores[scores.length - 1];
        }
        return Float.floatToIntBits(sum);
    }

    private static int personalize(final float[] values, final long deadline)
            throws IOException {
        final float[] weights = new float[64];
        for (int epoch = 0; epoch < 32; epoch++) {
            checkActive(deadline);
            for (int sample = 0; sample < 256; sample++) {
                float score = 0;
                for (int dimension = 0; dimension < weights.length; dimension++) {
                    score += weights[dimension]
                            * values[(sample * 67 + dimension) & (INPUT_VALUES - 1)];
                }
                final float error = (sample % 2 == 0 ? 1.0f : -1.0f) - score;
                for (int dimension = 0; dimension < weights.length; dimension++) {
                    weights[dimension] += 0.0005f * error
                            * values[(sample * 67 + dimension) & (INPUT_VALUES - 1)];
                }
            }
        }
        return Float.floatToIntBits(weights[0]);
    }
}

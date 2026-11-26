/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Explicit four-sample E5 tokenizer-only QA. No weights, notes, network,
 * inference, or automatic indexing. Fingerprint can be compared with the
 * offline pinned XLMRobertaTokenizer reference for the same .model bytes.
 */
final class KnowledgeE5GoldenQa {
    // Names are lexically sorted to make the cross-language digest canonical.
    static final String[] NAMES = {"emoji_query", "en_query", "ru_query", "zh_passage"};
    static final String[] TEXTS = {"😀 emoji", "hello world", "Привет мир", "北京天气"};
    static final KnowledgeE5Text.Purpose[] PURPOSES = {
            KnowledgeE5Text.Purpose.QUERY, KnowledgeE5Text.Purpose.QUERY,
            KnowledgeE5Text.Purpose.QUERY, KnowledgeE5Text.Purpose.PASSAGE
    };

    static final class Report {
        final String modelSha256;
        final String fingerprintSha256;
        final int[] counts;
        final long elapsedMillis;

        Report(final String modelSha, final String fingerprint,
               final int[] lengths, final long millis) {
            modelSha256 = modelSha;
            fingerprintSha256 = fingerprint;
            counts = lengths.clone();
            elapsedMillis = millis;
        }
    }

    private KnowledgeE5GoldenQa() { }

    static Report run(final Context context, final KnowledgeModelPackageStore packages,
                      final KnowledgeModelFiles.Entry entry) throws IOException {
        if (context == null || packages == null) throw new IOException("Missing QA context");
        final KnowledgeModelPackageStore.E5TokenizerFile tokenizer = packages.e5Tokenizer(entry);
        // A matching tokenizer alone is insufficient: require canonical E5
        // BERT/XLM-R config fields from separately rehashed installed JSON.
        packages.e5Config(entry);
        final int[][] ids = new int[NAMES.length][];
        final int[] lengths = new int[NAMES.length];
        final long start = System.nanoTime();
        for (int i = 0; i < NAMES.length; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Manual E5 tokenization cancelled");
            }
            final KnowledgeEmbeddingTensorContract.Inputs tensors =
                    KnowledgeE5SentencePiece.encode(context, tokenizer.file, tokenizer.bytes,
                            tokenizer.sha256, PURPOSES[i], TEXTS[i]);
            final long[] raw = tensors.inputIds()[0];
            ids[i] = new int[raw.length];
            lengths[i] = raw.length;
            for (int n = 0; n < raw.length; n++) {
                if (raw[n] < 0 || raw[n] > Integer.MAX_VALUE) {
                    throw new IOException("Tokenizer returned an invalid token ID");
                }
                ids[i][n] = (int) raw[n];
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Manual E5 tokenization cancelled");
        }
        return new Report(tokenizer.sha256, fingerprint(tokenizer.sha256, ids), lengths,
                Math.max(0, (System.nanoTime() - start) / 1_000_000L));
    }

    /** Canonical UTF-8 bytes must match scripts/e5_sentencepiece_reference.py. */
    static String fingerprint(final String modelSha, final int[][] samples) {
        if (modelSha == null || !modelSha.matches("[0-9a-f]{64}")
                || samples == null || samples.length != NAMES.length) {
            throw new IllegalArgumentException("Invalid golden fingerprint inputs");
        }
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            add(sha, "dpks-e5-golden-v1\n" + modelSha + "\n");
            for (int i = 0; i < NAMES.length; i++) {
                final int[] ids = samples[i];
                if (ids == null || ids.length < 3 || ids.length > 512
                        || ids[0] != 0 || ids[ids.length - 1] != 2) {
                    throw new IllegalArgumentException("Invalid golden token sequence");
                }
                final StringBuilder line = new StringBuilder(NAMES[i]).append('=');
                for (int n = 0; n < ids.length; n++) {
                    if (ids[n] < 0) throw new IllegalArgumentException("Negative E5 token ID");
                    if (n > 0) line.append(',');
                    line.append(ids[n]);
                }
                add(sha, line.append('\n').toString());
            }
            final StringBuilder hex = new StringBuilder(64);
            for (final byte item : sha.digest()) {
                hex.append(Character.forDigit((item >>> 4) & 15, 16));
                hex.append(Character.forDigit(item & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void add(final MessageDigest sha, final String value) {
        sha.update(value.getBytes(StandardCharsets.UTF_8));
    }
}

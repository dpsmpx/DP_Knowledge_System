/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Explicit, synchronous Android SentencePiece adapter for pinned E5 sidecars.
 * Caller must run off the UI thread, with a manually staged, SHA-pinned file.
 * This adapter does not make model weights or full ONNX inference runnable.
 */
final class KnowledgeE5SentencePiece {
    private KnowledgeE5SentencePiece() { }

    static KnowledgeEmbeddingTensorContract.Inputs encode(
            final Context context, final File tokenizerFile, final long expectedBytes,
            final String pinnedSha256, final KnowledgeE5Text.Purpose purpose,
            final String originalText) throws IOException {
        if (context == null || tokenizerFile == null || pinnedSha256 == null
                || !pinnedSha256.matches("[0-9a-f]{64}")
                || expectedBytes < 16 || expectedBytes > KnowledgeSentencePieceProbe.MAX_BYTES
                || !tokenizerFile.isFile() || tokenizerFile.length() != expectedBytes) {
            throw new IOException("E5 tokenizer requires a pinned local SentencePiece model");
        }
        // Fail before invoking the native parser if the local file is malformed
        // or isn't the expected BPE / raw XLM-R special-token arrangement.
        final KnowledgeSentencePieceProbe.Result metadata =
                KnowledgeSentencePieceProbe.inspect(tokenizerFile, expectedBytes);
        if (!metadata.looksLikeBpe() || metadata.unknownPieceId != 0
                || metadata.bosPieceId != 1 || metadata.eosPieceId != 2
                || metadata.padPieceId != -1) {
            throw new IOException("Incompatible E5 SentencePiece model metadata");
        }
        final String prepared = KnowledgeE5Text.prepare(purpose, originalText);
        try {
            synchronized (Python.class) {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(context.getApplicationContext()));
                }
            }
            final int[] ids = Python.getInstance().getModule("ksys_e5_tokenizer")
                    .callAttr("encode", tokenizerFile.getAbsolutePath(), expectedBytes,
                            pinnedSha256, prepared).toJava(int[].class);
            final int[] mask = new int[ids.length];
            Arrays.fill(mask, 1);
            return KnowledgeEmbeddingTensorContract.fromTokenizer(
                    ids, mask, 1, 0, 2, false);
        } catch (RuntimeException rejected) {
            // Never echo paths, text or native exception details (which can
            // accidentally contain user-supplied text) into diagnostics.
            throw new IOException("Native E5 SentencePiece tokenization unavailable or rejected", rejected);
        }
    }
}

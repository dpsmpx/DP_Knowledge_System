/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Bounded, read-only inspection of Google SentencePiece ModelProto metadata.
 * This is NOT a tokenizer: no text normalization, tokenization or model run.
 * Unknown protobuf extensions are skipped; no embedded path is followed.
 */
final class KnowledgeSentencePieceProbe {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_PIECES = 500_000;

    static final class Result {
        final int modelType; // TrainerSpec.ModelType: UNIGRAM=1, BPE=2.
        final int pieces;
        final int declaredVocab;
        final int unknownPieceId;
        final int bosPieceId;
        final int eosPieceId;
        final int padPieceId;
        final String normalizerName;
        final long charsMapBytes;
        final boolean addDummyPrefix;
        final boolean removeExtraWhitespaces;
        final boolean escapeWhitespaces;

        private Result(final State state) {
            modelType = state.modelType;
            pieces = state.pieces;
            declaredVocab = state.declaredVocab;
            unknownPieceId = state.unknownPieceId;
            bosPieceId = state.bosPieceId;
            eosPieceId = state.eosPieceId;
            padPieceId = state.padPieceId;
            normalizerName = state.normalizerName;
            charsMapBytes = state.charsMapBytes;
            addDummyPrefix = state.addDummyPrefix;
            removeExtraWhitespaces = state.removeExtraWhitespaces;
            escapeWhitespaces = state.escapeWhitespaces;
        }

        boolean looksLikeBpe() { return modelType == 2 && pieces > 0; }
    }

    private static final class State {
        int modelType = 1; // proto default UNIGRAM
        int pieces;
        int declaredVocab = -1;
        int unknownPieceId = -1;
        int bosPieceId = -1;
        int eosPieceId = -1;
        int padPieceId = -1;
        String normalizerName = "unspecified";
        long charsMapBytes = 0;
        boolean addDummyPrefix = true;
        boolean removeExtraWhitespaces = true;
        boolean escapeWhitespaces = true;
        boolean hasTrainer;
        boolean hasNormalizer;
    }

    private KnowledgeSentencePieceProbe() { }

    static Result inspect(final File input, final long expectedBytes) throws IOException {
        if (input == null || !input.isFile() || expectedBytes < 16
                || expectedBytes > MAX_BYTES || input.length() != expectedBytes) {
            throw new IOException("SentencePiece sidecar outside bounded input contract");
        }
        final State state = new State();
        try (RandomAccessFile file = new RandomAccessFile(input, "r")) {
            while (file.getFilePointer() < expectedBytes) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("SentencePiece inspection cancelled");
                }
                final Field field = next(file, expectedBytes);
                if (field.number == 1 && field.wire == 2) {
                    if (state.pieces >= MAX_PIECES) throw new IOException("Too many SentencePiece entries");
                    piece(file, field.end, state);
                    state.pieces++;
                } else if (field.number == 2 && field.wire == 2) {
                    if (state.hasTrainer) throw new IOException("Duplicate SentencePiece trainer spec");
                    state.hasTrainer = true;
                    trainer(file, field.end, state);
                } else if (field.number == 3 && field.wire == 2) {
                    if (state.hasNormalizer) throw new IOException("Duplicate SentencePiece normalizer spec");
                    state.hasNormalizer = true;
                    normalizer(file, field.end, state);
                }
                file.seek(field.end);
            }
        }
        if (!state.hasTrainer || !state.hasNormalizer || state.pieces == 0
                || (state.modelType != 1 && state.modelType != 2)) {
            throw new IOException("Unrecognized SentencePiece model metadata");
        }
        return new Result(state);
    }

    private static void piece(final RandomAccessFile file, final long end,
                              final State state) throws IOException {
        String token = null;
        int type = 1; // SentencePiece.NORMAL default
        while (file.getFilePointer() < end) {
            final Field field = next(file, end);
            if (field.number == 1 && field.wire == 2) {
                token = string(file, field.end, 128);
            } else if (field.number == 3 && field.wire == 0) {
                if (field.value < 1 || field.value > 6) {
                    throw new IOException("Invalid SentencePiece token type");
                }
                type = (int) field.value;
            }
            file.seek(field.end);
        }
        if (token == null || token.isEmpty() || type < 1 || type > 6) {
            throw new IOException("Invalid SentencePiece entry");
        }
        final int index = state.pieces;
        if ("<unk>".equals(token)) state.unknownPieceId = unique(state.unknownPieceId, index);
        if ("<s>".equals(token)) state.bosPieceId = unique(state.bosPieceId, index);
        if ("</s>".equals(token)) state.eosPieceId = unique(state.eosPieceId, index);
        if ("<pad>".equals(token)) state.padPieceId = unique(state.padPieceId, index);
    }

    private static int unique(final int old, final int next) throws IOException {
        if (old >= 0) throw new IOException("Duplicate SentencePiece special token");
        return next;
    }

    private static void trainer(final RandomAccessFile file, final long end,
                                final State state) throws IOException {
        while (file.getFilePointer() < end) {
            final Field field = next(file, end);
            if (field.number == 3 && field.wire == 0) {
                if (field.value != 1 && field.value != 2) {
                    throw new IOException("Unsupported SentencePiece trainer model type");
                }
                state.modelType = (int) field.value;
            } else if (field.number == 4 && field.wire == 0) {
                if (field.value < 1 || field.value > MAX_PIECES) {
                    throw new IOException("SentencePiece declared vocab exceeds limit");
                }
                state.declaredVocab = (int) field.value;
            }
            file.seek(field.end);
        }
    }

    private static void normalizer(final RandomAccessFile file, final long end,
                                   final State state) throws IOException {
        while (file.getFilePointer() < end) {
            final Field field = next(file, end);
            if (field.number == 1 && field.wire == 2) {
                state.normalizerName = string(file, field.end, 64);
                if (!state.normalizerName.isEmpty()
                        && !state.normalizerName.matches("[A-Za-z0-9_+-]{1,64}")) {
                    throw new IOException("Unsafe SentencePiece normalizer name");
                }
            } else if (field.number == 2 && field.wire == 2) {
                state.charsMapBytes = field.end - file.getFilePointer();
            } else if (field.number == 3 && field.wire == 0) {
                state.addDummyPrefix = bool(field.value);
            } else if (field.number == 4 && field.wire == 0) {
                state.removeExtraWhitespaces = bool(field.value);
            } else if (field.number == 5 && field.wire == 0) {
                state.escapeWhitespaces = bool(field.value);
            }
            file.seek(field.end);
        }
    }

    private static boolean bool(final long raw) throws IOException {
        if (raw != 0 && raw != 1) throw new IOException("Invalid SentencePiece bool");
        return raw == 1;
    }

    private static String string(final RandomAccessFile file, final long end,
                                 final int limit) throws IOException {
        final long size = end - file.getFilePointer();
        if (size < 0 || size > limit) {
            throw new IOException("SentencePiece metadata string too large");
        }
        final byte[] bytes = new byte[(int) size];
        file.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException bad) {
            throw new IOException("Invalid SentencePiece UTF-8 metadata", bad);
        }
    }

    private static final class Field {
        final int number;
        final int wire;
        final long value;
        final long end;

        Field(final int field, final int type, final long value, final long next) {
            number = field;
            wire = type;
            this.value = value;
            end = next;
        }
    }

    private static Field next(final RandomAccessFile file, final long end) throws IOException {
        final long tag = varint(file, end);
        final int wire = (int) (tag & 7);
        final long number = tag >>> 3;
        if (number < 1 || number > Integer.MAX_VALUE || wire == 3 || wire == 4) {
            throw new IOException("Unsupported protobuf field");
        }
        if (wire == 0) {
            final long value = varint(file, end);
            return new Field((int) number, wire, value, file.getFilePointer());
        }
        final long count;
        if (wire == 1) count = 8;
        else if (wire == 5) count = 4;
        else if (wire == 2) count = varint(file, end);
        else throw new IOException("Invalid protobuf wire type");
        final long start = file.getFilePointer();
        if (count < 0 || count > end - start) throw new IOException("Truncated protobuf field");
        return new Field((int) number, wire, 0, start + count);
    }

    private static long varint(final RandomAccessFile file, final long end) throws IOException {
        long result = 0;
        for (int i = 0; i < 10; i++) {
            if (file.getFilePointer() >= end) throw new IOException("Truncated protobuf varint");
            final int b = file.readUnsignedByte();
            if (i == 9 && (b & 0x7f) > 1) throw new IOException("Overflowed protobuf varint");
            result |= (long) (b & 0x7f) << (7 * i);
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("Overlong protobuf varint");
    }
}

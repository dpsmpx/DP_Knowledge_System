/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only ModelProto -> GraphProto -> ValueInfoProto tensor signature probe.
 * A seek skips initializer payloads: it NEVER loads weights, opens external
 * locations, creates an ORT session, or reads user notes. The owning package
 * gate must SHA-audit all installed files before calling this parser.
 */
final class KnowledgeE5OnnxMetadataProbe {
    private static final int MAX_TOP_AND_GRAPH_FIELDS = 100_000;
    private static final int MAX_NESTED_FIELDS = 128;
    private static final int MAX_VALUE_INFO_BYTES = 16 * 1024;
    private static final int MAX_TYPE_BYTES = 4096;
    private static final int MAX_NAME_BYTES = 64;

    static final class Report {
        final int inputs;
        final int outputs;
        final int dynamicAxes;
        final long modelBytes;
        final String descriptorSha256;
        // Only expected public E5 graph names survive requireE5Small().
        // These in-memory declarations are NEVER put into UI or Binder output.
        final Map<String, KnowledgeE5OnnxSignature.Tensor> inputSignature;
        final Map<String, KnowledgeE5OnnxSignature.Tensor> outputSignature;

        private Report(final Map<String, KnowledgeE5OnnxSignature.Tensor> in,
                       final Map<String, KnowledgeE5OnnxSignature.Tensor> out,
                       final int dynamic, final long bytes) {
            inputs = in.size();
            outputs = out.size();
            dynamicAxes = dynamic;
            modelBytes = bytes;
            descriptorSha256 = KnowledgeE5OnnxSignature.descriptorSha256(in, out);
            inputSignature = Collections.unmodifiableMap(new LinkedHashMap<>(in));
            outputSignature = Collections.unmodifiableMap(new LinkedHashMap<>(out));
        }
    }

    private static final class Decoder {
        final RandomAccessFile input;
        final long end;
        int fields;

        Decoder(final RandomAccessFile source, final long limit) {
            input = source;
            end = limit;
        }

        long tag(final int maxFields) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("ONNX metadata scan cancelled");
            }
            if (++fields > maxFields) throw new IOException("Too many ONNX metadata fields");
            final long value = varint();
            if (value >>> 3 == 0 || (value & 7) > 5 || (value & 7) == 3
                    || (value & 7) == 4) {
                throw new IOException("Invalid ONNX protobuf tag");
            }
            return value;
        }

        long varint() throws IOException {
            long result = 0;
            for (int index = 0; index < 10; index++) {
                if (input.getFilePointer() >= end) throw new IOException("Truncated ONNX varint");
                final int octet = input.readUnsignedByte();
                if (index == 9 && (octet & 0xfe) != 0) {
                    throw new IOException("Overflowed ONNX varint");
                }
                result |= (long) (octet & 127) << (7 * index);
                if ((octet & 128) == 0) return result;
            }
            throw new IOException("Overlong ONNX varint");
        }

        Decoder child(final long maxBytes) throws IOException {
            final long bytes = varint();
            if (bytes < 0 || bytes > maxBytes || bytes > end - input.getFilePointer()) {
                throw new IOException("ONNX metadata message outside bounds");
            }
            return new Decoder(input, input.getFilePointer() + bytes);
        }

        boolean more() throws IOException { return input.getFilePointer() < end; }

        void skip(final int wire) throws IOException {
            switch (wire) {
                case 0: varint(); return;
                case 1: seek(8); return;
                case 2: seek(varint()); return;
                case 5: seek(4); return;
                default: throw new IOException("Unsupported ONNX protobuf wire type");
            }
        }

        void seek(final long bytes) throws IOException {
            if (bytes < 0 || bytes > end - input.getFilePointer()) {
                throw new IOException("ONNX metadata crosses message boundary");
            }
            input.seek(input.getFilePointer() + bytes);
        }

        String name() throws IOException {
            final long length = varint();
            if (length < 1 || length > MAX_NAME_BYTES
                    || length > end - input.getFilePointer()) {
                throw new IOException("Unbounded ONNX tensor name");
            }
            final byte[] bytes = new byte[(int) length];
            input.readFully(bytes);
            for (final byte b : bytes) {
                if ((b < 'a' || b > 'z') && (b < 'A' || b > 'Z')
                        && (b < '0' || b > '9') && b != '_') {
                    throw new IOException("Unsupported ONNX tensor identifier");
                }
            }
            return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        }
    }

    private KnowledgeE5OnnxMetadataProbe() { }

    static Report inspect(final File file, final long expectedBytes,
                          final KnowledgeE5ConfigProfile.Result config) throws IOException {
        if (file == null || !file.isFile() || expectedBytes < 16
                || file.length() != expectedBytes || config == null) {
            throw new IOException("Missing pinned ONNX model or E5 config");
        }
        try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
            final Decoder model = new Decoder(source, expectedBytes);
            final Map<String, KnowledgeE5OnnxSignature.Tensor> inputs = new LinkedHashMap<>();
            final Map<String, KnowledgeE5OnnxSignature.Tensor> outputs = new LinkedHashMap<>();
            final int[] dynamicAxes = {0};
            boolean graphSeen = false;
            boolean irSeen = false;
            boolean opsetSeen = false;
            while (model.more()) {
                final long tag = model.tag(MAX_TOP_AND_GRAPH_FIELDS);
                final long field = tag >>> 3;
                final int wire = (int) (tag & 7);
                if (field == 1 && wire == 0) {
                    if (irSeen || model.varint() < 3) throw new IOException("Invalid ONNX IR version");
                    irSeen = true;
                } else if (field == 7 && wire == 2) {
                    if (graphSeen) throw new IOException("Multiple ONNX graphs");
                    graphSeen = true;
                    graph(model.child(expectedBytes), inputs, outputs, dynamicAxes);
                } else if (field == 8 && wire == 2) {
                    opsetSeen = true;
                    model.skip(wire);
                } else {
                    model.skip(wire);
                }
            }
            if (!graphSeen || !irSeen || !opsetSeen) {
                throw new IOException("ONNX graph or required header declarations missing");
            }
            try {
                KnowledgeE5OnnxSignature.requireE5Small(inputs, outputs, config);
            } catch (IllegalArgumentException mismatch) {
                throw new IOException("ONNX graph signature is not E5 Small", mismatch);
            }
            return new Report(inputs, outputs, dynamicAxes[0], expectedBytes);
        }
    }

    private static void graph(final Decoder graph,
                              final Map<String, KnowledgeE5OnnxSignature.Tensor> inputs,
                              final Map<String, KnowledgeE5OnnxSignature.Tensor> outputs,
                              final int[] dynamicAxes) throws IOException {
        while (graph.more()) {
            final long tag = graph.tag(MAX_TOP_AND_GRAPH_FIELDS);
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if ((field == 11 || field == 12) && wire == 2) {
                final Map<String, KnowledgeE5OnnxSignature.Tensor> selected =
                        field == 11 ? inputs : outputs;
                if (selected.size() >= 32) throw new IOException("Too many ONNX I/O tensors");
                valueInfo(graph.child(MAX_VALUE_INFO_BYTES), selected, dynamicAxes);
            } else {
                // Graph nodes, dense initializers and external-data declarations
                // are skipped by bounded seek; no tensor bytes are materialized.
                graph.skip(wire);
            }
        }
    }

    private static void valueInfo(final Decoder value,
                                  final Map<String, KnowledgeE5OnnxSignature.Tensor> into,
                                  final int[] dynamicAxes) throws IOException {
        String name = null;
        KnowledgeE5OnnxSignature.Tensor tensor = null;
        while (value.more()) {
            final long tag = value.tag(MAX_NESTED_FIELDS);
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                if (name != null) throw new IOException("Duplicate ONNX tensor name field");
                name = value.name();
            } else if (field == 2 && wire == 2) {
                if (tensor != null) throw new IOException("Duplicate ONNX type field");
                tensor = type(value.child(MAX_TYPE_BYTES), dynamicAxes);
            } else {
                value.skip(wire);
            }
        }
        if (name == null || tensor == null || into.put(name, tensor) != null) {
            throw new IOException("Missing or duplicate ONNX graph I/O declaration");
        }
    }

    private static KnowledgeE5OnnxSignature.Tensor type(final Decoder type,
                                                        final int[] dynamicAxes) throws IOException {
        KnowledgeE5OnnxSignature.Tensor tensor = null;
        while (type.more()) {
            final long tag = type.tag(MAX_NESTED_FIELDS);
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                if (tensor != null) throw new IOException("Duplicate ONNX tensor type");
                tensor = tensorType(type.child(MAX_TYPE_BYTES), dynamicAxes);
            } else if (wire == 2 && (field == 4 || field == 5 || field == 7
                    || field == 8 || field == 9)) {
                throw new IOException("E5 graph I/O is not a tensor");
            } else {
                type.skip(wire);
            }
        }
        if (tensor == null) throw new IOException("ONNX tensor type missing");
        return tensor;
    }

    private static KnowledgeE5OnnxSignature.Tensor tensorType(final Decoder tensor,
                                                              final int[] dynamicAxes) throws IOException {
        Long element = null;
        long[] shape = null;
        while (tensor.more()) {
            final long tag = tensor.tag(MAX_NESTED_FIELDS);
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 0) {
                if (element != null) throw new IOException("Duplicate ONNX element type");
                element = tensor.varint();
            } else if (field == 2 && wire == 2) {
                if (shape != null) throw new IOException("Duplicate ONNX tensor shape");
                shape = shape(tensor.child(MAX_TYPE_BYTES), dynamicAxes);
            } else {
                tensor.skip(wire);
            }
        }
        if (element == null || shape == null) {
            throw new IOException("ONNX tensor element type or rank unknown");
        }
        final KnowledgeE5OnnxSignature.DataType dataType = element == 7
                ? KnowledgeE5OnnxSignature.DataType.INT64
                : element == 1 ? KnowledgeE5OnnxSignature.DataType.FLOAT32
                : KnowledgeE5OnnxSignature.DataType.OTHER;
        return new KnowledgeE5OnnxSignature.Tensor(dataType, shape);
    }

    private static long[] shape(final Decoder shape, final int[] dynamicAxes) throws IOException {
        final long[] dimensions = new long[4]; // E5 I/O max rank is 3.
        int count = 0;
        while (shape.more()) {
            final long tag = shape.tag(MAX_NESTED_FIELDS);
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                if (count >= dimensions.length) throw new IOException("ONNX I/O rank too high");
                dimensions[count++] = dimension(shape.child(256));
                if (dimensions[count - 1] == -1) dynamicAxes[0]++;
            } else {
                shape.skip(wire);
            }
        }
        return java.util.Arrays.copyOf(dimensions, count);
    }

    private static long dimension(final Decoder dimension) throws IOException {
        boolean defined = false;
        long value = -1; // Symbolic or anonymous dimension, never echo its name.
        while (dimension.more()) {
            final long tag = dimension.tag(16);
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 0) {
                if (defined) throw new IOException("Ambiguous ONNX dimension");
                value = dimension.varint();
                if (value < 0) throw new IOException("Negative ONNX dimension");
                defined = true;
            } else if (field == 2 && wire == 2) {
                if (defined) throw new IOException("Ambiguous ONNX dimension");
                final long bytes = dimension.varint();
                if (bytes < 1 || bytes > 64) throw new IOException("Invalid ONNX symbolic axis");
                dimension.seek(bytes);
                defined = true;
            } else {
                dimension.skip(wire);
            }
        }
        return value;
    }
}

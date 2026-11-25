/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * A bounded, read-only GGUF/ONNX container probe. It identifies metadata
 * candidates; it DOES NOT load an inference engine, verify ONNX operators,
 * tensor data, tokenizer semantics or target ABI. All content is untrusted.
 *
 * Format references:
 * https://github.com/ggml-org/ggml/blob/master/docs/gguf.md
 * https://github.com/onnx/onnx/blob/main/onnx/onnx.proto
 */
final class KnowledgeModelContainerProbe {
    private static final long MAX_GGUF_METADATA_SCAN = 8L * 1024L * 1024L;
    private static final long MAX_GGUF_KEYS = 100_000L;
    private static final long MAX_ARRAY_MEMBERS = 20_000L;
    private static final long MAX_ONNX_FIELDS = 100_000L;
    private static final long MAX_GGUF_TENSOR_INFOS = 20_000L;
    private static final long MAX_ONNX_GRAPH_FIELDS = 100_000L;
    private static final long MAX_GGUF_DESCRIPTOR_SCAN = 32L * 1024L * 1024L;
    private static final long MAX_ONNX_GRAPH_NODES = 50_000L;
    private static final long MAX_ONNX_TYPED_VALUES = 32L;
    private static final long MAX_ONNX_VALUE_INFO_BYTES = 16L * 1024L;
    private static final long MAX_ONNX_NESTED_FIELDS = 128L;
    private static final int MAX_ONNX_SHAPE_DIMS = 16;

    enum State { METADATA_HINTS, PARTIAL, INVALID }
    enum DetailState { NOT_INSPECTED, DECLARATIONS_READ, PARTIAL, INVALID }

    private static final class Details {
        DetailState state = DetailState.NOT_INSPECTED;
        long tensorDescriptors;
        long nodes;
        long inputs;
        long outputs;
        long typedInputs;
        long typedOutputs;
        String operatorHints = "";
        String signatureHints = "";
    }

    static final class Result {
        final KnowledgeModelFiles.Format format;
        final State state;
        final long version;
        final long tensorCount;
        final long metadataCount;
        final String architecture;
        final String tokenizer;
        final int opsetDeclarations;
        final boolean graphDeclared;
        final DetailState detailState;
        final long tensorDescriptors;
        final long graphNodes;
        final long graphInputs;
        final long graphOutputs;
        final long typedInputs;
        final long typedOutputs;
        final String operatorHints;
        final String signatureHints;

        private Result(final KnowledgeModelFiles.Format containerFormat, final State outcome,
                       final long versionNumber, final long tensors, final long metadata,
                       final String family, final String tokenizerHint,
                       final int opsets, final boolean graph, final Details details) {
            format = containerFormat;
            state = outcome;
            version = versionNumber;
            tensorCount = tensors;
            metadataCount = metadata;
            architecture = family;
            tokenizer = tokenizerHint;
            opsetDeclarations = opsets;
            graphDeclared = graph;
            detailState = details.state;
            tensorDescriptors = details.tensorDescriptors;
            graphNodes = details.nodes;
            graphInputs = details.inputs;
            graphOutputs = details.outputs;
            typedInputs = details.typedInputs;
            typedOutputs = details.typedOutputs;
            operatorHints = details.operatorHints;
            signatureHints = details.signatureHints;
        }
    }

    private static final class ScanLimit extends IOException { }
    private static final class BadContainer extends IOException { }

    private KnowledgeModelContainerProbe() { }

    static Result inspect(final File file, final KnowledgeModelFiles.Format format)
            throws IOException {
        if (file == null || !file.isFile() || format == null) {
            throw new IOException("Container file unavailable");
        }
        try (RandomAccessFile data = new RandomAccessFile(file, "r")) {
            if (format == KnowledgeModelFiles.Format.GGUF) return inspectGguf(data);
            if (format == KnowledgeModelFiles.Format.ONNX_UNVERIFIED) return inspectOnnx(data);
            throw new IOException("Unsupported model container format");
        }
    }

    private static Result inspectGguf(final RandomAccessFile data) throws IOException {
        long version = 0, tensors = 0, metadata = 0;
        long alignment = 32;
        String architecture = "", tokenizer = "";
        final Details details = new Details();
        try {
            final byte[] magic = new byte[4];
            data.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G'
                    || magic[2] != 'U' || magic[3] != 'F') throw new BadContainer();
            version = readU32(data);
            if (version != 2 && version != 3) throw new BadContainer();
            tensors = readPositiveU64(data);
            metadata = readPositiveU64(data);
            if (metadata > MAX_GGUF_KEYS) throw new ScanLimit();
            for (long i = 0; i < metadata; i++) {
                checkInterrupted();
                guardMetadataPosition(data);
                final String key = readGgufString(data, 256);
                final long type = readU32(data);
                if ("general.architecture".equals(key) && type == 8) {
                    architecture = safeHint(readGgufString(data, 128));
                } else if ("tokenizer.ggml.model".equals(key) && type == 8) {
                    tokenizer = safeHint(readGgufString(data, 128));
                } else if ("general.alignment".equals(key) && (type == 4 || type == 10)) {
                    alignment = type == 4 ? readU32(data) : readPositiveU64(data);
                    if (alignment < 8 || alignment > 4096 || alignment % 8 != 0) {
                        throw new BadContainer();
                    }
                } else {
                    skipGgufValue(data, type, 0);
                }
            }
            inspectGgufTensorDescriptors(data, tensors, alignment, details);
            return gguf(tensors == 0 && metadata == 0 ? State.INVALID
                    : State.METADATA_HINTS, version, tensors, metadata,
                    architecture, tokenizer, details);
        } catch (ScanLimit limit) {
            details.state = DetailState.PARTIAL;
            return gguf(State.PARTIAL, version, tensors, metadata,
                    architecture, tokenizer, details);
        } catch (EOFException | BadContainer invalid) {
            details.state = DetailState.INVALID;
            return gguf(State.INVALID, version, tensors, metadata,
                    architecture, tokenizer, details);
        }
    }

    private static Result gguf(final State state, final long version,
                               final long tensors, final long keys, final String architecture,
                               final String tokenizer, final Details details) {
        return new Result(KnowledgeModelFiles.Format.GGUF, state, version, tensors, keys,
                architecture, tokenizer, 0, false, details);
    }

    /**
     * GGUF tensor descriptors are metadata only. We check bounded names,
     * dimensions and aligned offsets against the file but deliberately do NOT
     * infer tensor byte lengths from quantization types or load tensor data.
     */
    private static void inspectGgufTensorDescriptors(final RandomAccessFile data,
            final long count, final long alignment, final Details details) throws IOException {
        if (count > MAX_GGUF_TENSOR_INFOS) throw new ScanLimit();
        final Set<String> names = new HashSet<>();
        final long[] offsets = new long[(int) count];
        for (int index = 0; index < count; index++) {
            checkInterrupted();
            if (data.getFilePointer() > MAX_GGUF_DESCRIPTOR_SCAN) throw new ScanLimit();
            final String name = readGgufString(data, 64);
            if (name.isEmpty() || !names.add(name)) throw new BadContainer();
            final long dimensions = readU32(data);
            if (dimensions == 0) throw new BadContainer();
            if (dimensions > 4) throw new ScanLimit(); // Future GGUF may expand dims.
            for (int dim = 0; dim < (int) dimensions; dim++) {
                if (readPositiveU64(data) == 0) throw new BadContainer();
            }
            final long type = readU32(data);
            if (type > 255) throw new ScanLimit(); // Do not reject future quantizers.
            final long offset = readPositiveU64(data);
            if (offset % alignment != 0) throw new BadContainer();
            offsets[index] = offset;
            details.tensorDescriptors++;
        }
        final long position = data.getFilePointer();
        final long padding = (alignment - position % alignment) % alignment;
        if (padding > data.length() - position) throw new BadContainer();
        final long startOfData = position + padding;
        for (final long offset : offsets) {
            if (offset >= data.length() - startOfData) throw new BadContainer();
        }
        details.state = DetailState.DECLARATIONS_READ;
    }

    /** Reads the top-level protobuf tags only: no tensor allocation or execution. */
    private static Result inspectOnnx(final RandomAccessFile data) throws IOException {
        long ir = 0;
        int opsets = 0;
        boolean graph = false;
        final Details details = new Details();
        try {
            long fields = 0;
            while (data.getFilePointer() < data.length()) {
                checkInterrupted();
                if (++fields > MAX_ONNX_FIELDS) throw new ScanLimit();
                final long tag = readVarint(data);
                final long field = tag >>> 3;
                final int wire = (int) (tag & 7);
                if (field == 0) throw new BadContainer();
                if (field == 1 && wire == 0) {
                    ir = readVarint(data);
                } else if (field == 7 && wire == 2) {
                    final long size = readVarint(data);
                    if (size <= 0) throw new BadContainer();
                    graph = true;
                    inspectOnnxGraph(data, size, details);
                } else if (field == 8 && wire == 2) {
                    final long size = readVarint(data);
                    if (size < 2) throw new BadContainer();
                    if (readOpsetDeclaration(data, size)) opsets++;
                } else {
                    skipWire(data, wire);
                }
            }
            return onnx(ir > 0 && graph && opsets > 0
                    ? State.METADATA_HINTS : State.INVALID, ir, opsets, graph, details);
        } catch (ScanLimit limit) {
            details.state = DetailState.PARTIAL;
            return onnx(State.PARTIAL, ir, opsets, graph, details);
        } catch (EOFException | BadContainer invalid) {
            details.state = DetailState.INVALID;
            return onnx(State.INVALID, ir, opsets, graph, details);
        }
    }

    private static Result onnx(final State state, final long ir,
                               final int opsets, final boolean graph, final Details details) {
        return new Result(KnowledgeModelFiles.Format.ONNX_UNVERIFIED, state, ir,
                0, 0, "", "", opsets, graph, details);
    }

    /**
     * Walks only the direct fields of GraphProto, skipping huge TensorProto
     * initializers via bounded seeks. Input/output types and dimensions are
     * declaration hints, not validation against an inference backend.
     */
    private static void inspectOnnxGraph(final RandomAccessFile data, final long bytes,
                                         final Details details) throws IOException {
        if (bytes > data.length() - data.getFilePointer()) throw new BadContainer();
        final long end = data.getFilePointer() + bytes;
        long fields = 0;
        final Set<String> examples = new HashSet<>();
        while (data.getFilePointer() < end) {
            checkInterrupted();
            if (++fields > MAX_ONNX_GRAPH_FIELDS) throw new ScanLimit();
            final long tag = readVarint(data);
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 0) throw new BadContainer();
            if (wire == 2 && field == 1) {
                final long length = readVarint(data);
                if (++details.nodes > MAX_ONNX_GRAPH_NODES) throw new ScanLimit();
                inspectOnnxNode(data, length, details, examples);
            } else if (wire == 2 && (field == 11 || field == 12)) {
                final long length = readVarint(data);
                if (field == 11) details.inputs++;
                else details.outputs++;
                if (details.inputs + details.outputs > MAX_ONNX_TYPED_VALUES) {
                    throw new ScanLimit();
                }
                final String signature = inspectOnnxValueInfo(data, length, end);
                if (!signature.isEmpty()) {
                    if (field == 11) details.typedInputs++;
                    else details.typedOutputs++;
                    if (details.typedInputs + details.typedOutputs <= 4) {
                        details.signatureHints += (details.signatureHints.isEmpty() ? "" : "; ")
                                + (field == 11 ? "in:" : "out:") + signature;
                    }
                }
            } else {
                skipWire(data, wire);
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
        details.state = DetailState.DECLARATIONS_READ;
    }

    /** Reads bounded ValueInfoProto.name and tensor-only TypeProto declarations. */
    private static String inspectOnnxValueInfo(final RandomAccessFile data, final long bytes,
                                               final long parentEnd) throws IOException {
        final long end = boundedOnnxEnd(data, bytes, parentEnd, MAX_ONNX_VALUE_INFO_BYTES);
        String name = "", type = "";
        long fields = 0;
        while (data.getFilePointer() < end) {
            checkInterrupted();
            if (++fields > MAX_ONNX_NESTED_FIELDS) throw new ScanLimit();
            final long tag = readVarint(data);
            if (tag == 0) throw new BadContainer();
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                final long size = readVarint(data);
                if (size > end - data.getFilePointer()) throw new BadContainer();
                if (size <= 64) {
                    final byte[] raw = new byte[(int) size];
                    data.readFully(raw);
                    try {
                        name = safeHint(StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(raw)).toString());
                    } catch (CharacterCodingException invalid) {
                        throw new BadContainer();
                    }
                } else {
                    skipExact(data, size);
                }
            } else if (field == 2 && wire == 2) {
                final long size = readVarint(data);
                type = inspectOnnxType(data, size, end);
            } else {
                skipWire(data, wire);
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
        return type.isEmpty() ? "" : (name.isEmpty() ? "tensor:" : name + ":") + type;
    }

    /** Non-tensor and future ONNX type variants are left undecided, not rejected. */
    private static String inspectOnnxType(final RandomAccessFile data, final long bytes,
                                          final long parentEnd) throws IOException {
        final long end = boundedOnnxEnd(data, bytes, parentEnd, 4096);
        String tensorType = "";
        long fields = 0;
        while (data.getFilePointer() < end) {
            if (++fields > MAX_ONNX_NESTED_FIELDS) throw new ScanLimit();
            final long tag = readVarint(data);
            if (tag == 0) throw new BadContainer();
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                tensorType = inspectOnnxTensorType(data, readVarint(data), end);
            } else if ((field == 4 || field == 5 || field == 7 || field == 8
                    || field == 9) && wire == 2) {
                tensorType = ""; // TypeProto oneof: sequences/maps/etc are not decoded.
                skipExact(data, readVarint(data));
            } else {
                skipWire(data, wire);
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
        return tensorType;
    }

    private static String inspectOnnxTensorType(final RandomAccessFile data, final long bytes,
                                                final long parentEnd) throws IOException {
        final long end = boundedOnnxEnd(data, bytes, parentEnd, 4096);
        long elementType = 0;
        String shape = "?"; // Absent shape is unknown rank; [] is a scalar.
        long fields = 0;
        while (data.getFilePointer() < end) {
            if (++fields > MAX_ONNX_NESTED_FIELDS) throw new ScanLimit();
            final long tag = readVarint(data);
            if (tag == 0) throw new BadContainer();
            final long field = tag >>> 3;
            final int wire = (int) (tag & 7);
            if (field == 1 && wire == 0) {
                elementType = readVarint(data);
            } else if (field == 2 && wire == 2) {
                shape = inspectOnnxShape(data, readVarint(data), end);
            } else {
                skipWire(data, wire);
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
        if (elementType == 0) throw new BadContainer(); // TensorProto.UNDEFINED is not legal here.
        return onnxElementTypeHint(elementType) + shape;
    }

    private static String inspectOnnxShape(final RandomAccessFile data, final long bytes,
                                           final long parentEnd) throws IOException {
        final long end = boundedOnnxEnd(data, bytes, parentEnd, 4096);
        final StringBuilder dims = new StringBuilder("[");
        long fields = 0;
        int count = 0;
        while (data.getFilePointer() < end) {
            if (++fields > MAX_ONNX_NESTED_FIELDS) throw new ScanLimit();
            final long tag = readVarint(data);
            if (tag == 0) throw new BadContainer();
            if ((tag >>> 3) == 1 && (tag & 7) == 2) {
                if (++count > MAX_ONNX_SHAPE_DIMS) throw new ScanLimit();
                if (count > 1) dims.append(',');
                dims.append(inspectOnnxDimension(data, readVarint(data), end));
            } else {
                skipWire(data, (int) (tag & 7));
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
        return dims.append(']').toString();
    }

    private static String inspectOnnxDimension(final RandomAccessFile data, final long bytes,
                                               final long parentEnd) throws IOException {
        final long end = boundedOnnxEnd(data, bytes, parentEnd, 256);
        String dimension = "?";
        long fields = 0;
        while (data.getFilePointer() < end) {
            if (++fields > 16) throw new ScanLimit();
            final long tag = readVarint(data);
            if (tag == 0) throw new BadContainer();
            if ((tag >>> 3) == 1 && (tag & 7) == 0) {
                final long value = readVarint(data);
                dimension = value <= 1_000_000_000L ? Long.toString(value) : "large";
            } else if ((tag >>> 3) == 2 && (tag & 7) == 2) {
                skipExact(data, readVarint(data));
                dimension = "?"; // Symbolic dimension is intentionally not echoed.
            } else {
                skipWire(data, (int) (tag & 7));
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
        return dimension;
    }

    private static long boundedOnnxEnd(final RandomAccessFile data, final long bytes,
                                       final long parentEnd, final long maxBytes)
            throws IOException {
        if (bytes < 0 || bytes > parentEnd - data.getFilePointer()) throw new BadContainer();
        if (bytes > maxBytes) throw new ScanLimit();
        return data.getFilePointer() + bytes;
    }

    private static String onnxElementTypeHint(final long type) {
        if (type == 1) return "float32";
        if (type == 6) return "int32";
        if (type == 7) return "int64";
        if (type == 10) return "float16";
        if (type == 11) return "float64";
        if (type == 16) return "bfloat16";
        return "type#" + type; // Unknown and future enum values are not incompatible.
    }

    private static void inspectOnnxNode(final RandomAccessFile data, final long bytes,
                                        final Details details, final Set<String> examples)
            throws IOException {
        if (bytes > data.length() - data.getFilePointer()) throw new BadContainer();
        final long end = data.getFilePointer() + bytes;
        long fields = 0;
        while (data.getFilePointer() < end) {
            if (++fields > 1000) throw new ScanLimit();
            final long tag = readVarint(data);
            if (tag == 0) throw new BadContainer();
            if ((tag >>> 3) == 4 && (tag & 7) == 2) {
                final long size = readVarint(data);
                if (size > 64) {
                    skipExact(data, size);
                } else {
                    final byte[] dataBytes = new byte[(int) size];
                    data.readFully(dataBytes);
                    final String name;
                    try {
                        name = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(dataBytes)).toString();
                    } catch (CharacterCodingException invalid) {
                        throw new BadContainer();
                    }
                    final String safe = safeHint(name);
                    if (!safe.isEmpty() && examples.size() < 4 && examples.add(safe)) {
                        details.operatorHints += (details.operatorHints.isEmpty() ? "" : ", ") + safe;
                    }
                }
            } else {
                skipWire(data, (int) (tag & 7));
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
    }

    private static void skipWire(final RandomAccessFile data, final int wire) throws IOException {
        if (wire == 0) readVarint(data);
        else if (wire == 1) skipExact(data, 8);
        else if (wire == 2) skipExact(data, readVarint(data));
        else if (wire == 5) skipExact(data, 4);
        else throw new BadContainer(); // Groups are not used in ONNX ModelProto.
    }

    /** OperatorSetIdProto.version is field 2 (varint), domain is optional. */
    private static boolean readOpsetDeclaration(final RandomAccessFile data, final long size)
            throws IOException {
        if (size > 4096) throw new ScanLimit();
        final long start = data.getFilePointer();
        if (size > data.length() - start) throw new BadContainer();
        final long end = start + size;
        boolean versionPresent = false;
        while (data.getFilePointer() < end) {
            final long tag = readVarint(data);
            if (tag == 0) throw new BadContainer();
            if ((tag >>> 3) == 2 && (tag & 7) == 0) {
                versionPresent = readVarint(data) > 0;
            } else {
                skipWire(data, (int) (tag & 7));
            }
            if (data.getFilePointer() > end) throw new BadContainer();
        }
        return versionPresent;
    }

    private static long readVarint(final RandomAccessFile data) throws IOException {
        long result = 0;
        for (int i = 0; i < 10; i++) {
            final int b = data.readUnsignedByte();
            if (i == 9 && (b & 0xfe) != 0) throw new BadContainer();
            result |= (long) (b & 0x7f) << (i * 7);
            if ((b & 0x80) == 0) {
                if (result < 0) throw new BadContainer();
                return result;
            }
        }
        throw new BadContainer();
    }

    private static long readU32(final RandomAccessFile data) throws IOException {
        return Integer.reverseBytes(data.readInt()) & 0xffffffffL;
    }

    private static long readPositiveU64(final RandomAccessFile data) throws IOException {
        final long value = Long.reverseBytes(data.readLong());
        if (value < 0) throw new BadContainer();
        return value;
    }

    private static String readGgufString(final RandomAccessFile data, final int maxBytes)
            throws IOException {
        final long length = readPositiveU64(data);
        if (length > maxBytes) throw new ScanLimit();
        if (length > data.length() - data.getFilePointer()) throw new BadContainer();
        final byte[] bytes = new byte[(int) length];
        data.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new BadContainer();
        }
    }

    private static String safeHint(final String supplied) {
        // Do not let untrusted metadata inject lines or misleading labels.
        return supplied.matches("[a-zA-Z0-9_-]{1,48}") ? supplied : "";
    }

    private static void skipGgufValue(final RandomAccessFile data, final long type,
                                      final int nesting) throws IOException {
        checkInterrupted();
        if (type == 8) {
            skipExact(data, readPositiveU64(data));
            return;
        }
        if (type == 9) {
            if (nesting >= 3) throw new ScanLimit();
            final long itemType = readU32(data);
            final long count = readPositiveU64(data);
            if (count > MAX_ARRAY_MEMBERS) throw new ScanLimit();
            final int width = typeWidth(itemType);
            if (width != 0) {
                skipExact(data, count * width);
            } else {
                for (long i = 0; i < count; i++) {
                    if ((i & 255) == 0) checkInterrupted();
                    skipGgufValue(data, itemType, nesting + 1);
                    guardMetadataPosition(data);
                }
            }
            return;
        }
        skipExact(data, typeWidth(type));
    }

    private static int typeWidth(final long type) throws IOException {
        if (type == 0 || type == 1 || type == 7) return 1;
        if (type == 2 || type == 3) return 2;
        if (type == 4 || type == 5 || type == 6) return 4;
        if (type == 10 || type == 11 || type == 12) return 8;
        if (type == 8 || type == 9) return 0;
        throw new BadContainer();
    }

    private static void guardMetadataPosition(final RandomAccessFile data) throws IOException {
        if (data.getFilePointer() > MAX_GGUF_METADATA_SCAN) throw new ScanLimit();
    }

    private static void skipExact(final RandomAccessFile data, final long bytes) throws IOException {
        if (bytes < 0 || bytes > data.length() - data.getFilePointer()) {
            throw new BadContainer();
        }
        data.seek(data.getFilePointer() + bytes);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model preflight cancelled");
        }
    }
}

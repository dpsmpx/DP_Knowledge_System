/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.os.Debug;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** DPKS-only, explicit CPU smoke run against a generated, weight-free graph. */
final class KnowledgeOnnxCpuBackend {
    private KnowledgeOnnxCpuBackend() { }

    static boolean available() { return true; }

    /** Parse one user-approved, hash-verified model in memory; NEVER call run(). */
    static KnowledgeOnnxSessionGate.Report inspect(final byte[] modelBytes,
                                                   final KnowledgeOnnxMemoryTrace.Recorder memory)
            throws Exception {
        if (modelBytes == null || modelBytes.length < 16
                || modelBytes.length > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES) {
            throw new IOException("ONNX CPU input outside the size gate");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("ONNX CPU session inspection cancelled");
        }
        final long nativeBefore = Debug.getNativeHeapAllocatedSize();
        final OrtEnvironment environment = OrtEnvironment.getEnvironment();
        final long began = System.nanoTime();
        final long elapsed;
        final long inputs;
        final long outputs;
        final KnowledgeOnnxIoSummary summary;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            // No model filename is passed to native code, so paths from ONNX
            // external_data cannot be deliberately resolved via the API.
            try (OrtSession session = environment.createSession(modelBytes, options)) {
                memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
                elapsed = Math.max(0L, (System.nanoTime() - began) / 1_000_000L);
                inputs = session.getNumInputs();
                outputs = session.getNumOutputs();
                if (inputs < 1 || outputs < 1) {
                    throw new IOException("ONNX CPU session has no external I/O");
                }
                // Inspect runtime I/O shapes/types only in this isolated UID.
                // Names and symbolic dimension labels NEVER enter the report.
                final IoCounts in = countTensors(session.getInputInfo());
                final IoCounts out = countTensors(session.getOutputInfo());
                summary = new KnowledgeOnnxIoSummary(in.tensors, out.tensors,
                        in.floatCount, out.floatCount, in.int64Count, out.int64Count,
                        in.dynamicAxes, out.dynamicAxes, in.maxRank, out.maxRank);
                if (in.nodes != inputs || out.nodes != outputs
                        || !KnowledgeOnnxIoSummary.valid(summary, inputs, outputs)) {
                    throw new IOException("ONNX I/O metadata outside bounded diagnostic contract");
                }
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("ONNX CPU session inspection cancelled");
        }
        memory.complete(KnowledgeOnnxWorkerMemory.samplePssKiB());
        return new KnowledgeOnnxSessionGate.Report(environment.getVersion(), elapsed,
                Debug.getNativeHeapAllocatedSize() - nativeBefore, inputs, outputs,
                summary, memory.result(), KnowledgeOnnxRssHighWater.readSelf());
    }

    private static final class IoCounts {
        int nodes;
        int tensors;
        int floatCount;
        int int64Count;
        int dynamicAxes;
        int maxRank;
    }

    private static IoCounts countTensors(final Map<String, NodeInfo> nodes)
            throws IOException {
        if (nodes == null || nodes.isEmpty() || nodes.size() > KnowledgeOnnxIoSummary.MAX_IO) {
            throw new IOException("ONNX I/O node count outside bound");
        }
        final IoCounts found = new IoCounts();
        found.nodes = nodes.size();
        for (final NodeInfo node : nodes.values()) {
            if (node == null) throw new IOException("Missing ONNX I/O type");
            if (!(node.getInfo() instanceof TensorInfo)) continue;
            final TensorInfo tensor = (TensorInfo) node.getInfo();
            final long[] shape = tensor.getShape();
            if (shape == null || shape.length > KnowledgeOnnxIoSummary.MAX_RANK) {
                throw new IOException("ONNX tensor rank outside bound");
            }
            found.tensors++;
            if (tensor.type == OnnxJavaType.FLOAT) found.floatCount++;
            if (tensor.type == OnnxJavaType.INT64) found.int64Count++;
            found.maxRank = Math.max(found.maxRank, shape.length);
            for (final long dimension : shape) {
                if (dimension < 0) found.dynamicAxes++;
            }
        }
        return found;
    }

    static KnowledgeOnnxSmoke.Report smoke(final KnowledgeOnnxMemoryTrace.Recorder memory)
            throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("CPU smoke test cancelled");
        }
        final long nativeBefore = Debug.getNativeHeapAllocatedSize();
        final long started = System.nanoTime();
        final OrtEnvironment environment = OrtEnvironment.getEnvironment();
        final long initMillis;
        final long runMicros;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // CPU only, bounded native thread pools. No GPU/NNAPI/QNN provider and
            // no profiling file, remote endpoint or model-related background work.
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            try (OrtSession session = environment.createSession(
                    KnowledgeOnnxSmoke.identityModel(), options)) {
                memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
                initMillis = Math.max(0L,
                        (System.nanoTime() - started) / 1_000_000L);
                final FloatBuffer input = ByteBuffer.allocateDirect(4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                input.put(KnowledgeOnnxSmoke.INPUT);
                input.rewind();
                try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input,
                        new long[]{1})) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("CPU smoke test cancelled");
                    }
                    final long runStarted = System.nanoTime();
                    final float output;
                    try (OrtSession.Result result = session.run(
                            Collections.singletonMap("x", tensor))) {
                        if (result.size() != 1) throw new IOException("Wrong smoke output count");
                        final Object value = result.get(0).getValue();
                        if (!(value instanceof float[]) || ((float[]) value).length != 1) {
                            throw new IOException("Wrong smoke output shape");
                        }
                        output = ((float[]) value)[0];
                    }
                    runMicros = Math.max(0L,
                            (System.nanoTime() - runStarted) / 1_000L);
                    if (Float.compare(output, KnowledgeOnnxSmoke.EXPECTED_OUTPUT) != 0) {
                        throw new IOException("Incorrect synthetic ONNX CPU output");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("CPU smoke test cancelled");
                    }
                }
            }
            // One more all-synthetic path exercises INT64 named inputs,
            // rank-three FLOAT output and the shared masked-mean aggregator.
            // It never opens installed model files or note content.
            smokeEmbeddingTensors(environment, options, memory);
        }
        // Count AFTER the session, options, result and input tensor have closed.
        // This is a diagnostic native-heap delta, NOT peak RSS or model RAM.
        final long nativeDelta = Debug.getNativeHeapAllocatedSize() - nativeBefore;
        memory.complete(KnowledgeOnnxWorkerMemory.samplePssKiB());
        return new KnowledgeOnnxSmoke.Report(environment.getVersion(),
                initMillis, runMicros, nativeDelta, memory.result(),
                KnowledgeOnnxRssHighWater.readSelf());
    }

    private static void smokeEmbeddingTensors(final OrtEnvironment environment,
                                              final OrtSession.SessionOptions options,
                                              final KnowledgeOnnxMemoryTrace.Recorder memory)
            throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Synthetic embedding smoke cancelled");
        }
        final KnowledgeEmbeddingTensorContract.Inputs batch =
                KnowledgeEmbeddingTensorContract.fromTokenizer(
                        KnowledgeOnnxEmbeddingSmoke.IDS, KnowledgeOnnxEmbeddingSmoke.MASK,
                        1, 0, 2, false);
        try (OrtSession session = environment.createSession(
                KnowledgeOnnxEmbeddingSmoke.gatherModel(), options)) {
            KnowledgeE5OnnxSignature.require(signature(session.getInputInfo()),
                    signature(session.getOutputInfo()), KnowledgeOnnxEmbeddingSmoke.DIMENSIONS);
            memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
            try (OnnxTensor ids = OnnxTensor.createTensor(environment, batch.inputIds());
                 OnnxTensor mask = OnnxTensor.createTensor(environment, batch.attentionMask());
                 OnnxTensor types = OnnxTensor.createTensor(environment, batch.tokenTypeIds())) {
                final Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                inputs.put("input_ids", ids);
                inputs.put("attention_mask", mask);
                if (session.getInputInfo().containsKey("token_type_ids")) {
                    inputs.put("token_type_ids", types);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Synthetic embedding smoke cancelled");
                }
                try (OrtSession.Result result = session.run(inputs)) {
                    if (result.size() != 1 || !(result.get(0).getValue() instanceof float[][][])) {
                        throw new IOException("Synthetic embedding output has unexpected rank/type");
                    }
                    final float[] vector = KnowledgeEmbeddingTensorContract.poolAndNormalize(
                            (float[][][]) result.get(0).getValue(), batch,
                            KnowledgeOnnxEmbeddingSmoke.DIMENSIONS);
                    if (Math.abs(vector[0] - KnowledgeOnnxEmbeddingSmoke.EXPECTED_0) > .00001f
                            || Math.abs(vector[1] - KnowledgeOnnxEmbeddingSmoke.EXPECTED_1) > .00001f) {
                        throw new IOException("Synthetic embedding masked pooling mismatch");
                    }
                    for (int i = 2; i < vector.length; i++) {
                        if (Math.abs(vector[i]) > .00001f) {
                            throw new IOException("Unexpected synthetic embedding component");
                        }
                    }
                }
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Synthetic embedding smoke cancelled");
        }
    }

    private static Map<String, KnowledgeE5OnnxSignature.Tensor> signature(
            final Map<String, NodeInfo> nodes) throws IOException {
        if (nodes == null || nodes.size() > KnowledgeOnnxIoSummary.MAX_IO) {
            throw new IOException("Invalid ONNX synthetic graph I/O");
        }
        final Map<String, KnowledgeE5OnnxSignature.Tensor> results = new LinkedHashMap<>();
        for (final Map.Entry<String, NodeInfo> node : nodes.entrySet()) {
            if (node.getValue() == null || !(node.getValue().getInfo() instanceof TensorInfo)) {
                throw new IOException("Non-tensor ONNX synthetic graph I/O");
            }
            final TensorInfo tensor = (TensorInfo) node.getValue().getInfo();
            results.put(node.getKey(), new KnowledgeE5OnnxSignature.Tensor(
                    tensor.type == OnnxJavaType.INT64 ? KnowledgeE5OnnxSignature.DataType.INT64
                            : tensor.type == OnnxJavaType.FLOAT
                            ? KnowledgeE5OnnxSignature.DataType.FLOAT32
                            : KnowledgeE5OnnxSignature.DataType.OTHER,
                    tensor.getShape()));
        }
        return results;
    }
}

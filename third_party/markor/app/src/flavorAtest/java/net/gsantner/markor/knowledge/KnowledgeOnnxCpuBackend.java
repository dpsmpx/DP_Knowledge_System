/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.os.Debug;

import net.gsantner.markor.BuildConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
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

    /** Debug-only inference. The callee must pin received bytes before createSession(). */
    static KnowledgeOnnxSessionGate.Report runPinnedSyntheticE5(final Context workerContext,
                                       final byte[] received, final int variant,
                                       final KnowledgeOnnxMemoryTrace.Recorder memory)
            throws Exception {
        if (!BuildConfig.DEBUG || !KnowledgeOnnxProcessIdentity.isWorker()) {
            throw new IOException("Pinned E5 FD inference is debug-worker only");
        }
        KnowledgeE5IsolatedSmoke.requirePinnedGraph(received, variant);
        // Do not invoke inspect() first: that would create a native session
        // for untrusted bytes before the exact synthetic-fixture comparison.
        final OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            KnowledgeE5IsolatedSmoke.verifyTransported(
                    workerContext, received, variant, environment, options, memory);
        }
        // All 384 output values were already checked inside the isolated UID.
        // The Binder reply contains ONLY the existing bounded scalar I/O report.
        return inspect(received, memory);
    }

    /** Parse one user-approved, hash-verified model in memory; NEVER call run(). */
    static KnowledgeOnnxSessionGate.Report inspect(final byte[] modelBytes,
                                                   final KnowledgeOnnxMemoryTrace.Recorder memory)
            throws Exception {
        if (modelBytes == null || modelBytes.length < 16
                || modelBytes.length > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES) {
            throw new IOException("ONNX CPU input outside the size gate");
        }
        return inspectMetadata(modelBytes, null, memory);
    }

    /**
     * Native direct-buffer ORT metadata probe for a PINNED debug graph only.
     * The caller must keep Verified open until the session is closed. A SHA
     * match alone does not authorize a real installed E5 model for native ORT.
     */
    static KnowledgeOnnxSessionGate.Report inspectVerifiedDirectDebug(
            final Context workerContext, final KnowledgeE5DirectModelBuffer.Verified verified,
            final KnowledgeOnnxMemoryTrace.Recorder memory) throws Exception {
        if (!BuildConfig.DEBUG || !KnowledgeOnnxProcessIdentity.isWorker()
                || verified == null) {
            throw new IOException("Direct ONNX metadata probe is pinned-debug-worker only");
        }
        final ByteBuffer model = verified.view();
        if (!model.isDirect() || !model.isReadOnly() || model.position() != 0
                || model.remaining() < 16
                || model.remaining() > KnowledgeE5DirectModelBuffer.MAX_DIRECT_BYTES) {
            throw new IOException("Direct ONNX metadata input outside verified buffer gate");
        }
        // Snapshot AFTER the direct allocation, just before the native ORT
        // session. Never infer native safety from the earlier copy-stage gate.
        KnowledgeOnnxWorkerMemory.requireAvailableForDirect(
                workerContext, model.remaining(), true, false);
        return inspectMetadata(null, model, memory);
    }

    /** One read-only metadata path; the only difference is ORT's input API. */
    private static KnowledgeOnnxSessionGate.Report inspectMetadata(
            final byte[] modelBytes, final ByteBuffer direct,
            final KnowledgeOnnxMemoryTrace.Recorder memory) throws Exception {
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
            try (OrtSession session = direct == null
                    ? environment.createSession(modelBytes, options)
                    : environment.createSession(direct, options)) {
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
                        in.dynamicAxes, out.dynamicAxes, in.maxRank, out.maxRank,
                        e5DescriptorSha256(session.getInputInfo(), session.getOutputInfo()));
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

    /** Only the exact E5 metadata profile may generate a fingerprint for IPC. */
    private static String e5DescriptorSha256(final Map<String, NodeInfo> inputInfo,
                                             final Map<String, NodeInfo> outputInfo) {
        try {
            return KnowledgeE5OnnxSignature.descriptorSha256(
                    e5Tensors(inputInfo), e5Tensors(outputInfo));
        } catch (IllegalArgumentException invalidOrNonE5) {
            // Ordinary ONNX sessions still report bounded aggregate counts.
            return null;
        }
    }

    private static Map<String, KnowledgeE5OnnxSignature.Tensor> e5Tensors(
            final Map<String, NodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty() || nodes.size() > 3) {
            throw new IllegalArgumentException("Not bounded E5 I/O");
        }
        final Map<String, KnowledgeE5OnnxSignature.Tensor> descriptors =
                new LinkedHashMap<>();
        for (final Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            final NodeInfo node = entry.getValue();
            if (node == null || !(node.getInfo() instanceof TensorInfo)) {
                throw new IllegalArgumentException("Non-tensor E5 I/O");
            }
            final TensorInfo tensor = (TensorInfo) node.getInfo();
            final KnowledgeE5OnnxSignature.DataType type =
                    tensor.type == OnnxJavaType.INT64
                    ? KnowledgeE5OnnxSignature.DataType.INT64
                    : tensor.type == OnnxJavaType.FLOAT
                            ? KnowledgeE5OnnxSignature.DataType.FLOAT32
                            : KnowledgeE5OnnxSignature.DataType.OTHER;
            descriptors.put(entry.getKey(),
                    new KnowledgeE5OnnxSignature.Tensor(type, tensor.getShape()));
        }
        return descriptors;
    }

    static KnowledgeOnnxSmoke.Report smoke(final Context workerContext,
                                           final KnowledgeOnnxMemoryTrace.Recorder memory)
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
            // Exercise the canonical 384-channel E5 runner inside the actual
            // isolated service UID, not only the instrumentation process.
            KnowledgeE5IsolatedSmoke.verify(workerContext, environment, options, memory);
            if (BuildConfig.DEBUG) {
                // Both pinned E5 export forms open through the direct ByteBuffer
                // API for metadata ONLY. Never run() or inspect a user model here.
                smokePinnedDirectMetadata(workerContext, memory);
            }
        }
        // Count AFTER the session, options, result and input tensor have closed.
        // This is a diagnostic native-heap delta, NOT peak RSS or model RAM.
        final long nativeDelta = Debug.getNativeHeapAllocatedSize() - nativeBefore;
        memory.complete(KnowledgeOnnxWorkerMemory.samplePssKiB());
        return new KnowledgeOnnxSmoke.Report(environment.getVersion(),
                initMillis, runMicros, nativeDelta, memory.result(),
                KnowledgeOnnxRssHighWater.readSelf());
    }

    private static void smokePinnedDirectMetadata(final Context workerContext,
            final KnowledgeOnnxMemoryTrace.Recorder memory) throws Exception {
        if (!KnowledgeOnnxProcessIdentity.isWorker()) {
            throw new IOException("Direct E5 metadata smoke requires the worker UID");
        }
        for (final int variant : new int[]{
                KnowledgeOnnxIsolatedProtocol.DEBUG_E5_TWO_INPUT_POOLER,
                KnowledgeOnnxIsolatedProtocol.DEBUG_E5_THREE_INPUT}) {
            final boolean tokenTypes = variant
                    == KnowledgeOnnxIsolatedProtocol.DEBUG_E5_THREE_INPUT;
            final byte[] pinned = KnowledgeE5TokenEmbeddingSmoke.graph(
                    6, tokenTypes, !tokenTypes);
            try {
                // Byte equality against an independently regenerated graph is
                // required BEFORE any native session, even for debug fixtures.
                KnowledgeE5IsolatedSmoke.requirePinnedGraph(pinned, variant);
                KnowledgeOnnxWorkerMemory.requireAvailableForDirect(
                        workerContext, pinned.length, false, false);
                final KnowledgeOnnxMemoryTrace.Recorder directMemory =
                        new KnowledgeOnnxMemoryTrace.Recorder();
                directMemory.begin(KnowledgeOnnxWorkerMemory.samplePssKiB());
                try (KnowledgeE5DirectModelBuffer.Verified verified =
                             KnowledgeE5DirectModelBuffer.readVerified(
                                     new ByteArrayInputStream(pinned), pinned.length,
                                     KnowledgeOnnxSessionPipe.sha256Hex(pinned))) {
                    final KnowledgeOnnxSessionGate.Report metadata =
                            inspectVerifiedDirectDebug(workerContext, verified, directMemory);
                    final long expectedInputs = tokenTypes ? 3 : 2;
                    final long expectedOutputs = tokenTypes ? 1 : 2;
                    if (metadata.inputs != expectedInputs
                            || metadata.outputs != expectedOutputs
                            || metadata.ioSummary == null
                            || !pinnedDirectDescriptorSha256(tokenTypes).equals(
                                    metadata.ioSummary.e5DescriptorSha256)
                            || !KnowledgeOnnxIoSummary.valid(metadata.ioSummary,
                                    metadata.inputs, metadata.outputs)) {
                        throw new IOException("Pinned direct E5 ORT metadata mismatch");
                    }
                }
                memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB());
            } finally {
                Arrays.fill(pinned, (byte) 0);
            }
        }
    }

    /** Independent expected name/type/axis description for the six-token fixture. */
    private static String pinnedDirectDescriptorSha256(final boolean tokenTypes) {
        final Map<String, KnowledgeE5OnnxSignature.Tensor> inputs = new LinkedHashMap<>();
        final Map<String, KnowledgeE5OnnxSignature.Tensor> outputs = new LinkedHashMap<>();
        inputs.put("input_ids", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        inputs.put("attention_mask", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        if (tokenTypes) {
            inputs.put("token_type_ids", new KnowledgeE5OnnxSignature.Tensor(
                    KnowledgeE5OnnxSignature.DataType.INT64, 1, 6));
        }
        outputs.put("last_hidden_state", new KnowledgeE5OnnxSignature.Tensor(
                KnowledgeE5OnnxSignature.DataType.FLOAT32, 1, 6,
                KnowledgeE5TokenEmbeddingSmoke.DIMENSIONS));
        if (!tokenTypes) {
            outputs.put("pooler_output", new KnowledgeE5OnnxSignature.Tensor(
                    KnowledgeE5OnnxSignature.DataType.FLOAT32, 1,
                    KnowledgeE5TokenEmbeddingSmoke.DIMENSIONS));
        }
        return KnowledgeE5OnnxSignature.descriptorSha256(inputs, outputs);
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

    /** Reused by synthetic instrumentation when comparing ORT and protobuf I/O. */
    static Map<String, KnowledgeE5OnnxSignature.Tensor> signature(
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

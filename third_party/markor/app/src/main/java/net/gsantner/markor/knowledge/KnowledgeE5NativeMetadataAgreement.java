/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.util.Map;

/**
 * Fail-closed comparison of a SHA-pinned protobuf preflight and isolated ORT
 * metadata. Neither report is an inference authorization: this gate does not
 * run a model, approve its files or change its runnable state.
 */
final class KnowledgeE5NativeMetadataAgreement {
    private KnowledgeE5NativeMetadataAgreement() { }

    private static int dynamicAxes(final Map<String, KnowledgeE5OnnxSignature.Tensor> values) {
        int count = 0;
        for (final KnowledgeE5OnnxSignature.Tensor tensor : values.values()) {
            for (final long axis : tensor.shape()) {
                if (axis == -1) count++;
            }
        }
        return count;
    }

    static void requireMatching(final KnowledgeE5OnnxMetadataProbe.Report protobuf,
                                final long nativeInputs, final long nativeOutputs,
                                final KnowledgeOnnxIoSummary nativeIo) throws IOException {
        if (protobuf == null || protobuf.inputSignature == null
                || protobuf.outputSignature == null || nativeIo == null
                || protobuf.modelBytes < 16
                || protobuf.modelBytes > KnowledgeE5DirectModelBuffer.MAX_DIRECT_BYTES
                || !KnowledgeOnnxIoSummary.valid(nativeIo, nativeInputs, nativeOutputs)) {
            throw new IOException("Missing or invalid E5 metadata report");
        }

        // The full SHA covers exact allowlisted tensor names, types, and every
        // axis (including dynamic axes). No tensor names travel over Binder.
        final String independentlyDeclaredDigest;
        try {
            KnowledgeE5OnnxSignature.require(protobuf.inputSignature,
                    protobuf.outputSignature, KnowledgeE5ConfigProfile.HIDDEN_SIZE);
            independentlyDeclaredDigest = KnowledgeE5OnnxSignature.descriptorSha256(
                    protobuf.inputSignature, protobuf.outputSignature);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid protobuf E5 tensor contract", invalid);
        }
        final int declaredInputDynamics = dynamicAxes(protobuf.inputSignature);
        final int declaredOutputDynamics = dynamicAxes(protobuf.outputSignature);
        if (!KnowledgeOnnxSessionPipe.validSha256(protobuf.descriptorSha256)
                || !independentlyDeclaredDigest.equals(protobuf.descriptorSha256)
                || !protobuf.descriptorSha256.equals(nativeIo.e5DescriptorSha256)
                || protobuf.inputs != nativeInputs || protobuf.outputs != nativeOutputs
                || nativeIo.inputTensors != protobuf.inputs
                || nativeIo.outputTensors != protobuf.outputs
                || nativeIo.int64Inputs != protobuf.inputs || nativeIo.floatInputs != 0
                || nativeIo.floatOutputs != protobuf.outputs || nativeIo.int64Outputs != 0
                || nativeIo.maxInputRank != 2 || nativeIo.maxOutputRank != 3
                || protobuf.dynamicAxes != declaredInputDynamics + declaredOutputDynamics
                || declaredInputDynamics != nativeIo.dynamicInputAxes
                || declaredOutputDynamics != nativeIo.dynamicOutputAxes) {
            throw new IOException("E5 protobuf and isolated ORT metadata differ");
        }
    }
}

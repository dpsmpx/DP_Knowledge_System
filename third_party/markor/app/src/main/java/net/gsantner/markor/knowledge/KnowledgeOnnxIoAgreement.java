/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * Compare bounded, name-free ONNX GraphProto declarations with the native ORT
 * session report. Informational only: initializer-backed inputs and native
 * shape inference can legitimately differ from source declarations.
 */
final class KnowledgeOnnxIoAgreement {
    enum Verdict {
        COUNTS_AND_TENSORS_AGREE,
        COUNTS_AGREE_TYPES_PARTIAL,
        INPUT_OUTPUT_COUNTS_DIFFER,
        TENSOR_COUNTS_DIFFER,
        KNOWN_TYPE_COUNTS_DIFFER,
        UNKNOWN
    }

    private KnowledgeOnnxIoAgreement() { }

    static Verdict compare(final long declaredInputs, final long declaredOutputs,
                           final long typedInputs, final long typedOutputs,
                           final long floatInputs, final long floatOutputs,
                           final long int64Inputs, final long int64Outputs,
                           final long runtimeInputs, final long runtimeOutputs,
                           final KnowledgeOnnxIoSummary runtimeSummary) {
        if (declaredInputs < 1 || declaredInputs > KnowledgeOnnxIoSummary.MAX_IO
                || declaredOutputs < 1 || declaredOutputs > KnowledgeOnnxIoSummary.MAX_IO
                || typedInputs < 0 || typedInputs > declaredInputs
                || typedOutputs < 0 || typedOutputs > declaredOutputs
                || floatInputs < 0 || int64Inputs < 0
                || floatOutputs < 0 || int64Outputs < 0
                || floatInputs > typedInputs || int64Inputs > typedInputs
                || floatOutputs > typedOutputs || int64Outputs > typedOutputs
                || floatInputs + int64Inputs > typedInputs
                || floatOutputs + int64Outputs > typedOutputs
                || !KnowledgeOnnxIoSummary.valid(
                        runtimeSummary, runtimeInputs, runtimeOutputs)) return Verdict.UNKNOWN;
        if (declaredInputs != runtimeInputs || declaredOutputs != runtimeOutputs) {
            return Verdict.INPUT_OUTPUT_COUNTS_DIFFER;
        }
        // Only fully decoded tensor declarations permit comparison of tensor
        // totals. Other TypeProto variants remain explicitly undecided.
        if (typedInputs != declaredInputs || typedOutputs != declaredOutputs) {
            return Verdict.COUNTS_AGREE_TYPES_PARTIAL;
        }
        if (typedInputs != runtimeSummary.inputTensors
                || typedOutputs != runtimeSummary.outputTensors) {
            return Verdict.TENSOR_COUNTS_DIFFER;
        }
        // Only aggregate known categories are compared; other numeric types,
        // individual node-to-node mappings and shapes remain unverified.
        return floatInputs == runtimeSummary.floatInputs
                && floatOutputs == runtimeSummary.floatOutputs
                && int64Inputs == runtimeSummary.int64Inputs
                && int64Outputs == runtimeSummary.int64Outputs
                ? Verdict.COUNTS_AND_TENSORS_AGREE : Verdict.KNOWN_TYPE_COUNTS_DIFFER;
    }
}

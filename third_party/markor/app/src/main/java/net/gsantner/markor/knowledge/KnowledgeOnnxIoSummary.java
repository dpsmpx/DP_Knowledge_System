/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

/**
 * Bounded, name-free ONNX I/O metadata from a temporarily opened CPU session.
 * Counts and ranks are diagnostics only: no input names, symbolic dimension
 * strings, tensor payloads or claims of tokenizer/role compatibility cross IPC.
 */
final class KnowledgeOnnxIoSummary {
    static final int MAX_IO = 32;
    static final int MAX_RANK = 16;

    final int inputTensors;
    final int outputTensors;
    final int floatInputs;
    final int floatOutputs;
    final int int64Inputs;
    final int int64Outputs;
    final int dynamicInputAxes;
    final int dynamicOutputAxes;
    final int maxInputRank;
    final int maxOutputRank;

    KnowledgeOnnxIoSummary(final int inputTensors, final int outputTensors,
                           final int floatInputs, final int floatOutputs,
                           final int int64Inputs, final int int64Outputs,
                           final int dynamicInputAxes, final int dynamicOutputAxes,
                           final int maxInputRank, final int maxOutputRank) {
        this.inputTensors = inputTensors;
        this.outputTensors = outputTensors;
        this.floatInputs = floatInputs;
        this.floatOutputs = floatOutputs;
        this.int64Inputs = int64Inputs;
        this.int64Outputs = int64Outputs;
        this.dynamicInputAxes = dynamicInputAxes;
        this.dynamicOutputAxes = dynamicOutputAxes;
        this.maxInputRank = maxInputRank;
        this.maxOutputRank = maxOutputRank;
    }

    static boolean valid(final KnowledgeOnnxIoSummary info, final long inputs,
                         final long outputs) {
        if (info == null || inputs < 1 || inputs > MAX_IO
                || outputs < 1 || outputs > MAX_IO
                || info.inputTensors < 0 || info.inputTensors > inputs
                || info.outputTensors < 0 || info.outputTensors > outputs
                || info.floatInputs < 0 || info.int64Inputs < 0
                || info.floatOutputs < 0 || info.int64Outputs < 0
                || info.floatInputs + info.int64Inputs > info.inputTensors
                || info.floatOutputs + info.int64Outputs > info.outputTensors
                || info.maxInputRank < 0 || info.maxInputRank > MAX_RANK
                || info.maxOutputRank < 0 || info.maxOutputRank > MAX_RANK
                || info.dynamicInputAxes < 0 || info.dynamicOutputAxes < 0) return false;
        return info.dynamicInputAxes <= info.inputTensors * info.maxInputRank
                && info.dynamicOutputAxes <= info.outputTensors * info.maxOutputRank;
    }
}

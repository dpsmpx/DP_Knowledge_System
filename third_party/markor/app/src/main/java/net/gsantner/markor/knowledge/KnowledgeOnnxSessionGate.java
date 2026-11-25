/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;

/**
 * Conservative eligibility gate for ONE explicit in-memory ORT session check.
 * It is not a model compatibility certificate and never authorizes inference.
 */
final class KnowledgeOnnxSessionGate {
    static final long MAX_MODEL_BYTES = 16L * 1024L * 1024L;
    static final long MAX_PACKAGE_BYTES = 32L * 1024L * 1024L;
    static final long MIN_AVAILABLE_RAM_BYTES = 512L * 1024L * 1024L;

    static final class Report {
        final String runtimeVersion;
        final long sessionInitMillis;
        final long nativeHeapDeltaBytes;
        final long inputs;
        final long outputs;
        final KnowledgeOnnxIoSummary ioSummary;
        final KnowledgeOnnxMemoryTrace workerMemory;
        final long workerRssHighWaterKiB;

        Report(final String version, final long millis, final long delta,
               final long inputCount, final long outputCount,
               final KnowledgeOnnxIoSummary summary, final KnowledgeOnnxMemoryTrace memory,
               final long rssHighWaterKiB) {
            runtimeVersion = version;
            sessionInitMillis = millis;
            nativeHeapDeltaBytes = delta;
            inputs = inputCount;
            outputs = outputCount;
            ioSummary = summary;
            workerMemory = memory;
            workerRssHighWaterKiB = rssHighWaterKiB;
        }
    }

    private KnowledgeOnnxSessionGate() { }

    /** A fresh OS-wide snapshot gate, not a reservation or native peak-RAM guarantee. */
    static void requireAvailableMemory(final long availableBytes, final boolean lowMemory)
            throws IOException {
        if (lowMemory || availableBytes < MIN_AVAILABLE_RAM_BYTES) {
            throw new IOException("Not enough available RAM for optional CPU check");
        }
    }

    static void requireEligible(final KnowledgeModelFiles.Entry entry,
                                final KnowledgeModelContainerProbe.Result container)
            throws IOException {
        if (entry == null || entry.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED
                || entry.bytes < 16 || entry.bytes > MAX_MODEL_BYTES) {
            throw new IOException("Only small installed ONNX files can be inspected");
        }
        if (container == null
                || container.state != KnowledgeModelContainerProbe.State.METADATA_HINTS
                || container.detailState != KnowledgeModelContainerProbe.DetailState.DECLARATIONS_READ
                || !container.graphDeclared || container.opsetDeclarations < 1
                || container.graphNodes < 1 || container.graphNodes > 512
                || container.graphInputs < 1 || container.graphInputs > 32
                || container.graphOutputs < 1 || container.graphOutputs > 32
                || container.externalDataTensorsDeclared != 0
                || container.externalDataEntriesScanned != 0
                || !container.externalReferences.isEmpty()
                || container.sparseInitializersSkipped != 0
                || container.localFunctionDeclarations != 0
                || container.customDomainNodes != 0
                || container.undecodedOperatorNodes != 0) {
            throw new IOException("ONNX metadata is outside the bounded CPU session gate");
        }
    }
}

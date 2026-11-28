/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;

/**
 * Read-only, exact FILE-size accounting for the optional model shelf.
 * RAM availability is an OS snapshot, not a model RAM/peak estimate.
 * Manifest declarations are untrusted until the separate manual SHA audit.
 */
final class KnowledgeModelResourcePlan {
    enum Gate {
        GGUF_BACKEND_PENDING,
        MISSING_PACKAGE,
        EXTERNAL_DATA_PENDING,
        MODEL_TOO_LARGE,
        PACKAGE_TOO_LARGE,
        RAM_UNKNOWN,
        RAM_TOO_LOW,
        RESOURCES_ONLY_PASSED
    }

    static final class Report {
        final long weightBytes;
        final long externalBytes;
        final long otherCompanionBytes;
        final long totalDeclaredBytes;
        final int externalFiles;
        final long availableRamBytes;
        final long freeStorageBytes;
        final Gate gate;

        Report(final long weight, final long external, final long other,
               final int shardCount, final long ram, final long storage, final Gate gateResult)
                throws IOException {
            weightBytes = weight;
            externalBytes = external;
            otherCompanionBytes = other;
            externalFiles = shardCount;
            try {
                totalDeclaredBytes = Math.addExact(weight, Math.addExact(external, other));
            } catch (ArithmeticException overflow) {
                throw new IOException("Model package declared byte count overflow", overflow);
            }
            availableRamBytes = ram;
            freeStorageBytes = storage;
            gate = gateResult;
        }
    }

    private KnowledgeModelResourcePlan() { }

    static Report plan(final KnowledgeModelFiles.Entry entry,
                       final KnowledgeModelPackageManifest manifest,
                       final long availableRam, final long freeStorage) throws IOException {
        if (entry == null || entry.format == null || entry.bytes < 16) {
            throw new IOException("Invalid installed model metadata");
        }
        long externalBytes = 0, otherBytes = 0;
        int externalFiles = 0;
        if (manifest != null) {
            manifest.requireWeight(entry);
            for (final KnowledgeModelPackageManifest.Component part : manifest.components) {
                if (part.kind == KnowledgeModelPackageManifest.Kind.WEIGHTS) continue;
                try {
                    if (part.kind == KnowledgeModelPackageManifest.Kind.ONNX_EXTERNAL_DATA) {
                        externalBytes = Math.addExact(externalBytes, part.bytes);
                        externalFiles++;
                    } else {
                        otherBytes = Math.addExact(otherBytes, part.bytes);
                    }
                } catch (ArithmeticException overflow) {
                    throw new IOException("Model sidecar size overflow", overflow);
                }
            }
        }
        final long total;
        try {
            total = Math.addExact(entry.bytes, Math.addExact(externalBytes, otherBytes));
        } catch (ArithmeticException overflow) {
            throw new IOException("Model package total size overflow", overflow);
        }
        final Gate gate;
        if (entry.format != KnowledgeModelFiles.Format.ONNX_UNVERIFIED) {
            gate = Gate.GGUF_BACKEND_PENDING;
        } else if (manifest == null) {
            gate = Gate.MISSING_PACKAGE;
        } else if (externalFiles != 0) {
            gate = Gate.EXTERNAL_DATA_PENDING;
        } else if (entry.bytes > KnowledgeOnnxSessionGate.MAX_MODEL_BYTES) {
            gate = Gate.MODEL_TOO_LARGE;
        } else if (total > KnowledgeOnnxSessionGate.MAX_PACKAGE_BYTES) {
            gate = Gate.PACKAGE_TOO_LARGE;
        } else if (availableRam <= 0) {
            gate = Gate.RAM_UNKNOWN;
        } else if (availableRam < KnowledgeOnnxSessionGate.MIN_AVAILABLE_RAM_BYTES) {
            gate = Gate.RAM_TOO_LOW;
        } else {
            gate = Gate.RESOURCES_ONLY_PASSED;
        }
        return new Report(entry.bytes, externalBytes, otherBytes, externalFiles,
                Math.max(0, availableRam), Math.max(0, freeStorage), gate);
    }
}

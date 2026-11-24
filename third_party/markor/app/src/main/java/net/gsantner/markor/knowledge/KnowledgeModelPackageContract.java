/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MODEL-M1: explicit package-readiness manifest for one installed weight blob.
 * A SHA-verified GGUF/ONNX file alone NEVER proves runtime compatibility.
 * Reports name missing evidence, not simulated capabilities or inferred speed.
 * This is a versioned contract for future tokenizer/config/adapter installers.
 */
final class KnowledgeModelPackageContract {
    static final int SCHEMA_VERSION = 1;

    enum Requirement {
        PINNED_REVISION,
        LICENSE_REVIEW,
        TOKENIZER,
        MODEL_CONFIG,
        CHAT_TEMPLATE,
        EMBEDDING_POOLING,
        RERANKER_SCORE,
        FEATURE_LABEL_SCHEMA,
        VALIDATED_ANDROID_BACKEND,
        UNSUPPORTED_ROLE_FORMAT
    }

    static final class Report {
        final int schemaVersion;
        final String weightSha256;
        final String sourceId;
        final String license;
        final String engineCandidate;
        final boolean curatedWeight;
        final List<Requirement> missing;

        private Report(final KnowledgeModelFiles.Entry entry,
                       final KnowledgeModelCatalog.Artifact known,
                       final List<Requirement> requirements) {
            schemaVersion = SCHEMA_VERSION;
            weightSha256 = entry.sha256;
            curatedWeight = known != null;
            sourceId = curatedWeight ? known.id : "user-imported/unverified";
            license = curatedWeight ? known.license : "unverified";
            engineCandidate = entry.format == KnowledgeModelFiles.Format.GGUF
                    ? "llama.cpp / GGUF candidate" : "ONNX Runtime Mobile candidate";
            missing = Collections.unmodifiableList(new ArrayList<>(requirements));
        }

        boolean runnable() {
            // No backend has yet passed Android architecture/tokenizer checks.
            // A future release may only change this after verified components.
            return false;
        }
    }

    private KnowledgeModelPackageContract() { }

    static Report inspect(final KnowledgeModelFiles.Entry entry) {
        if (entry == null || entry.role == null || entry.format == null
                || entry.sha256 == null || !entry.sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid package identity");
        }
        final KnowledgeModelCatalog.Artifact candidate =
                KnowledgeModelCatalog.findInstalled(entry.role, entry.sha256);
        final KnowledgeModelCatalog.Artifact known = candidate != null &&
                ((entry.format == KnowledgeModelFiles.Format.GGUF
                        && candidate.filename.endsWith(".gguf"))
                || (entry.format == KnowledgeModelFiles.Format.ONNX_UNVERIFIED
                        && candidate.filename.endsWith(".onnx"))) ? candidate : null;
        final List<Requirement> missing = new ArrayList<>();
        // Even curated main-branch weights are byte-pinned, not HF revision-pinned.
        missing.add(Requirement.PINNED_REVISION);
        if (known == null) missing.add(Requirement.LICENSE_REVIEW);
        switch (entry.role) {
            case EMBEDDING:
                missing.add(Requirement.TOKENIZER);
                if (entry.format == KnowledgeModelFiles.Format.ONNX_UNVERIFIED) {
                    missing.add(Requirement.MODEL_CONFIG);
                }
                missing.add(Requirement.EMBEDDING_POOLING);
                break;
            case GENERATION:
                missing.add(Requirement.TOKENIZER);
                if (entry.format == KnowledgeModelFiles.Format.ONNX_UNVERIFIED) {
                    missing.add(Requirement.MODEL_CONFIG);
                }
                missing.add(Requirement.CHAT_TEMPLATE);
                break;
            case RERANKING:
                missing.add(Requirement.TOKENIZER);
                if (entry.format == KnowledgeModelFiles.Format.ONNX_UNVERIFIED) {
                    missing.add(Requirement.MODEL_CONFIG);
                }
                missing.add(Requirement.RERANKER_SCORE);
                break;
            case PERSONALIZATION:
                if (entry.format == KnowledgeModelFiles.Format.GGUF) {
                    missing.add(Requirement.UNSUPPORTED_ROLE_FORMAT);
                }
                missing.add(Requirement.FEATURE_LABEL_SCHEMA);
                break;
            default:
                throw new IllegalArgumentException("Unknown package role");
        }
        missing.add(Requirement.VALIDATED_ANDROID_BACKEND);
        return new Report(entry, known, missing);
    }
}

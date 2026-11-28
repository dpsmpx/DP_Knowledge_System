/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Audited single-file Hugging Face artifact suggestions for MODEL-M1.
 * SHA-256 values refer to the exact weight files shown on their HF blob pages.
 * An audited artifact is NOT a validated Android inference model: tokenizer,
 * backend, ops, packaging and runtime resource needs must be checked later.
 * The source's 'main' ref can move, but the expected digest is mandatory.
 */
final class KnowledgeModelCatalog {
    /** Download guidance only: companions require a manually audited manifest. */
    static final class Companion {
        final String filename;
        final String sourcePage;
        final String expectedSha256;

        Companion(final String localName, final String upstreamName, final String digest) {
            if (localName == null || !localName.matches("[A-Za-z0-9._-]{1,96}")
                    || upstreamName == null || !upstreamName.equals("onnx/" + localName)
                    || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid E5 companion reference");
            }
            filename = localName;
            sourcePage = "https://huggingface.co/intfloat/multilingual-e5-small/blob/main/"
                    + upstreamName;
            expectedSha256 = digest;
        }
    }

    static final class Artifact {
        final KnowledgeModelPolicy.Role role;
        final String id;
        final String title;
        final String sourcePage;
        final String downloadUrl;
        final String filename;
        final String expectedSha256;
        final String license;
        final int approximateMegabytes;

        Artifact(final KnowledgeModelPolicy.Role kind, final String identity, final String label,
                 final String repositoryPage, final String directUrl, final String digest,
                 final String licenseId, final int approximateSizeMB) {
            if (kind == null || identity == null || identity.isEmpty()
                    || label == null || label.isEmpty()
                    || repositoryPage == null || !repositoryPage.startsWith("https://huggingface.co/")
                    || licenseId == null || licenseId.isEmpty() || approximateSizeMB <= 0
                    || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid curated model artifact");
            }
            try {
                final KnowledgeModelDownload.Source source = KnowledgeModelDownload.parseSource(directUrl);
                if (!"huggingface.co".equalsIgnoreCase(source.uri.getHost())
                        || !directUrl.startsWith(repositoryPage + "/resolve/main/")) {
                    throw new IllegalArgumentException("Artifact source is not its declared model");
                }
                filename = source.filename;
            } catch (IOException invalid) {
                throw new IllegalArgumentException("Invalid direct artifact URL", invalid);
            }
            role = kind;
            id = identity;
            title = label;
            sourcePage = repositoryPage;
            downloadUrl = directUrl;
            expectedSha256 = digest;
            license = licenseId;
            approximateMegabytes = approximateSizeMB;
        }
    }

    private static final String HF = "https://huggingface.co/";

    private static Artifact file(final KnowledgeModelPolicy.Role role, final String repository,
                                 final String filename, final String label,
                                 final String license, final int sizeMB, final String sha256) {
        final String page = HF + repository;
        return new Artifact(role, repository + ":" + filename, label, page,
                page + "/resolve/main/" + filename, sha256, license, sizeMB);
    }

    static final List<Artifact> ARTIFACTS = Collections.unmodifiableList(Arrays.asList(
            file(KnowledgeModelPolicy.Role.EMBEDDING, "intfloat/multilingual-e5-small",
                    "onnx/model_O4.onnx", "Multilingual E5 Small · ONNX O4", "MIT", 235,
                    "4654c156f3e4171abc9c716cdb771bf9116455d15ac1aab364aeeede0e3205b0"),
            file(KnowledgeModelPolicy.Role.EMBEDDING, "intfloat/multilingual-e5-small",
                    "onnx/model.onnx", "Multilingual E5 Small · ONNX FP32", "MIT", 470,
                    "ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665"),
            file(KnowledgeModelPolicy.Role.GENERATION, "unsloth/Qwen3-0.6B-GGUF",
                    "Qwen3-0.6B-Q4_K_M.gguf", "Qwen3 0.6B · Unsloth Q4_K_M", "Apache-2.0", 397,
                    "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"),
            file(KnowledgeModelPolicy.Role.GENERATION, "Qwen/Qwen3-0.6B-GGUF",
                    "Qwen3-0.6B-Q8_0.gguf", "Qwen3 0.6B · publisher Q8_0", "Apache-2.0", 639,
                    "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"),
            file(KnowledgeModelPolicy.Role.GENERATION, "Qwen/Qwen3-1.7B-GGUF",
                    "Qwen3-1.7B-Q8_0.gguf", "Qwen3 1.7B · publisher Q8_0", "Apache-2.0", 1830,
                    "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a"),
            file(KnowledgeModelPolicy.Role.RERANKING,
                    "ExpedientFalcon/Qwen3-Reranker-0.6B-Q4_K_M-GGUF",
                    "qwen3-reranker-0.6b-q4_k_m.gguf", "Qwen3 Reranker 0.6B · community Q4", "Apache-2.0", 396,
                    "d03631450a5f7888956d5ebcaed5c601a4abc09487fae3d2ed15b2ea94528112"),
            file(KnowledgeModelPolicy.Role.RERANKING,
                    "ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF",
                    "qwen3-reranker-0.6b-q8_0.gguf", "Qwen3 Reranker 0.6B · ggml Q8", "Apache-2.0", 639,
                    "22c9979ce4fbcdc5acdc310c6641c32797eff1aa980b8f7a2db8a8ea23429a48")
    ));

    // Official onnx/ filenames flattened into a local package. The two JSON
    // hashes were calculated from the upstream raw bytes (653 and 443 bytes),
    // while the SentencePiece hash is published on the HF blob page.
    // SHA matching is a file identity check, not proof of common Git revision.
    private static final List<Companion> E5_COMPANIONS =
            Collections.unmodifiableList(Arrays.asList(
                    new Companion("sentencepiece.bpe.model", "onnx/sentencepiece.bpe.model",
                            "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865"),
                    new Companion("config.json", "onnx/config.json",
                            "bbb7c1333fc4b3e27fbc9cd5d2070aabcc1d4dfb99917c3633e772f97545a6b6"),
                    new Companion("tokenizer_config.json", "onnx/tokenizer_config.json",
                            "a1d6bc8734a6f635dc158508bef000f8e2e5a759c7d92f984b2c86e5ff53425b")));

    private KnowledgeModelCatalog() { }

    static List<Artifact> forRole(final KnowledgeModelPolicy.Role role) {
        final List<Artifact> matches = new ArrayList<>();
        for (final Artifact file : ARTIFACTS) {
            if (file.role == role) matches.add(file);
        }
        return Collections.unmodifiableList(matches);
    }

    static Artifact findInstalled(final KnowledgeModelPolicy.Role role, final String digest) {
        for (final Artifact artifact : ARTIFACTS) {
            if (artifact.role == role && artifact.expectedSha256.equals(digest)) return artifact;
        }
        return null;
    }

    static List<Companion> e5Companions(final KnowledgeModelPolicy.Role role,
                                        final String weightDigest) {
        final Artifact candidate = findInstalled(role, weightDigest);
        if (candidate == null || role != KnowledgeModelPolicy.Role.EMBEDDING
                || !candidate.id.startsWith("intfloat/multilingual-e5-small:onnx/")) {
            return Collections.emptyList();
        }
        return E5_COMPANIONS;
    }
}

/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * MODEL-M0: an optional provider is not permission to disclose source text.
 * This policy has no Android, network or inference dependency. No code may
 * silently replace an unavailable local provider with a network provider.
 */
final class KnowledgeModelPolicy {
    enum Role { EMBEDDING, GENERATION, RERANKING, PERSONALIZATION }
    enum Provider { OFF, ANDROID, PERSONAL_PC, CLOUD }
    enum Gate { OFF, NOT_CONFIGURED, NOT_IMPLEMENTED, NEEDS_NETWORK_CONSENT, READY_LOCAL }

    static final class Candidate {
        final Role role;
        final String label;
        final String url;
        final String note;

        Candidate(final Role type, final String name, final String page, final String hint) {
            role = type;
            label = name;
            url = page;
            note = hint;
        }
    }

    static final List<Candidate> CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            new Candidate(Role.EMBEDDING, "Multilingual E5 Small",
                    "https://huggingface.co/intfloat/multilingual-e5-small",
                    "~100M parameters; runtime conversion required"),
            new Candidate(Role.EMBEDDING, "EmbeddingGemma 300M",
                    "https://huggingface.co/google/embeddinggemma-300m",
                    "~300M parameters; gated license"),
            new Candidate(Role.EMBEDDING, "BGE-M3",
                    "https://huggingface.co/BAAI/bge-m3", "~568M parameters"),
            new Candidate(Role.EMBEDDING, "Qwen3 Embedding 0.6B",
                    "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B", "~600M parameters"),
            new Candidate(Role.GENERATION, "Qwen3 0.6B GGUF",
                    "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF", "4-bit variant available"),
            new Candidate(Role.GENERATION, "Qwen3 1.7B GGUF",
                    "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF", "higher RAM demand"),
            new Candidate(Role.RERANKING, "Qwen3 Reranker 0.6B",
                    "https://huggingface.co/Qwen/Qwen3-Reranker-0.6B", "runtime adapter pending"),
            new Candidate(Role.RERANKING, "BGE Reranker v2 M3",
                    "https://huggingface.co/BAAI/bge-reranker-v2-m3", "runtime adapter pending"),
            new Candidate(Role.PERSONALIZATION, "Local linear classifier",
                    "", "planned on-device training; no pretrained weights"),
            new Candidate(Role.PERSONALIZATION, "Small ONNX classifier",
                    "", "fixed features and label schema required")
    ));

    private KnowledgeModelPolicy() { }

    static Gate gate(final Provider provider, final boolean configured,
                     final boolean androidBackendAvailable) {
        if (provider == null || provider == Provider.OFF) return Gate.OFF;
        if (!configured) return Gate.NOT_CONFIGURED;
        if (provider == Provider.ANDROID) {
            return androidBackendAvailable ? Gate.READY_LOCAL : Gate.NOT_IMPLEMENTED;
        }
        return Gate.NEEDS_NETWORK_CONSENT;
    }

    static boolean mayTransferNoteText(final Provider provider, final boolean approved) {
        return approved && (provider == Provider.PERSONAL_PC || provider == Provider.CLOUD);
    }

    static List<Candidate> candidates(final Role role) {
        final java.util.ArrayList<Candidate> options = new java.util.ArrayList<>();
        for (final Candidate candidate : CANDIDATES) {
            if (candidate.role == role) options.add(candidate);
        }
        return Collections.unmodifiableList(options);
    }
}

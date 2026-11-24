/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;

/**
 * MODEL-M0: in-memory, revocable permit created ONLY from an explicit UI
 * confirmation. Network clients require it before opening a connection.
 * The permit contains no notes, API keys or paths and cannot survive restart.
 * Index consent is scoped to one bounded run; its outgoing batches must still
 * pass independent source-revision checks before each upload.
 */
final class KnowledgeNetworkApproval {
    enum Purpose { EMBEDDING_INDEX, EMBEDDING_QUERY, GENERATION }
    private static final long MAX_AGE_NANOS = 90L * 60L * 1_000_000_000L;
    private static final int MAX_INDEX_REQUESTS = 4096;

    static final class Permit {
        private final String endpoint;
        private final String model;
        private final Purpose purpose;
        private final byte[] exactTextHash;
        private final int maxRequests;
        private final long issuedAt;
        private int used;
        private boolean revoked;

        private Permit(final URL target, final String modelId, final Purpose task,
                       final List<String> exactTexts, final int requests) {
            if (target == null || modelId == null || modelId.trim().isEmpty()
                    || task == null || requests < 1 || requests > MAX_INDEX_REQUESTS
                    || !KnowledgeModelPolicy.mayTransferNoteText(
                            providerFor(target), true)) {
                throw new IllegalArgumentException("Invalid off-device consent scope");
            }
            final String service = task == Purpose.GENERATION
                    ? "/v1/chat/completions" : "/v1/embeddings";
            if (!KnowledgeLlmClient.serviceEndpoint(target.toExternalForm(), service)
                    .toExternalForm().equals(target.toExternalForm())) {
                throw new IllegalArgumentException("Unnormalized consent endpoint");
            }
            endpoint = target.toExternalForm();
            model = modelId.trim();
            purpose = task;
            exactTextHash = exactTexts == null ? null : digest(exactTexts);
            if ((task == Purpose.EMBEDDING_INDEX) != (exactTexts == null)) {
                throw new IllegalArgumentException("Exact preview required for single-shot consent");
            }
            maxRequests = requests;
            issuedAt = System.nanoTime();
        }

        synchronized void consume(final URL target, final String modelId, final Purpose task,
                                  final List<String> outgoingText) throws IOException {
            if (revoked || Thread.currentThread().isInterrupted()
                    || System.nanoTime() - issuedAt > MAX_AGE_NANOS
                    || used >= maxRequests || target == null || modelId == null
                    || task != purpose || !endpoint.equals(target.toExternalForm())
                    || !model.equals(modelId.trim()) || outgoingText == null
                    || outgoingText.isEmpty() || (exactTextHash != null
                    && !MessageDigest.isEqual(exactTextHash, digest(outgoingText)))) {
                throw new IOException("Missing, expired or mismatched explicit network consent");
            }
            used++;
        }

        synchronized void revoke() { revoked = true; }
    }

    private KnowledgeNetworkApproval() { }

    static Permit forIndex(final URL endpoint, final String model) {
        return new Permit(endpoint, model, Purpose.EMBEDDING_INDEX, null, MAX_INDEX_REQUESTS);
    }

    static Permit forQuery(final URL endpoint, final String model, final String question) {
        return new Permit(endpoint, model, Purpose.EMBEDDING_QUERY,
                Collections.singletonList(question), 1);
    }

    static Permit forAnswer(final URL endpoint, final String model, final String exactPreview) {
        return new Permit(endpoint, model, Purpose.GENERATION,
                Collections.singletonList(exactPreview), 1);
    }

    /** Classifies only network destinations; Android-local never opens HTTP. */
    static KnowledgeModelPolicy.Provider providerFor(final URL endpoint) {
        if (endpoint == null || endpoint.getHost() == null
                || (!"http".equalsIgnoreCase(endpoint.getProtocol())
                && !"https".equalsIgnoreCase(endpoint.getProtocol()))) {
            return KnowledgeModelPolicy.Provider.OFF;
        }
        return KnowledgeLlmClient.privateLocalHost(endpoint.getHost())
                ? KnowledgeModelPolicy.Provider.PERSONAL_PC
                : KnowledgeModelPolicy.Provider.CLOUD;
    }

    private static byte[] digest(final List<String> inputs) {
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (final String value : inputs) {
                if (value == null) throw new IllegalArgumentException("Missing approved text");
                final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                sha.update((byte) (bytes.length >>> 24));
                sha.update((byte) (bytes.length >>> 16));
                sha.update((byte) (bytes.length >>> 8));
                sha.update((byte) bytes.length);
                sha.update(bytes);
            }
            return sha.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

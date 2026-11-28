/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class KnowledgeNetworkApprovalTest {
    private static final URL EMBEDDINGS = KnowledgeEmbeddingClient.endpoint(
            "https://models.example.org/v1");
    private static final URL GENERATION = KnowledgeLlmClient.endpoint(
            "https://models.example.org/v1");

    @Test public void offDeviceProviderClassificationIsFailClosed() throws Exception {
        assertEquals(KnowledgeModelPolicy.Provider.OFF,
                KnowledgeNetworkApproval.providerFor(null));
        assertEquals(KnowledgeModelPolicy.Provider.OFF,
                KnowledgeNetworkApproval.providerFor(new URL("file:/tmp/test")));
        assertEquals(KnowledgeModelPolicy.Provider.PERSONAL_PC,
                KnowledgeNetworkApproval.providerFor(new URL("http://192.168.1.2/v1/embeddings")));
        assertEquals(KnowledgeModelPolicy.Provider.CLOUD,
                KnowledgeNetworkApproval.providerFor(EMBEDDINGS));
        try {
            KnowledgeNetworkApproval.forQuery(
                    new URL("http://models.example.org/v1/embeddings"), "m", "private");
            fail("Public HTTP must not have an approval");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void queryApprovalIsExactSingleUseAndRevocable() throws Exception {
        final KnowledgeNetworkApproval.Permit gate =
                KnowledgeNetworkApproval.forQuery(EMBEDDINGS, "emb", "approved query");
        denied(() -> gate.consume(EMBEDDINGS, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY,
                Collections.singletonList("different query")));
        denied(() -> gate.consume(EMBEDDINGS, "different model",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY,
                Collections.singletonList("approved query")));
        denied(() -> gate.consume(GENERATION, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY,
                Collections.singletonList("approved query")));
        denied(() -> gate.consume(EMBEDDINGS, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                Collections.singletonList("approved query")));
        gate.consume(EMBEDDINGS, "emb", KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY,
                Collections.singletonList("approved query"));
        denied(() -> gate.consume(EMBEDDINGS, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY,
                Collections.singletonList("approved query")));
        gate.revoke();
    }

    @Test public void answerCannotBeReusedOrPointedAtAnotherPayload() throws Exception {
        final KnowledgeNetworkApproval.Permit gate =
                KnowledgeNetworkApproval.forAnswer(GENERATION, "llm", "exact source preview");
        denied(() -> gate.consume(GENERATION, "llm",
                KnowledgeNetworkApproval.Purpose.GENERATION,
                Collections.singletonList("substituted preview")));
        gate.consume(GENERATION, "llm", KnowledgeNetworkApproval.Purpose.GENERATION,
                Collections.singletonList("exact source preview"));
        denied(() -> gate.consume(GENERATION, "llm",
                KnowledgeNetworkApproval.Purpose.GENERATION,
                Collections.singletonList("exact source preview")));
    }

    @Test public void indexScopeSupportsBoundedBatchesButRevocationStopsIt() throws Exception {
        final KnowledgeNetworkApproval.Permit gate =
                KnowledgeNetworkApproval.forIndex(EMBEDDINGS, "emb", Arrays.asList(
                        Arrays.asList("first fragment", "second fragment"),
                        Collections.singletonList("another note")));
        denied(() -> gate.consume(EMBEDDINGS, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                Collections.singletonList("unreviewed fragment")));
        denied(() -> gate.consume(EMBEDDINGS, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                Collections.singletonList("another note")));
        gate.consume(EMBEDDINGS, "emb", KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                Arrays.asList("first fragment", "second fragment"));
        gate.consume(EMBEDDINGS, "emb", KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                Collections.singletonList("another note"));
        gate.revoke();
        denied(() -> gate.consume(EMBEDDINGS, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                Collections.singletonList("later fragment")));
    }

    @Test public void indexScopeCannotOutliveItsRequestBudget() throws Exception {
        final java.util.List<java.util.List<String>> batches = new java.util.ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            batches.add(Collections.singletonList("synthetic fragment"));
        }
        final KnowledgeNetworkApproval.Permit gate =
                KnowledgeNetworkApproval.forIndex(EMBEDDINGS, "emb", batches);
        for (int i = 0; i < 4096; i++) {
            gate.consume(EMBEDDINGS, "emb", KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                    Collections.singletonList("synthetic fragment"));
        }
        denied(() -> gate.consume(EMBEDDINGS, "emb",
                KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX,
                Collections.singletonList("synthetic fragment")));
    }

    @Test public void indexScopeRejectsUnplannedOrEmptyBatchLists() throws Exception {
        try {
            KnowledgeNetworkApproval.forIndex(EMBEDDINGS, "emb", Collections.emptyList());
            fail("Empty upload plan must fail");
        } catch (IllegalArgumentException expected) { }
        try {
            KnowledgeNetworkApproval.forIndex(EMBEDDINGS, "emb", Collections.singletonList(
                    Collections.singletonList("approved")));
        } catch (IllegalArgumentException unexpected) {
            fail("A reviewed single batch is valid");
        }
        try {
            KnowledgeNetworkApproval.forIndex(EMBEDDINGS, "emb", Collections.singletonList(
                    Collections.emptyList()));
            fail("Empty individual batch must fail");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void malformedOrWrongPurposeEndpointNeverGetsAConsentPermit() throws Exception {
        try {
            KnowledgeNetworkApproval.forAnswer(EMBEDDINGS, "m", "preview");
            fail("Generation consent for an embeddings endpoint");
        } catch (IllegalArgumentException expected) { }
        try {
            KnowledgeNetworkApproval.forQuery(new URL("https://models.example.org/v1/other"),
                    "m", "question");
            fail("Unexpected endpoint path");
        } catch (IllegalArgumentException expected) { }
        try {
            KnowledgeNetworkApproval.forQuery(new URL("https://user:pass@models.example.org/v1/embeddings"),
                    "m", "question");
            fail("Credentials embedded in endpoint");
        } catch (IllegalArgumentException expected) { }
    }

    private interface Checked { void run() throws IOException; }

    private static void denied(final Checked action) throws Exception {
        try {
            action.run();
            fail("Network approval failed open");
        } catch (IOException expected) { }
    }
}

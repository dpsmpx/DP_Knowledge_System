/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * MODEL-M0: isolated loopback-only fake API, never a personal-vault or cloud
 * test. Exercises the real HTTP clients with entirely synthetic payloads.
 */
public final class KnowledgeNetworkTransportTest {
    private static final String EMBEDDINGS = "/v1/embeddings";
    private static final String CHAT = "/v1/chat/completions";

    @Test public void missingOrMismatchedConsentSendsNoHttpRequest() throws Exception {
        try (FakeApi api = new FakeApi(EMBEDDINGS, 200, embeddings(1), null)) {
            final URL endpoint = api.embeddingEndpoint();
            refused(() -> KnowledgeEmbeddingClient.embed(endpoint, "synthetic-embed", "",
                    Collections.singletonList("safe synthetic text"), null,
                    KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY));
            final KnowledgeNetworkApproval.Permit approval =
                    KnowledgeNetworkApproval.forQuery(endpoint, "synthetic-embed",
                            "safe synthetic text");
            refused(() -> KnowledgeEmbeddingClient.embed(endpoint, "synthetic-embed", "",
                    Collections.singletonList("unapproved text"), approval,
                    KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY));
            refused(() -> KnowledgeEmbeddingClient.embed(endpoint, "synthetic-embed", "",
                    Collections.singletonList("safe synthetic text"), approval,
                    KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX));
            assertEquals("Denied requests cannot reach a socket", 0, api.requests.get());
        }
        try (FakeApi api = new FakeApi(CHAT, 200, answer(), null)) {
            final URL endpoint = api.chatEndpoint();
            refused(() -> KnowledgeLlmClient.ask(endpoint, "synthetic-chat", "",
                    "synthetic prompt", null));
            final KnowledgeNetworkApproval.Permit approval =
                    KnowledgeNetworkApproval.forAnswer(endpoint, "synthetic-chat", "synthetic prompt");
            approval.revoke();
            refused(() -> KnowledgeLlmClient.ask(endpoint, "synthetic-chat", "",
                    "synthetic prompt", approval));
            assertEquals(0, api.requests.get());
        }
    }

    @Test public void embeddingUploadsOnlyReviewedOrderedChunks() throws Exception {
        try (FakeApi api = new FakeApi(EMBEDDINGS, 200, embeddings(2), null)) {
            final URL endpoint = api.embeddingEndpoint();
            final java.util.List<String> approved = Arrays.asList("alpha synthetic", "beta synthetic");
            final KnowledgeNetworkApproval.Permit permit = KnowledgeNetworkApproval.forIndex(
                    endpoint, "synthetic-embed", Collections.singletonList(approved));
            final float[][] result = KnowledgeEmbeddingClient.embed(endpoint, "synthetic-embed",
                    "synthetic-key", approved, permit,
                    KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX);
            assertEquals(2, result.length);
            assertEquals(8, result[0].length);
            assertEquals(1, api.requests.get());
            final JSONObject received = new JSONObject(api.payload.get());
            assertEquals("synthetic-embed", received.getString("model"));
            assertEquals("alpha synthetic", received.getJSONArray("input").getString(0));
            assertEquals("beta synthetic", received.getJSONArray("input").getString(1));
            assertEquals("Bearer synthetic-key", api.authorization.get());
            refused(() -> KnowledgeEmbeddingClient.embed(endpoint, "synthetic-embed", "",
                    approved, permit, KnowledgeNetworkApproval.Purpose.EMBEDDING_INDEX));
            assertEquals("No silent retry or extra transfer", 1, api.requests.get());
        }
    }

    @Test public void serverErrorsAndMalformedRepliesNeverRetryAutomatically() throws Exception {
        try (FakeApi api = new FakeApi(CHAT, 503, "synthetic outage", null)) {
            final URL endpoint = api.chatEndpoint();
            final KnowledgeNetworkApproval.Permit permit = KnowledgeNetworkApproval.forAnswer(
                    endpoint, "synthetic-chat", "synthetic preview");
            refused(() -> KnowledgeLlmClient.ask(endpoint, "synthetic-chat", "",
                    "synthetic preview", permit));
            assertEquals(1, api.requests.get());
            refused(() -> KnowledgeLlmClient.ask(endpoint, "synthetic-chat", "",
                    "synthetic preview", permit));
            assertEquals(1, api.requests.get());
        }
        try (FakeApi api = new FakeApi(CHAT, 401, "synthetic invalid key", null)) {
            final URL endpoint = api.chatEndpoint();
            final KnowledgeNetworkApproval.Permit permit = KnowledgeNetworkApproval.forAnswer(
                    endpoint, "synthetic-chat", "synthetic preview");
            refused(() -> KnowledgeLlmClient.ask(endpoint, "synthetic-chat", "incorrect-test-key",
                    "synthetic preview", permit));
            assertEquals("Rejected credentials must not trigger fallback", 1, api.requests.get());
        }
        try (FakeApi api = new FakeApi(EMBEDDINGS, 200, "{\"data\":[]}", null)) {
            final URL endpoint = api.embeddingEndpoint();
            final KnowledgeNetworkApproval.Permit permit = KnowledgeNetworkApproval.forQuery(
                    endpoint, "synthetic-embed", "synthetic question");
            refused(() -> KnowledgeEmbeddingClient.embed(endpoint, "synthetic-embed", "",
                    Collections.singletonList("synthetic question"), permit,
                    KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY));
            assertEquals("Incomplete response is not retried", 1, api.requests.get());
        }
    }

    @Test public void interruptedCallIsRefusedBeforeSocketOpen() throws Exception {
        try (FakeApi api = new FakeApi(EMBEDDINGS, 200, embeddings(1), null)) {
            final URL endpoint = api.embeddingEndpoint();
            final KnowledgeNetworkApproval.Permit permit = KnowledgeNetworkApproval.forQuery(
                    endpoint, "synthetic-embed", "synthetic query");
            Thread.currentThread().interrupt();
            try {
                refused(() -> KnowledgeEmbeddingClient.embed(endpoint, "synthetic-embed", "",
                        Collections.singletonList("synthetic query"), permit,
                        KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY));
            } finally {
                Thread.interrupted(); // Do not leak an interrupt to later JUnit tests.
            }
            assertEquals(0, api.requests.get());
        }
    }

    @Test public void redirectCannotForwardPrivateTextOrKeyToAnotherServer() throws Exception {
        try (FakeApi receiver = new FakeApi(CHAT, 200, answer(), null);
             FakeApi redirect = new FakeApi(CHAT, 302, "",
                     receiver.chatEndpoint().toExternalForm())) {
            final URL endpoint = redirect.chatEndpoint();
            final KnowledgeNetworkApproval.Permit permit = KnowledgeNetworkApproval.forAnswer(
                    endpoint, "synthetic-chat", "synthetic only");
            refused(() -> KnowledgeLlmClient.ask(endpoint, "synthetic-chat", "synthetic-key",
                    "synthetic only", permit));
            assertEquals(1, redirect.requests.get());
            assertEquals("No redirect may receive source text", 0, receiver.requests.get());
        }
    }

    @Test public void stoppedEndpointFailsWithoutHiddenCloudFallback() throws Exception {
        final URL closed;
        try (FakeApi api = new FakeApi(EMBEDDINGS, 200, embeddings(1), null)) {
            closed = api.embeddingEndpoint();
        }
        final KnowledgeNetworkApproval.Permit permit = KnowledgeNetworkApproval.forQuery(
                closed, "synthetic-embed", "synthetic only");
        refused(() -> KnowledgeEmbeddingClient.embed(closed, "synthetic-embed", "",
                Collections.singletonList("synthetic only"), permit,
                KnowledgeNetworkApproval.Purpose.EMBEDDING_QUERY));
    }

    private static String embeddings(final int count) {
        final StringBuilder json = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i != 0) json.append(',');
            json.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < 8; d++) {
                if (d != 0) json.append(',');
                json.append(d == i ? "1.0" : "0.0");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private static String answer() {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + "\"Synthetic response [1]\"}}]}";
    }

    private interface Operation { void execute() throws IOException; }

    private static void refused(final Operation call) throws Exception {
        try {
            call.execute();
            fail("Transport must fail closed");
        } catch (IOException expected) { }
    }

    private static final class FakeApi implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> payload = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>();

        FakeApi(final String path, final int code, final String response,
                final String redirectTo) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> reply(exchange, code, response, redirectTo));
            server.start();
        }

        URL embeddingEndpoint() {
            return KnowledgeEmbeddingClient.endpoint(base());
        }

        URL chatEndpoint() {
            return KnowledgeLlmClient.endpoint(base());
        }

        private String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private void reply(final HttpExchange exchange, final int code,
                           final String response, final String redirectTo) throws IOException {
            try {
                requests.incrementAndGet();
                payload.set(read(exchange.getRequestBody()));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                final byte[] body = response.getBytes(StandardCharsets.UTF_8);
                if (redirectTo != null) exchange.getResponseHeaders().set("Location", redirectTo);
                exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
                if (body.length != 0) {
                    try (OutputStream stream = exchange.getResponseBody()) {
                        stream.write(body);
                    }
                }
            } finally {
                exchange.close();
            }
        }

        private static String read(final InputStream input) throws IOException {
            try (InputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[4096];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}

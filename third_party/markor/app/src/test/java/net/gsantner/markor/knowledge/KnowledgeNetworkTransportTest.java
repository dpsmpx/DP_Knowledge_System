/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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
        final ServerSocket server;
        final Thread receiver;
        final String expectedPath;
        final int replyCode;
        final String replyBody;
        final String redirect;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> payload = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>();

        FakeApi(final String path, final int code, final String response,
                final String redirectTo) throws IOException {
            expectedPath = path;
            replyCode = code;
            replyBody = response;
            redirect = redirectTo;
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            receiver = new Thread(this::serve, "dpks-synthetic-loopback-only");
            receiver.setDaemon(true);
            receiver.start();
        }

        URL embeddingEndpoint() {
            return KnowledgeEmbeddingClient.endpoint(base());
        }

        URL chatEndpoint() {
            return KnowledgeLlmClient.endpoint(base());
        }

        private String base() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/v1";
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket connection = server.accept()) {
                    connection.setSoTimeout(8_000);
                    requests.incrementAndGet();
                    reply(connection);
                } catch (SocketException closed) {
                    if (server.isClosed()) return;
                } catch (IOException invalid) {
                    if (server.isClosed()) return;
                }
            }
        }

        private void reply(final Socket connection) throws IOException {
            final InputStream input = connection.getInputStream();
            final ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int delimiter = 0;
            while (headerBytes.size() < 8192) {
                final int value = input.read();
                if (value == -1) throw new IOException("Synthetic request headers incomplete");
                headerBytes.write(value);
                delimiter = value == "\r\n\r\n".charAt(delimiter)
                        ? delimiter + 1 : value == '\r' ? 1 : 0;
                if (delimiter == 4) break;
            }
            if (delimiter != 4) throw new IOException("Synthetic request headers oversized");
            final String[] headers = new String(headerBytes.toByteArray(),
                    StandardCharsets.US_ASCII).split("\r\n");
            if (!headers[0].startsWith("POST " + expectedPath + " HTTP/1.")) {
                throw new IOException("Unexpected synthetic endpoint path");
            }
            int length = -1;
            for (final String header : headers) {
                final String lower = header.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                } else if (lower.startsWith("authorization:")) {
                    authorization.set(header.substring(header.indexOf(':') + 1).trim());
                }
            }
            if (length < 0 || length > 20_000) throw new IOException("Invalid synthetic body size");
            final byte[] bytes = new byte[length];
            int count = 0;
            while (count < length) {
                final int n = input.read(bytes, count, length - count);
                if (n == -1) throw new IOException("Synthetic request body truncated");
                count += n;
            }
            payload.set(new String(bytes, StandardCharsets.UTF_8));
            final byte[] body = replyBody.getBytes(StandardCharsets.UTF_8);
            final String responseHeaders = "HTTP/1.1 " + replyCode + " Synthetic\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n"
                    + (redirect == null ? "" : "Location: " + redirect + "\r\n") + "\r\n";
            final OutputStream output = connection.getOutputStream();
            output.write(responseHeaders.getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }

        @Override public void close() {
            try {
                server.close();
                receiver.join(1_000);
            } catch (IOException ignored) {
                // The server owns no persistent state or resources beyond the socket.
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

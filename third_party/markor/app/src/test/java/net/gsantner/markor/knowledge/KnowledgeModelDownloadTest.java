/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import static org.junit.Assert.*;

public class KnowledgeModelDownloadTest {
    private static final String FILE_URL =
            "https://huggingface.co/example/weights/resolve/main/q4.gguf?download=true";

    @Test public void onlyPublicDirectHttpsWeightLinksAreAccepted() throws Exception {
        assertEquals("q4.gguf", KnowledgeModelDownload.parseSource(FILE_URL).filename);
        assertEquals("e5.onnx", KnowledgeModelDownload.parseSource(
                "https://models.example.org/e5.onnx").filename);
        for (final String url : Arrays.asList(
                "http://models.example.org/e5.onnx", "file:///tmp/model.gguf",
                "https://localhost/e5.onnx", "https://127.0.0.1/e5.onnx",
                "https://10.0.0.1/e5.onnx", "https://[::1]/model.gguf",
                "https://models.local/e5.onnx", "https://models.example.org:8443/e5.onnx",
                "https://name:password@models.example.org/e5.onnx",
                "https://models.example.org/model.gguf#fragment",
                "https://models.example.org/repository", "https://models.example.org/archive.zip",
                "https://models.example.org/model%2Egguf")) {
            try {
                KnowledgeModelDownload.parseSource(url);
                fail("Accepted unsafe or non-direct URL: " + url);
            } catch (IOException expected) { }
        }
    }

    @Test public void redirectsStayOnHttpsAndInstallOnlyCompleteModelBytes() throws Exception {
        final File root = Files.createTempDirectory("dpks-downloader").toFile();
        try {
            final byte[] valid = gguf();
            final Queue<FakeConnection> responses = new ArrayDeque<>();
            responses.add(new FakeConnection(302, "https://cdn.example.org/blob?id=123", null, -1));
            responses.add(new FakeConnection(200, null, valid, valid.length));
            final KnowledgeModelFiles shelf = new KnowledgeModelFiles(root);
            final long[] copied = {0};
            final long[] total = {0};
            final KnowledgeModelFiles.Entry model = KnowledgeModelDownload.download(
                    FILE_URL, KnowledgeModelPolicy.Role.GENERATION, "", shelf,
                    (n, length) -> { copied[0] = n; total[0] = length; },
                    url -> responses.remove());
            assertEquals(64, model.bytes);
            assertEquals(64, copied[0]);
            assertEquals(64, total[0]);
            assertTrue(responses.isEmpty());
            assertEquals(1, shelf.installed().size());
        } finally { cleanup(root); }
    }

    @Test public void downgradeHtmlAndTruncationNeverRegisterFiles() throws Exception {
        final File root = Files.createTempDirectory("dpks-download-failure").toFile();
        try {
            final KnowledgeModelFiles shelf = new KnowledgeModelFiles(root);
            final FakeConnection downgrade = new FakeConnection(302,
                    "http://cdn.example.org/blob", null, -1);
            assertFailsWithoutInstalling(shelf, downgrade);
            assertFailsWithoutInstalling(shelf,
                    new FakeConnection(200, null, "<html>login</html>".getBytes("UTF-8"), 18) {
                        @Override public String getContentType() { return "text/html; charset=UTF-8"; }
                    });
            assertFailsWithoutInstalling(shelf, new FakeConnection(200, null, gguf(), 100));
            assertFailsWithoutInstalling(shelf, new FakeConnection(404, null, null, -1));
        } finally { cleanup(root); }
    }

    private static void assertFailsWithoutInstalling(final KnowledgeModelFiles shelf,
                                                     final FakeConnection response) throws Exception {
        try {
            KnowledgeModelDownload.download(FILE_URL, KnowledgeModelPolicy.Role.GENERATION,
                    "", shelf, null, url -> response);
            fail("Failed to reject unsafe or incomplete download");
        } catch (IOException expected) { }
        assertTrue(shelf.installed().isEmpty());
    }

    private static byte[] gguf() {
        final byte[] data = new byte[64];
        data[0] = 'G'; data[1] = 'G'; data[2] = 'U'; data[3] = 'F'; data[4] = 3;
        return data;
    }

    private static void cleanup(final File root) {
        final File[] children = root.listFiles();
        if (children != null) for (final File child : children) child.delete();
        root.delete();
    }

    private static class FakeConnection extends HttpURLConnection {
        private final int status;
        private final String location;
        private final byte[] bytes;
        private final long length;

        FakeConnection(final int statusCode, final String redirect,
                       final byte[] body, final long announcedSize) throws Exception {
            super(new URL(FILE_URL));
            status = statusCode;
            location = redirect;
            bytes = body;
            length = announcedSize;
        }

        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(final String field) {
            return "Location".equals(field) ? location : null;
        }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public long getContentLengthLong() { return length; }
        @Override public InputStream getInputStream() throws IOException {
            if (bytes == null) throw new IOException("Missing fake content");
            return new ByteArrayInputStream(bytes);
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { connected = true; }
    }
}

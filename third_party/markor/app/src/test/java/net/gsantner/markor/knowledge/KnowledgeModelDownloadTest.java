/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import static org.junit.Assert.*;

public class KnowledgeModelDownloadTest {
    private static final String FILE_URL =
            "https://huggingface.co/example/weights/resolve/main/q4.gguf?download=true";
    private static final KnowledgeModelDownload.AddressResolver PUBLIC_DNS = host ->
            new InetAddress[]{InetAddress.getByAddress(new byte[]{(byte) 93, (byte) 184,
                    (byte) 216, 34})};

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

    @Test public void resolvedDnsAddressesMustAllBePublic() throws Exception {
        for (final String address : Arrays.asList(
                "127.0.0.1", "10.3.4.5", "172.31.0.9", "192.168.1.1",
                "169.254.169.254", "100.64.2.1", "0.1.2.3", "192.0.2.5",
                "198.18.1.1", "198.51.100.8", "203.0.113.9",
                "224.0.0.1", "255.255.255.255", "::1", "fc00::1",
                "fe80::1", "2001:db8::1", "2001::1", "2002::1")) {
            assertFalse(address, KnowledgeModelDownload.isPublicAddress(
                    InetAddress.getByName(address)));
        }
        for (final String address : Arrays.asList("93.184.216.34", "8.8.8.8",
                "2606:4700:4700::1111")) {
            assertTrue(address, KnowledgeModelDownload.isPublicAddress(
                    InetAddress.getByName(address)));
        }
        assertFalse(KnowledgeModelDownload.isPublicAddress(null));

        final File root = Files.createTempDirectory("dpks-dns-refuse").toFile();
        try {
            final KnowledgeModelFiles shelf = new KnowledgeModelFiles(root);
            final int[] opened = {0};
            for (final KnowledgeModelDownload.AddressResolver resolver :
                    Arrays.<KnowledgeModelDownload.AddressResolver>asList(
                    host -> new InetAddress[0],
                    host -> new InetAddress[]{InetAddress.getByName("192.168.1.2")},
                    host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"),
                            InetAddress.getByName("127.0.0.1")})) {
                try {
                    KnowledgeModelDownload.download(FILE_URL,
                            KnowledgeModelPolicy.Role.GENERATION, "", shelf, null,
                            url -> { opened[0]++; return new FakeConnection(200, null, gguf(), 64); },
                            resolver);
                    fail("Accepted DNS reply with no fully public destination");
                } catch (IOException expected) { }
            }
            assertEquals("DNS must be checked before connection", 0, opened[0]);
            assertTrue(shelf.installed().isEmpty());
        } finally { cleanup(root); }
    }

    @Test public void redirectDestinationMustPassItsOwnDnsCheck() throws Exception {
        final File root = Files.createTempDirectory("dpks-dns-redirect").toFile();
        try {
            final int[] opened = {0};
            final KnowledgeModelDownload.AddressResolver resolver = host ->
                    new InetAddress[]{InetAddress.getByName(host.equals("huggingface.co")
                            ? "8.8.8.8" : "10.10.10.10")};
            final KnowledgeModelFiles shelf = new KnowledgeModelFiles(root);
            try {
                KnowledgeModelDownload.download(FILE_URL, KnowledgeModelPolicy.Role.GENERATION,
                        "", shelf, null, url -> {
                            opened[0]++;
                            return new FakeConnection(302,
                                    "https://cdn.example.org/blob", null, -1);
                        }, resolver);
                fail("Redirect to an internal DNS target was followed");
            } catch (IOException expected) { }
            assertEquals("Only the initial public redirect may be opened", 1, opened[0]);
            assertTrue(shelf.installed().isEmpty());
        } finally { cleanup(root); }
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
                    url -> responses.remove(), PUBLIC_DNS);
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

    @Test public void trustedDigestResumesOnlyAnExactRemainingHttpRange() throws Exception {
        final File root = Files.createTempDirectory("dpks-resume-valid").toFile();
        try {
            final byte[] bytes = gguf();
            final String digest = digest(bytes);
            final KnowledgeModelFiles shelf = new KnowledgeModelFiles(root);
            final FakeConnection truncated = new FakeConnection(200, null,
                    Arrays.copyOfRange(bytes, 0, 20), bytes.length);
            assertFailsWithoutInstalling(shelf, truncated, digest);
            assertEquals(20, shelf.resumeBytes(digest, "q4.gguf"));
            final FakeConnection resumed = new FakeConnection(206, null,
                    Arrays.copyOfRange(bytes, 20, 64), 44, "bytes 20-63/64");
            final long[] progress = {-1};
            final KnowledgeModelFiles.Entry entry = KnowledgeModelDownload.download(
                    FILE_URL, KnowledgeModelPolicy.Role.GENERATION, digest, shelf,
                    (copied, total) -> { progress[0] = copied; assertEquals(64, total); },
                    url -> resumed, PUBLIC_DNS);
            assertEquals("bytes=20-", resumed.getRequestProperty("Range"));
            assertEquals(64, progress[0]);
            assertEquals(digest, entry.sha256);
            assertEquals(1, shelf.installed().size());
            assertEquals(0, shelf.resumeBytes(digest, "q4.gguf"));
        } finally { cleanup(root); }
    }

    @Test public void wrongRangeAndUnsolicited206CannotMixModelBytes() throws Exception {
        final File root = Files.createTempDirectory("dpks-resume-refuse").toFile();
        try {
            final byte[] bytes = gguf();
            final String digest = digest(bytes);
            final KnowledgeModelFiles shelf = new KnowledgeModelFiles(root);
            assertFailsWithoutInstalling(shelf, new FakeConnection(200, null,
                    Arrays.copyOfRange(bytes, 0, 20), 64), digest);
            for (final String mismatch : Arrays.asList("bytes 19-63/64",
                    "bytes 20-62/64", "bytes */64", "bytes 20-63/65")) {
                assertFailsWithoutInstalling(shelf, new FakeConnection(206, null,
                        Arrays.copyOfRange(bytes, 20, 64), 44, mismatch), digest);
                assertEquals(20, shelf.resumeBytes(digest, "q4.gguf"));
            }
            assertTrue(shelf.installed().isEmpty());
            // Even a well-formed 206 is not acceptable on a fresh download.
            shelf.discardResume(digest, "q4.gguf");
            assertFailsWithoutInstalling(shelf, new FakeConnection(206, null,
                    Arrays.copyOfRange(bytes, 20, 64), 44, "bytes 20-63/64"), digest);
        } finally { cleanup(root); }
    }

    @Test public void ignoredRangeRestartsSafelyAndBadShaDiscardsCheckpoint() throws Exception {
        final File root = Files.createTempDirectory("dpks-resume-restart").toFile();
        try {
            final byte[] bytes = gguf();
            final String digest = digest(bytes);
            final KnowledgeModelFiles shelf = new KnowledgeModelFiles(root);
            assertFailsWithoutInstalling(shelf, new FakeConnection(200, null,
                    Arrays.copyOfRange(bytes, 0, 16), 64), digest);
            final FakeConnection ignoredRange = new FakeConnection(200, null, bytes, bytes.length);
            final KnowledgeModelFiles.Entry entry = KnowledgeModelDownload.download(
                    FILE_URL, KnowledgeModelPolicy.Role.GENERATION, digest, shelf,
                null, url -> ignoredRange, PUBLIC_DNS);
            assertEquals("bytes=16-", ignoredRange.getRequestProperty("Range"));
            assertEquals(digest, entry.sha256);
            assertEquals(0, shelf.resumeBytes(digest, "q4.gguf"));
            final String untrueDigest = repeat('0', 64);
            assertFailsWithoutInstalling(shelf, new FakeConnection(200, null,
                    Arrays.copyOfRange(bytes, 0, 20), 64), untrueDigest);
            assertEquals(20, shelf.resumeBytes(untrueDigest, "q4.gguf"));
            assertFailsWithoutInstalling(shelf, new FakeConnection(206, null,
                    Arrays.copyOfRange(bytes, 20, 64), 44, "bytes 20-63/64"), untrueDigest);
            assertEquals("Wrong digest must discard all untrusted partial bytes", 0,
                    shelf.resumeBytes(untrueDigest, "q4.gguf"));
            assertEquals(1, shelf.installed().size());
        } finally { cleanup(root); }
    }

    private static void assertFailsWithoutInstalling(final KnowledgeModelFiles shelf,
            final FakeConnection response, final String expectedSha) throws Exception {
        final int original = shelf.installed().size();
        try {
            KnowledgeModelDownload.download(FILE_URL, KnowledgeModelPolicy.Role.GENERATION,
                    expectedSha, shelf, null, url -> response, PUBLIC_DNS);
            fail("Failed to reject incomplete or mismatched model response");
        } catch (IOException expected) { }
        assertEquals("Failed transfer must not register another model", original,
                shelf.installed().size());
    }

    private static String digest(final byte[] bytes) throws Exception {
        final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        final StringBuilder result = new StringBuilder();
        for (final byte value : hash) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static String repeat(final char value, final int count) {
        final char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static void assertFailsWithoutInstalling(final KnowledgeModelFiles shelf,
                                                     final FakeConnection response) throws Exception {
        try {
            KnowledgeModelDownload.download(FILE_URL, KnowledgeModelPolicy.Role.GENERATION,
                    "", shelf, null, url -> response, PUBLIC_DNS);
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
        private final String contentRange;

        FakeConnection(final int statusCode, final String redirect,
                       final byte[] body, final long announcedSize) throws IOException {
            this(statusCode, redirect, body, announcedSize, null);
        }

        FakeConnection(final int statusCode, final String redirect,
                       final byte[] body, final long announcedSize,
                       final String announcedRange) throws IOException {
            super(new URL(FILE_URL));
            status = statusCode;
            location = redirect;
            bytes = body;
            length = announcedSize;
            contentRange = announcedRange;
        }

        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(final String field) {
            if ("Location".equals(field)) return location;
            if ("Content-Range".equals(field)) return contentRange;
            return null;
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

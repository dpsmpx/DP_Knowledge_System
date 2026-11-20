/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Restore to a *new* sibling library. Existing Markdown and attachments are
 * never replaced. The authoritative journal is committed only by the
 * separately version-checked Python journal restore API.
 */
final class KnowledgePortableRestore {
    interface JournalCommit { void commit(String text) throws Exception; }

    private KnowledgePortableRestore() { }

    static KnowledgePeerIdentity.Verification verifyPeer(final Context context,
                                                         final File verifiedZip)
            throws Exception {
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry proof = zip.getEntry(KnowledgePortableArchive.PEER);
            if (proof == null) return null; // Older unsigned backups cannot sync.
            final ZipEntry journal = zip.getEntry(KnowledgePortableArchive.JOURNAL);
            if (journal == null) throw new IOException("Signed archive missing journal");
            return KnowledgePeerIdentity.verifyJournal(context, readProof(zip, proof),
                    zip.getInputStream(journal));
        }
    }

    static byte[] peerProof(final File verifiedZip) throws Exception {
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry proof = zip.getEntry(KnowledgePortableArchive.PEER);
            return proof == null ? null : readProof(zip, proof);
        }
    }

    private static byte[] readProof(final ZipFile zip, final ZipEntry proof) throws Exception {
        if (proof.getSize() < 1 || proof.getSize() > 4096)
            throw new IOException("Invalid peer proof length");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = zip.getInputStream(proof)) {
            final byte[] buf = new byte[4096];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (bytes.size() + n > 4096) throw new IOException("Oversized peer proof");
                bytes.write(buf, 0, n);
            }
        }
        return bytes.toByteArray();
    }

    static String journalText(final File verifiedZip) throws Exception {
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry journal = zip.getEntry(KnowledgePortableArchive.JOURNAL);
            if (journal == null) return null;
            if (journal.getSize() > KnowledgeJournalCrypto.MAX_JOURNAL_BYTES)
                throw new IOException("Journal too large for supported import");
            try (InputStream input = zip.getInputStream(journal);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                final byte[] buf = new byte[65536];
                int n;
                while ((n = input.read(buf)) >= 0) {
                    if (bytes.size() > KnowledgeJournalCrypto.MAX_JOURNAL_BYTES - n)
                        throw new IOException("Journal too large for supported import");
                    bytes.write(buf, 0, n);
                }
                return new String(bytes.toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }

    static File restoreNewLibrary(final File verifiedZip, final File currentLibrary,
                                  final JournalCommit journalCommit,
                                  final KnowledgePortableArchive.Cancellation cancelled)
            throws Exception {
        // Re-verify immediately before extraction; never trust stale preview.
        final KnowledgePortableArchive.Summary summary =
                KnowledgePortableArchive.verify(verifiedZip, cancelled);
        if (currentLibrary == null || !currentLibrary.isDirectory())
            throw new IOException("Current library not available");
        final File parent = currentLibrary.getCanonicalFile().getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canWrite())
            throw new IOException("Library parent is not writable");
        if (parent.getUsableSpace() < summary.bytes + 64L * 1024 * 1024)
            throw new IOException("Insufficient free space for restore");
        final String suffix = Long.toHexString(new SecureRandom().nextLong());
        final File restored = new File(parent, "DPKS_Restored_" + suffix);
        final File temp = new File(parent, ".dpks-restore-" + suffix);
        if (restored.exists() || !temp.mkdir()) throw new IOException("Cannot stage restore");
        boolean moved = false;
        try {
            final String root = temp.getCanonicalPath() + File.separator;
            int files = 0;
            try (ZipFile zip = new ZipFile(verifiedZip)) {
                final java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    if (cancelled.cancelled()) throw new IOException("Restore cancelled");
                    final ZipEntry entry = entries.nextElement();
                    if (!entry.getName().startsWith("notes/")) continue;
                    final String relative = entry.getName().substring("notes/".length());
                    if (relative.isEmpty() || relative.startsWith("/")
                            || relative.contains("\\") || relative.contains("../")
                            || relative.endsWith("/..") || relative.contains("/./"))
                        throw new IOException("Unsafe restore path");
                    final File output = new File(temp, relative);
                    if (!output.getCanonicalPath().startsWith(root))
                        throw new IOException("Restore path escapes destination");
                    final File folder = output.getParentFile();
                    if ((!folder.isDirectory() && !folder.mkdirs()) || !folder.isDirectory())
                        throw new IOException("Cannot create restored directory");
                    long copied = 0;
                    try (InputStream input = zip.getInputStream(entry);
                         FileOutputStream target = new FileOutputStream(output)) {
                        final byte[] block = new byte[65536];
                        int n;
                        while ((n = input.read(block)) != -1) {
                            if (cancelled.cancelled()) throw new IOException("Restore cancelled");
                            copied += n;
                            if (copied > KnowledgePortableArchive.MAX_FILE_BYTES)
                                throw new IOException("Restored item too large");
                            target.write(block, 0, n);
                        }
                        target.getFD().sync();
                    }
                    if (copied != entry.getSize()) throw new IOException("Restore length mismatch");
                    files++;
                }
            }
            if (files != summary.sources) throw new IOException("Incomplete source restore");
            if (!temp.renameTo(restored)) throw new IOException("Cannot finalize restored library");
            moved = true;
            // Any journal failure leaves the original vault and journal
            // untouched. The new directory remains recoverable, not selected.
            if (summary.journal && journalCommit != null)
                journalCommit.commit(journalText(verifiedZip));
            return restored;
        } finally {
            if (!moved) removeStaging(temp);
        }
    }

    /** Only deletes this freshly created temporary directory, never a vault. */
    private static void removeStaging(final File dir) {
        if (!dir.getName().startsWith(".dpks-restore-")) return;
        removeChild(dir, dir);
    }

    private static void removeChild(final File root, final File dir) {
        try {
            if (!dir.getCanonicalPath().equals(dir.getAbsolutePath())
                    || !dir.getCanonicalPath().startsWith(root.getCanonicalPath())) {
                dir.delete();
                return;
            }
        } catch (IOException unsafe) {
            return;
        }
        final File[] files = dir.listFiles();
        if (files != null) for (final File file : files) {
            if (file.isDirectory()) removeChild(root, file);
            else file.delete();
        }
        dir.delete();
    }
}

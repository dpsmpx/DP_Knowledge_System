/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Local-only holding area for signed foreign branches. Nothing here is ever
 * replayed into the authoritative journal or written to source Markdown.
 * Every record is encrypted with an independent nonexportable Keystore key.
 */
final class KnowledgePeerInbox {
    private static final String ALIAS = "dpks_peer_inbox_aes256_v1";
    private static final String DIRECTORY = "knowledge_peer_inbox_v1";
    private static final byte[] MAGIC = "DPKSI001".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int MAX_PROOF = 4096;
    private static final int MAX_JOURNAL = KnowledgeJournalCrypto.MAX_JOURNAL_BYTES;
    private static final int MAX_RECORDS = 100;
    private static final String NAME_PATTERN = "[0-9a-f]{64}_[0-9a-f]{64}\\.dpkspeer";

    static final class ObservedRollbackException extends SecurityException {
        ObservedRollbackException() {
            super("Signed peer branch is older than a retained, already verified branch");
        }
    }

    static final class Snapshot {
        final String journal;
        final KnowledgePeerIdentity.Verification peer;

        Snapshot(final String journal, final KnowledgePeerIdentity.Verification peer) {
            this.journal = journal;
            this.peer = peer;
        }
    }

    private KnowledgePeerInbox() { }

    private static File directory(final Context context) throws IOException {
        final File root = new File(context.getNoBackupFilesDir(), DIRECTORY);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Peer inbox unavailable");
        return root;
    }

    private static SecretKey key() throws Exception {
        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            final KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
    }

    private static String hex(final byte[] bytes) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        final char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            output[i * 2] = alphabet[(bytes[i] >>> 4) & 15];
            output[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(output);
    }

    private static String recordName(final String fingerprint, final byte[] journal)
            throws Exception {
        if (!fingerprint.matches("[0-9a-f]{64}")) throw new SecurityException("Invalid peer");
        return fingerprint + "_" + hex(MessageDigest.getInstance("SHA-256").digest(journal))
                + ".dpkspeer";
    }

    static synchronized boolean save(final Context context, final byte[] proof,
                                     final String journal) throws Exception {
        if (proof == null || proof.length < 1 || proof.length > MAX_PROOF || journal == null)
            throw new SecurityException("Unsigned or oversized branch");
        final byte[] content = journal.getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_JOURNAL) throw new IOException("Branch exceeds limit");
        final KnowledgePeerIdentity.Verification verified = KnowledgePeerIdentity.verifyJournal(
                context, proof, new ByteArrayInputStream(content));
        if (!verified.paired) throw new SecurityException("Device is not paired");
        final File root = directory(context);
        // Independent AEAD/Keystore high-water history survives explicit
        // deletion of an inbox record. No new branch is accepted until this
        // monotonic ledger has been durably updated (including old upgrades).
        KnowledgePeerHighWater.observe(context, verified.fingerprint, journal);
        final File destination = new File(root, recordName(verified.fingerprint, content));
        if (destination.exists()) {
            final Snapshot existing = open(context, destination.getName());
            if (!verified.fingerprint.equals(existing.peer.fingerprint)
                    || !journal.equals(existing.journal))
                throw new SecurityException("Inbox record hash collision");
            return false; // Retry of identical signed branch is idempotent.
        }
        // A previously verified longer chain from the SAME paired identity
        // is positive evidence of a rollback. Keep the newer copy, do not
        // silently replace or accept the older prefix as a fresh branch.
        // Different signed forks are not automatically considered malicious.
        for (final String name : list(context)) {
            if (!name.startsWith(verified.fingerprint + "_")) continue;
            final Snapshot known = open(context, name);
            if (!known.peer.paired) throw new SecurityException("Peer trust was revoked");
            if (isStrictLinePrefix(journal, known.journal))
                throw new ObservedRollbackException();
        }
        if (list(context).length >= MAX_RECORDS)
            throw new IOException("Peer inbox is full; review or delete old branches");

        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payload)) {
            output.writeInt(proof.length);
            output.write(proof);
            output.writeInt(content.length);
            output.write(content);
        }
        final byte[] plain = payload.toByteArray();
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new SecureRandom());
        cipher.updateAAD(MAGIC);
        final byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new IOException("Invalid inbox nonce");
        final byte[] sealed;
        try { sealed = cipher.doFinal(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
        final File temp = new File(root, destination.getName() + ".new");
        try {
            if (temp.exists() || !temp.createNewFile()) throw new IOException("Inbox is busy");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(MAGIC);
                output.write(iv);
                output.write(sealed);
                output.getFD().sync();
            }
            if (destination.exists() || !temp.renameTo(destination))
                throw new IOException("Cannot commit peer branch");
            return true;
        } finally { if (temp.exists()) temp.delete(); }
    }

    /** A JSONL prefix ends only at an event boundary; a fork is not rollback. */
    static boolean isStrictLinePrefix(final String candidate, final String observed) {
        return candidate != null && observed != null
                && !candidate.isEmpty() && candidate.endsWith("\n")
                && candidate.length() < observed.length() && observed.startsWith(candidate);
    }

    static String[] list(final Context context) throws IOException {
        final File[] files = directory(context).listFiles();
        if (files == null) throw new IOException("Cannot list peer inbox");
        final ArrayList<String> names = new ArrayList<>();
        for (final File file : files) {
            if (file.isFile() && file.getName().matches(NAME_PATTERN)) names.add(file.getName());
        }
        Collections.sort(names);
        return names.toArray(new String[0]);
    }

    static Snapshot open(final Context context, final String name) throws Exception {
        if (name == null || !name.matches(NAME_PATTERN)) throw new SecurityException("Invalid branch ID");
        final File file = new File(directory(context), name);
        if (!file.isFile() || file.length() < MAGIC.length + IV_BYTES + 17
                || file.length() > MAX_JOURNAL + MAX_PROOF + 128)
            throw new IOException("Invalid peer inbox record");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = input.read(buf)) != -1) {
                if (bytes.size() + n > MAX_JOURNAL + MAX_PROOF + 128)
                    throw new IOException("Oversized peer inbox record");
                bytes.write(buf, 0, n);
            }
        }
        final byte[] packet = bytes.toByteArray();
        if (!Arrays.equals(MAGIC, Arrays.copyOfRange(packet, 0, MAGIC.length)))
            throw new SecurityException("Unsupported peer inbox version");
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, packet, MAGIC.length, IV_BYTES));
        cipher.updateAAD(MAGIC);
        final byte[] plain = cipher.doFinal(packet, MAGIC.length + IV_BYTES,
                packet.length - MAGIC.length - IV_BYTES);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain))) {
            final int proofSize = input.readInt();
            if (proofSize < 1 || proofSize > MAX_PROOF) throw new IOException("Bad proof size");
            final byte[] proof = new byte[proofSize];
            input.readFully(proof);
            final int journalSize = input.readInt();
            if (journalSize < 0 || journalSize > MAX_JOURNAL || journalSize != input.available())
                throw new IOException("Bad journal size");
            final byte[] journal = new byte[journalSize];
            input.readFully(journal);
            final KnowledgePeerIdentity.Verification peer = KnowledgePeerIdentity.verifyJournal(
                    context, proof, new ByteArrayInputStream(journal));
            if (!name.equals(recordName(peer.fingerprint, journal)))
                throw new SecurityException("Peer inbox identity commitment mismatch");
            return new Snapshot(new String(journal, StandardCharsets.UTF_8), peer);
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    static void delete(final Context context, final String name) throws IOException {
        if (name == null || !name.matches(NAME_PATTERN)) throw new SecurityException("Invalid branch ID");
        final File file = new File(directory(context), name);
        if (file.exists() && !file.delete()) throw new IOException("Cannot delete peer snapshot");
    }
}

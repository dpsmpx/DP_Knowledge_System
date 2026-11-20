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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Independent, non-clearable-via-inbox high-water history for paired journal
 * signatures. Store hashes of all proper JSONL prefixes, not only the most
 * recent head: this rejects old signed prefixes without misclassifying forks.
 * Persisted in noBackupFilesDir with a separate Android Keystore AES-GCM key.
 */
final class KnowledgePeerHighWater {
    private static final String ALIAS = "dpks_peer_highwater_aes256_v1";
    private static final String FILE = "knowledge_peer_highwater_v1.dat";
    private static final byte[] MAGIC = "DPKSHW01".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int MAX_PREFIXES = 100_000;
    private static final int MAX_ENCRYPTED_BYTES = 18_000_000;
    private static final String DIGEST_PATTERN = "[0-9a-f]{64}";

    private KnowledgePeerHighWater() { }

    private static String hex(final byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        final char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[2 * i] = digits[(bytes[i] >>> 4) & 15];
            chars[2 * i + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }

    private static String digest(final byte[] source) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(source));
    }

    /** Pure testable rule. A fork is permitted unless it equals a known prefix. */
    static boolean wasObservedAsProperPrefix(final Map<String, Set<String>> history,
                                             final String fingerprint,
                                             final String journal) throws Exception {
        final Set<String> prefixes = history.get(fingerprint);
        return prefixes != null && prefixes.contains(digest(
                journal.getBytes(StandardCharsets.UTF_8)));
    }

    /** Strictly append commitments; never delete a high-water marker. */
    static void recordProperPrefixes(final Map<String, Set<String>> history,
                                     final String fingerprint, final String journal)
            throws Exception {
        if (fingerprint == null || !fingerprint.matches(DIGEST_PATTERN) || journal == null)
            throw new SecurityException("Invalid peer high-water identity");
        final byte[] bytes = journal.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > KnowledgeJournalCrypto.MAX_JOURNAL_BYTES)
            throw new IOException("Signed journal too large for rollback guard");
        final Set<String> hashes = history.containsKey(fingerprint)
                ? new HashSet<>(history.get(fingerprint)) : new HashSet<>();
        final MessageDigest running = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < bytes.length; i++) {
            running.update(bytes[i]);
            // The final whole-document digest is not a rollback marker.
            if (bytes[i] == '\n' && i < bytes.length - 1) {
                hashes.add(hex(((MessageDigest) running.clone()).digest()));
                if (hashes.size() > MAX_PREFIXES)
                    throw new IOException("High-water history capacity reached; fail closed");
            }
        }
        // Check total before mutating in-memory state.
        int total = hashes.size();
        for (final Map.Entry<String, Set<String>> entry : history.entrySet()) {
            if (!fingerprint.equals(entry.getKey())) total += entry.getValue().size();
            if (total > MAX_PREFIXES)
                throw new IOException("Protected high-water history full; fail closed");
        }
        history.put(fingerprint, hashes);
    }

    private static KeyStore store() throws Exception {
        final KeyStore value = KeyStore.getInstance("AndroidKeyStore");
        value.load(null);
        return value;
    }

    private static SecretKey secret(final KeyStore store, final boolean allowCreate)
            throws Exception {
        if (!store.containsAlias(ALIAS)) {
            if (!allowCreate) throw new SecurityException("Protected high-water key missing");
            final KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
    }

    private static Map<String, Set<String>> read(final File file, final SecretKey key)
            throws Exception {
        if (!file.isFile() || file.length() <= MAGIC.length + IV_BYTES + 16
                || file.length() > MAX_ENCRYPTED_BYTES)
            throw new SecurityException("Protected high-water ledger missing or invalid");
        final byte[] packet = new byte[(int) file.length()];
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            input.readFully(packet);
            if (input.read() != -1) throw new IOException("High-water ledger changed during read");
        }
        if (!Arrays.equals(MAGIC, Arrays.copyOfRange(packet, 0, MAGIC.length)))
            throw new SecurityException("Unknown high-water ledger version");
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, packet, MAGIC.length, IV_BYTES));
        cipher.updateAAD(MAGIC);
        final byte[] plain = cipher.doFinal(packet, MAGIC.length + IV_BYTES,
                packet.length - MAGIC.length - IV_BYTES);
        try {
            return decode(plain);
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    static Map<String, Set<String>> decode(final byte[] plain) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain))) {
            if (input.readInt() != VERSION) throw new SecurityException("Unknown high-water version");
            final int count = input.readInt();
            if (count < 0 || count > MAX_PREFIXES) throw new IOException("Invalid ledger count");
            final Map<String, Set<String>> result = new HashMap<>();
            for (int i = 0; i < count; i++) {
                final String fingerprint = input.readUTF();
                final String hash = input.readUTF();
                if (!fingerprint.matches(DIGEST_PATTERN) || !hash.matches(DIGEST_PATTERN)
                        || !result.computeIfAbsent(fingerprint, unused -> new HashSet<>()).add(hash))
                    throw new SecurityException("Invalid or duplicate high-water marker");
            }
            if (input.available() != 0) throw new SecurityException("Trailing high-water data");
            return result;
        }
    }

    static byte[] encode(final Map<String, Set<String>> history) throws Exception {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(data)) {
            output.writeInt(VERSION);
            int count = 0;
            for (final Set<String> prefixes : history.values()) count += prefixes.size();
            if (count > MAX_PREFIXES) throw new IOException("High-water ledger full");
            output.writeInt(count);
            for (final Map.Entry<String, Set<String>> entry : history.entrySet()) {
                for (final String hash : entry.getValue()) {
                    output.writeUTF(entry.getKey());
                    output.writeUTF(hash);
                }
            }
        }
        final byte[] result = data.toByteArray();
        if (result.length > MAX_ENCRYPTED_BYTES - MAGIC.length - IV_BYTES - 16)
            throw new IOException("High-water history exceeds portable bound");
        return result;
    }

    private static void commit(final File destination, final SecretKey key,
                               final Map<String, Set<String>> history) throws Exception {
        final byte[] plain = encode(history);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new SecureRandom());
        cipher.updateAAD(MAGIC);
        final byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new IOException("Bad high-water nonce");
        final byte[] sealed;
        try { sealed = cipher.doFinal(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
        if (MAGIC.length + iv.length + sealed.length > MAX_ENCRYPTED_BYTES)
            throw new IOException("High-water ledger exceeds size bound");
        final File temp = File.createTempFile(".dpks-highwater-", ".new",
                destination.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(MAGIC);
                output.write(iv);
                output.write(sealed);
                output.getFD().sync();
            }
            if (!temp.renameTo(destination)) throw new IOException("Cannot commit high-water ledger");
        } finally { temp.delete(); }
    }

    /** Authenticated by the outer password-encrypted ZIP; no Keystore key is exported. */
    static synchronized byte[] exportPortable(final Context context) throws Exception {
        final File file = new File(context.getNoBackupFilesDir(), FILE);
        final KeyStore keys = store();
        final boolean hasKey = keys.containsAlias(ALIAS);
        if (!hasKey && !file.exists()) return null;
        if (!hasKey || !file.isFile())
            throw new SecurityException("High-water recovery required before export");
        return encode(read(file, secret(keys, false)));
    }

    private static Map<String, Set<String>> readPortable(final File verifiedZip)
            throws Exception {
        try (ZipFile zip = new ZipFile(verifiedZip)) {
            final ZipEntry entry = zip.getEntry(KnowledgePortableArchive.HIGHWATER);
            if (entry == null) return null;
            if (entry.getSize() < 8 || entry.getSize() > MAX_ENCRYPTED_BYTES)
                throw new IOException("Invalid portable high-water size");
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = zip.getInputStream(entry)) {
                final byte[] block = new byte[32 * 1024];
                int n;
                while ((n = input.read(block)) != -1) {
                    if (bytes.size() + n > MAX_ENCRYPTED_BYTES)
                        throw new IOException("Oversized high-water checkpoint");
                    bytes.write(block, 0, n);
                }
            }
            final byte[] plain = bytes.toByteArray();
            try { return decode(plain); }
            finally { Arrays.fill(plain, (byte) 0); }
        }
    }

    static int portablePrefixCount(final File verifiedZip) throws Exception {
        final Map<String, Set<String>> values = readPortable(verifiedZip);
        if (values == null) return 0;
        int count = 0;
        for (final Set<String> hashes : values.values()) count += hashes.size();
        return count;
    }

    static int portableSignerCount(final File verifiedZip) throws Exception {
        final Map<String, Set<String>> values = readPortable(verifiedZip);
        return values == null ? 0 : values.size();
    }

    /** Union-only migration, no ever-accepted prefix can disappear. */
    static void mergeMonotonic(final Map<String, Set<String>> current,
                               final Map<String, Set<String>> imported) throws IOException {
        int total = 0;
        for (final Map.Entry<String, Set<String>> entry : imported.entrySet()) {
            current.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>())
                    .addAll(entry.getValue());
        }
        for (final Set<String> values : current.values()) {
            total += values.size();
            if (total > MAX_PREFIXES) throw new IOException("Merged high-water ledger full");
        }
    }

    /** Foreground manual recovery only, merges high-water data monotonically. */
    static synchronized void importPortable(final Context context, final File verifiedZip)
            throws Exception {
        final Map<String, Set<String>> imported = readPortable(verifiedZip);
        if (imported == null) throw new IOException("No protected history in backup");
        final File file = new File(context.getNoBackupFilesDir(), FILE);
        final KeyStore keys = store();
        final boolean hasKey = keys.containsAlias(ALIAS);
        if (!hasKey && file.exists())
            throw new SecurityException("Old high-water key unavailable; manual recovery required");
        final SecretKey key = secret(keys, !hasKey);
        // A missing ledger with an existing key can be recovered only here,
        // after a complete encrypted-backup verification and separate UI consent.
        final Map<String, Set<String>> current = file.isFile()
                ? read(file, key) : new HashMap<>();
        mergeMonotonic(current, imported);
        commit(file, key, current);
    }

    /** Call after signature validation and before writing an inbox entry. */
    static synchronized void observe(final Context context, final String fingerprint,
                                     final String journal) throws Exception {
        if (context == null || fingerprint == null || journal == null)
            throw new SecurityException("Missing authenticated high-water context");
        final File file = new File(context.getNoBackupFilesDir(), FILE);
        final KeyStore store = store();
        final boolean hasKey = store.containsAlias(ALIAS);
        // Removing the ledger while leaving its device-bound key is not a
        // reset; block any new acceptance pending explicit recovery.
        if (hasKey && !file.isFile())
            throw new SecurityException("Protected high-water history missing; recovery required");
        if (!hasKey && file.exists())
            throw new SecurityException("High-water key lost; recovery required");
        final SecretKey key = secret(store, !hasKey);
        final Map<String, Set<String>> history = hasKey ? read(file, key) : new HashMap<>();
        if (!hasKey) {
            // Upgrade installs that already have signed inbox records must
            // inherit their stronger observations before any inbox deletion.
            for (final String name : KnowledgePeerInbox.list(context)) {
                final KnowledgePeerInbox.Snapshot retained = KnowledgePeerInbox.open(context, name);
                recordProperPrefixes(history, retained.peer.fingerprint, retained.journal);
            }
        }
        if (wasObservedAsProperPrefix(history, fingerprint, journal))
            throw new KnowledgePeerInbox.ObservedRollbackException();
        recordProperPrefixes(history, fingerprint, journal);
        commit(file, key, history);
    }
}

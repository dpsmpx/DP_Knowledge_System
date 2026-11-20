/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A user-memorable archive passphrase sealed by Android's nonexportable key. */
final class KnowledgeBackupKeyStore {
    private static final String ALIAS = "dpks_stage21_backup_local_v1";
    private static final String PREF = "dpks_backup_device_sealed_v1";
    private static final String SEALED = "passphrase";
    private static final int IV_BYTES = 12;

    private KnowledgeBackupKeyStore() { }

    static void save(final Context context, final char[] passphrase) throws Exception {
        if (passphrase == null || passphrase.length < 12 || passphrase.length > 512)
            throw new IllegalArgumentException("Invalid backup passphrase");
        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            final KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            generator.generateKey();
        }
        final SecretKey key = ((KeyStore.SecretKeyEntry)
                store.getEntry(ALIAS, null)).getSecretKey();
        final byte[] plain = new String(passphrase).getBytes(StandardCharsets.UTF_8);
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new SecureRandom());
            final byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES)
                throw new IllegalStateException("Unexpected Keystore nonce length");
            final byte[] sealed = cipher.doFinal(plain);
            final byte[] packet = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, packet, 0, iv.length);
            System.arraycopy(sealed, 0, packet, iv.length, sealed.length);
            final String encoded = Base64.encodeToString(packet, Base64.NO_WRAP);
            if (!context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(SEALED, encoded).commit())
                throw new IllegalStateException("Cannot seal backup preference");
            Arrays.fill(packet, (byte) 0);
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    static char[] read(final Context context) throws Exception {
        final SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        final String encoded = prefs.getString(SEALED, null);
        if (encoded == null) throw new IllegalStateException("Backup passphrase not configured");
        final byte[] packet = Base64.decode(encoded, Base64.NO_WRAP);
        if (packet.length <= IV_BYTES + 16) throw new IllegalStateException("Bad sealed secret");
        final KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        final SecretKey key = ((KeyStore.SecretKeyEntry)
                store.getEntry(ALIAS, null)).getSecretKey();
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, packet, 0, IV_BYTES));
        final byte[] plain = cipher.doFinal(packet, IV_BYTES, packet.length - IV_BYTES);
        try { return new String(plain, StandardCharsets.UTF_8).toCharArray(); }
        finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(packet, (byte) 0);
        }
    }

    static void clear(final Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(SEALED).commit();
        // The Keystore key stays nonexportable and cannot reveal old backups
        // without their independent user-memorized passphrase.
    }
}

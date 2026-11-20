/* Copyright 2026 DP Knowledge System contributors; SPDX-License-Identifier: Apache-2.0 */
package net.gsantner.markor.knowledge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.AlgorithmParameters;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.SecureRandom;

/**
 * Explicit local P-256 identity. Never uses device IDs; the private signing
 * key cannot leave Android Keystore. Peers must be manually pinned, not merely
 * inferred from an archive's self-asserted public key.
 */
final class KnowledgePeerIdentity {
    private static final String ALIAS = "dpks_peer_signing_p256_v1";
    private static final String PREF = "dpks_paired_peers_v1";
    private static final String PREFIX = "DPKS-PEER1:";
    private static final String PROOF_SCHEMA = "dpks-peer-journal-proof-v1";
    private static final byte[] DOMAIN = "DPKS-PEER-SNAPSHOT-V1\n"
            .getBytes(StandardCharsets.US_ASCII);

    static final class Verification {
        final String fingerprint;
        final boolean paired;

        Verification(final String fingerprint, final boolean paired) {
            this.fingerprint = fingerprint;
            this.paired = paired;
        }
    }

    private KnowledgePeerIdentity() { }

    private static KeyStore store() throws Exception {
        final KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (!keys.containsAlias(ALIAS)) {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(
                    ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        return keys;
    }

    private static PublicKey localPublic() throws Exception {
        return store().getCertificate(ALIAS).getPublicKey();
    }

    static String invitation() throws Exception {
        final byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        return PREFIX + Base64.encodeToString(localPublic().getEncoded(),
                Base64.NO_WRAP | Base64.URL_SAFE) + ":"
                + Base64.encodeToString(nonce, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    /** Stable across rotation/process death, so copied invitations retain their displayed code. */
    static String currentInvitation(final Context context) throws Exception {
        final String saved = peers(context).getString("local_invitation", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                if (fingerprint(saved).equals(ownFingerprint())) return saved;
            } catch (Exception invalid) { /* A regenerated Android Keystore identity invalidates it. */ }
        }
        final String fresh = invitation();
        if (!peers(context).edit().putString("local_invitation", fresh).commit())
            throw new IOException("Cannot retain pairing invitation");
        return fresh;
    }

    private static PublicKey decode(final String invitation) throws Exception {
        if (invitation == null || invitation.length() > 2500
                || !invitation.startsWith(PREFIX)) throw new SecurityException("Bad peer invite");
        final String[] parts = invitation.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 2) throw new SecurityException("Invalid pairing request");
        final byte[] nonce = Base64.decode(parts[1], Base64.NO_WRAP | Base64.URL_SAFE);
        if (nonce.length != 12) throw new SecurityException("Invalid pairing nonce");
        final byte[] der = Base64.decode(parts[0],
                Base64.NO_WRAP | Base64.URL_SAFE);
        if (der.length < 75 || der.length > 300) throw new SecurityException("Bad peer key length");
        final PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(der));
        if (!"EC".equals(key.getAlgorithm())) throw new SecurityException("Wrong peer key");
        // Pin the exact named P-256 parameters, not merely a 256-bit curve.
        final java.security.interfaces.ECPublicKey ec =
                (java.security.interfaces.ECPublicKey) key;
        final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        final ECParameterSpec p256 = parameters.getParameterSpec(ECParameterSpec.class);
        final ECParameterSpec actual = ec.getParams();
        if (!actual.getCurve().equals(p256.getCurve())
                || !actual.getGenerator().equals(p256.getGenerator())
                || !actual.getOrder().equals(p256.getOrder())
                || actual.getCofactor() != p256.getCofactor())
            throw new SecurityException("Unsupported peer curve");
        return key;
    }

    private static String hex(final byte[] value) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        final char[] hex = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            hex[2 * i] = alphabet[(value[i] >>> 4) & 15];
            hex[2 * i + 1] = alphabet[value[i] & 15];
        }
        return new String(hex);
    }

    static String fingerprint(final String invite) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(decode(invite).getEncoded()));
    }

    static String ownFingerprint() throws Exception { return fingerprint(invitation()); }

    static String verificationCode(final String invitation) throws Exception {
        final PublicKey peer = decode(invitation);
        final byte[] nonce = Base64.decode(invitation.substring(
                invitation.lastIndexOf(':') + 1), Base64.NO_WRAP | Base64.URL_SAFE);
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(peer.getEncoded());
        digest.update(nonce);
        // Short-lived display code. The receiver MUST compare it directly
        // with the sender's device, not with the received invitation text.
        return hex(digest.digest()).substring(0, 12);
    }

    private static SharedPreferences peers(final Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static void pair(final Context context, final String invitation,
                     final String verifiedCode) throws Exception {
        final PublicKey key = decode(invitation);
        final String fp = hex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        if (verifiedCode == null || !verificationCode(invitation)
                .equalsIgnoreCase(verifiedCode.trim())) {
            throw new SecurityException("Peer fingerprint confirmation differs");
        }
        if (fp.equals(ownFingerprint()))
            throw new SecurityException("Cannot pair with own identity");
        final String encoded = Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
        if (!peers(context).edit().putString("key_" + fp, encoded).commit())
            throw new IOException("Peer trust store could not be updated");
    }

    static void revoke(final Context context, final String fingerprint) {
        if (fingerprint != null && fingerprint.matches("[0-9a-f]{64}"))
            peers(context).edit().remove("key_" + fingerprint).commit();
    }

    static String[] pairedFingerprints(final Context context) {
        final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (final String key : peers(context).getAll().keySet()) {
            if (key.matches("key_[0-9a-f]{64}")) names.add(key.substring(4));
        }
        java.util.Collections.sort(names);
        return names.toArray(new String[0]);
    }

    private static boolean trusted(final Context context, final String fp, final PublicKey key) {
        final String expected = Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
        return expected.equals(peers(context).getString("key_" + fp, null));
    }

    private static byte[] journalHash(final InputStream data) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] block = new byte[65536];
        long count = 0;
        int n;
        while ((n = data.read(block)) != -1) {
            count += n;
            if (count > KnowledgeJournalCrypto.MAX_JOURNAL_BYTES)
                throw new IOException("Journal too large for peer proof");
            digest.update(block, 0, n);
        }
        return digest.digest();
    }

    /** Create proof only after Python validated the authoritative chain. */
    static byte[] signJournal(final File journal) throws Exception {
        if (journal == null || !journal.isFile()) return null;
        final byte[] hash;
        try (InputStream input = new FileInputStream(journal)) { hash = journalHash(input); }
        final long issued = System.currentTimeMillis();
        final Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign((java.security.PrivateKey) store().getKey(ALIAS, null));
        signature.update(DOMAIN);
        signature.update(hash);
        signature.update(java.nio.ByteBuffer.allocate(8).putLong(issued).array());
        final JSONObject proof = new JSONObject();
        proof.put("schema", PROOF_SCHEMA);
        proof.put("invitation", invitation());
        proof.put("journal_sha256", hex(hash));
        proof.put("issued_ms", issued);
        proof.put("signature", Base64.encodeToString(signature.sign(), Base64.NO_WRAP));
        return proof.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Authenticates the signed bytes; unpaired senders are NOT trusted. */
    static Verification verifyJournal(final Context context, final byte[] proofBytes,
                                      final InputStream journal) throws Exception {
        if (proofBytes == null || proofBytes.length > 4096 || journal == null)
            throw new SecurityException("Missing peer proof");
        final JSONObject proof = new JSONObject(new String(proofBytes, StandardCharsets.UTF_8));
        if (!PROOF_SCHEMA.equals(proof.optString("schema")))
            throw new SecurityException("Unknown peer proof schema");
        final String invite = proof.getString("invitation");
        final PublicKey key = decode(invite);
        final String fp = hex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        final byte[] digest;
        try (InputStream input = journal) { digest = journalHash(input); }
        if (!hex(digest).equals(proof.getString("journal_sha256")))
            throw new SecurityException("Peer journal commitment mismatch");
        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(key);
        verifier.update(DOMAIN);
        verifier.update(digest);
        verifier.update(java.nio.ByteBuffer.allocate(8)
                .putLong(proof.getLong("issued_ms")).array());
        final byte[] sig = Base64.decode(proof.getString("signature"), Base64.NO_WRAP);
        if (!verifier.verify(sig)) throw new SecurityException("Invalid peer signature");
        return new Verification(fp, trusted(context, fp, key));
    }
}

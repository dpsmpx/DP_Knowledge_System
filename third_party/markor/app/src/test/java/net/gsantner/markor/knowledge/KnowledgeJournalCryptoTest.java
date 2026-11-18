/*
 * Copyright 2026 DP Knowledge System contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package net.gsantner.markor.knowledge;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class KnowledgeJournalCryptoTest {
    @Test
    public void portableRoundTripDoesNotReuseNonce() throws Exception {
        final byte[] data = "{\"seq\":1,\"id\":\"synthetic\"}\n".getBytes(StandardCharsets.UTF_8);
        final char[] password = "synthetic test password with sufficient entropy".toCharArray();
        final byte[] one = KnowledgeJournalCrypto.encrypt(data, password);
        final byte[] two = KnowledgeJournalCrypto.encrypt(data, password);
        assertFalse(Arrays.equals(one, two));
        assertArrayEquals(data, KnowledgeJournalCrypto.decrypt(one, password));
    }

    @Test(expected = GeneralSecurityException.class)
    public void wrongPassphraseFailsAuthentication() throws Exception {
        final byte[] data = "synthetic".getBytes(StandardCharsets.UTF_8);
        final byte[] encrypted = KnowledgeJournalCrypto.encrypt(data,
                "synthetic correct passphrase".toCharArray());
        KnowledgeJournalCrypto.decrypt(encrypted, "synthetic wrong passphrase".toCharArray());
    }

    @Test(expected = GeneralSecurityException.class)
    public void corruptedBackupFailsAuthentication() throws Exception {
        final byte[] data = "synthetic".getBytes(StandardCharsets.UTF_8);
        final char[] password = "synthetic correct passphrase".toCharArray();
        final byte[] encrypted = KnowledgeJournalCrypto.encrypt(data, password);
        encrypted[encrypted.length - 1] ^= 1;
        KnowledgeJournalCrypto.decrypt(encrypted, password);
    }

    @Test(expected = GeneralSecurityException.class)
    public void rejectsShortPassphrase() throws Exception {
        KnowledgeJournalCrypto.encrypt(new byte[]{1}, "short".toCharArray());
    }
}

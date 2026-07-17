package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM implementation that packages a format version, random nonce, ciphertext and tag. */
public final class AesGcmSecretTextCipher implements SecretTextCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final byte PAYLOAD_VERSION = 1;

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom secureRandom;

    public AesGcmSecretTextCipher(byte[] key, String keyId) {
        this(key, keyId, new SecureRandom());
    }

    AesGcmSecretTextCipher(byte[] keyBytes, String keyId, SecureRandom secureRandom) {
        byte[] copiedKey = Arrays.copyOf(keyBytes, keyBytes.length);
        if (copiedKey.length != 16 && copiedKey.length != 24 && copiedKey.length != 32) {
            throw new IllegalArgumentException("Intent trace AES key must be 128, 192 or 256 bits");
        }
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Intent trace encryption key id is required");
        }
        this.key = new SecretKeySpec(copiedKey, "AES");
        this.keyId = keyId;
        this.secureRandom = secureRandom;
        Arrays.fill(copiedKey, (byte) 0);
    }

    @Override
    public Optional<EncryptedText> encrypt(String plainText) {
        if (plainText == null) {
            return Optional.empty();
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                    .put(PAYLOAD_VERSION)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
            return Optional.of(new EncryptedText(keyId, payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Intent trace encryption failed", e);
        }
    }

    @Override
    public Optional<String> decrypt(EncryptedText encryptedText) {
        if (encryptedText == null) {
            return Optional.empty();
        }
        if (!keyId.equals(encryptedText.keyId())) {
            throw new IllegalArgumentException("Intent trace ciphertext uses an unavailable key");
        }
        byte[] payload = encryptedText.payload();
        if (payload.length <= 1 + NONCE_BYTES || payload[0] != PAYLOAD_VERSION) {
            throw new IllegalArgumentException("Intent trace ciphertext has an unsupported format");
        }
        try {
            byte[] nonce = Arrays.copyOfRange(payload, 1, 1 + NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, 1 + NONCE_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return Optional.of(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Intent trace ciphertext could not be authenticated", e);
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }
}

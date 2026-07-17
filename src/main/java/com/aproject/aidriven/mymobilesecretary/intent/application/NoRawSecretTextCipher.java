package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Optional;

/** Local/test implementation that deliberately does not retain raw model input or output. */
final class NoRawSecretTextCipher implements SecretTextCipher {

    @Override
    public Optional<EncryptedText> encrypt(String plainText) {
        return Optional.empty();
    }

    @Override
    public Optional<String> decrypt(EncryptedText encryptedText) {
        return Optional.empty();
    }

    @Override
    public boolean enabled() {
        return false;
    }
}

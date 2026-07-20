package com.aproject.aidriven.mymobilesecretary.media.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/** Filesystem implementation for the monolith; storage keys remain portable to object storage. */
@Component
public class LocalMediaObjectStorage implements MediaObjectStorage {

    private final Path root;

    public LocalMediaObjectStorage(MediaStorageProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String storageKey, byte[] bytes) {
        Path target = resolve(storageKey);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } catch (IOException failure) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original storage failure remains the actionable exception.
                }
            }
            throw new MediaStorageException("unable to persist media object", failure);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException failure) {
            throw new MediaStorageException("unable to read media object", failure);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException failure) {
            throw new MediaStorageException("unable to remove incomplete media object", failure);
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || !storageKey.matches("[a-f0-9]{2}/[a-f0-9-]{36}")) {
            throw new SecurityException("invalid media storage key");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("media storage key escapes configured root");
        }
        return resolved;
    }

    public static class MediaStorageException extends RuntimeException {
        public MediaStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

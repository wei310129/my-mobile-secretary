package com.aproject.aidriven.mymobilesecretary.media.application;

/** Private byte storage. Keys are generated internally and are never accepted from API callers. */
public interface MediaObjectStorage {
    void put(String storageKey, byte[] bytes);

    byte[] read(String storageKey);

    void delete(String storageKey);
}

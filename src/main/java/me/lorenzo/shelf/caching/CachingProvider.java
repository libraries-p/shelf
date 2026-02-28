package me.lorenzo.shelf.caching;

import me.lorenzo.presence.vault.record.DataRecord;

import java.util.List;
import java.util.Optional;

public interface CachingProvider {

    Optional<DataRecord> get(String key);

    void put(String key, DataRecord record);

    Optional<List<DataRecord>> getList(String key);

    void putList(String key, List<DataRecord> records);

    /**
     * Removes a single cached entry by exact key.
     */
    void invalidate(String key);

    /**
     * Removes all cached entries whose key starts with the given prefix.
     * Used to invalidate an entire collection on writes.
     */
    void invalidateAll(String keyPrefix);

    void clear();
}

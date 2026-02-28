package me.lorenzo.shelf.caching.impl;

import me.lorenzo.presence.vault.record.DataRecord;
import me.lorenzo.shelf.caching.CachingProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class LocalMapCaching implements CachingProvider {

    private final Map<String, CacheEntry<DataRecord>> singles = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<List<DataRecord>>> lists = new ConcurrentHashMap<>();
    private final long ttlMs;

    public LocalMapCaching() {
        this.ttlMs = 0;
    }

    public LocalMapCaching(Duration ttl) {
        this.ttlMs = ttl.toMillis();
    }

    @Override
    public Optional<DataRecord> get(String key) {
        CacheEntry<DataRecord> entry = singles.get(key);
        if (entry == null || entry.isExpired()) {
            singles.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void put(String key, DataRecord record) {
        singles.put(key, new CacheEntry<>(record, expiryFor()));
    }

    @Override
    public Optional<List<DataRecord>> getList(String key) {
        CacheEntry<List<DataRecord>> entry = lists.get(key);
        if (entry == null || entry.isExpired()) {
            lists.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void putList(String key, List<DataRecord> records) {
        lists.put(key, new CacheEntry<>(records, expiryFor()));
    }

    @Override
    public void invalidate(String key) {
        singles.remove(key);
        lists.remove(key);
    }

    @Override
    public void invalidateAll(String keyPrefix) {
        singles.keySet().removeIf(k -> k.startsWith(keyPrefix));
        lists.keySet().removeIf(k -> k.startsWith(keyPrefix));
    }

    @Override
    public void clear() {
        singles.clear();
        lists.clear();
    }

    private long expiryFor() {
        return ttlMs > 0 ? System.currentTimeMillis() + ttlMs : 0;
    }

    private record CacheEntry<T>(T value, long expiryMs) {
        boolean isExpired() {
            return expiryMs > 0 && System.currentTimeMillis() > expiryMs;
        }
    }
}

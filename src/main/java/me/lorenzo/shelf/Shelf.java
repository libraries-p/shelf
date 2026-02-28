package me.lorenzo.shelf;

import me.lorenzo.presence.entity.EntityBuilderInstructions;
import me.lorenzo.presence.entity.service.EBIService;
import me.lorenzo.presence.vault.DataVault;
import me.lorenzo.presence.vault.query.Query;
import me.lorenzo.presence.vault.record.DataRecord;
import me.lorenzo.services.service.holder.Services;
import me.lorenzo.shelf.caching.CachingProvider;
import me.lorenzo.shelf.caching.impl.LocalMapCaching;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A cache-first storage abstraction.
 *
 * Persistence is optional. When not configured, Shelf acts as a pure in-memory
 * store — populate it manually via seed() and read via get().
 *
 * Caching defaults to LocalMapCaching (in-memory, no TTL) if not configured.
 *
 * Usage:
 * <pre>
 *   // Pure cache (default)
 *   Shelf shelf = Shelf.create().build();
 *
 *   // Cache + persistence
 *   Shelf shelf = Shelf.create()
 *       .withPersistence(new MongoDataVault(...))
 *       .withCaching(new RedisCaching(...))
 *       .build();
 * </pre>
 */
public class Shelf {

    private final DataVault vault;       // nullable — persistence is optional
    private final CachingProvider cache;

    private Shelf(DataVault vault, CachingProvider cache) {
        this.vault = vault;
        this.cache = cache;
    }

    public static Builder create() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Read — cache-first, fall through to persistence on miss (if configured)
    // -------------------------------------------------------------------------

    public Optional<DataRecord> get(String collection, Query query) {
        String key = cacheKey(collection, query);
        Optional<DataRecord> cached = cache.get(key);
        if (cached.isPresent()) return cached;

        if (vault == null) return Optional.empty();

        Optional<DataRecord> result = vault.findOne(collection, query);
        result.ifPresent(r -> cache.put(key, r));
        return result;
    }

    public List<DataRecord> getAll(String collection, Query query) {
        String key = cacheKey(collection, query);
        Optional<List<DataRecord>> cached = cache.getList(key);
        if (cached.isPresent()) return cached.get();

        if (vault == null) return List.of();

        List<DataRecord> result = vault.find(collection, query);
        cache.putList(key, result);
        return result;
    }

    public <T> Optional<T> get(String collection, Query query, Class<T> type) {
        EntityBuilderInstructions<T> builder = ebiService().getOrThrow(type);
        return get(collection, query).map(r -> builder.assemble(r.asMap()));
    }

    public <T> List<T> getAll(String collection, Query query, Class<T> type) {
        EntityBuilderInstructions<T> builder = ebiService().getOrThrow(type);
        return getAll(collection, query).stream()
                .map(r -> builder.assemble(r.asMap()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Write — vault (if present) then invalidate collection cache
    // No-op on vault when persistence is not configured.
    // -------------------------------------------------------------------------

    public void put(String collection, DataRecord record) {
        if (vault != null) vault.insert(collection, record);
        cache.invalidateAll(collectionPrefix(collection));
    }

    public void putAll(String collection, List<DataRecord> records) {
        if (vault != null) vault.insertMany(collection, records);
        cache.invalidateAll(collectionPrefix(collection));
    }

    public void update(String collection, Query query, DataRecord updates) {
        if (vault != null) vault.update(collection, query, updates);
        cache.invalidateAll(collectionPrefix(collection));
    }

    public void upsert(String collection, Query query, DataRecord record) {
        if (vault != null) vault.upsert(collection, query, record);
        cache.invalidateAll(collectionPrefix(collection));
    }

    public void remove(String collection, Query query) {
        if (vault != null) vault.delete(collection, query);
        cache.invalidateAll(collectionPrefix(collection));
    }

    // -------------------------------------------------------------------------
    // Manual cache population — for cache-only mode or pre-warming
    // -------------------------------------------------------------------------

    /** Stores a record directly in cache under the given query key, without touching persistence. */
    public void seed(String collection, Query query, DataRecord record) {
        cache.put(cacheKey(collection, query), record);
    }

    /** Stores a list of records directly in cache under the given query key, without touching persistence. */
    public void seedAll(String collection, Query query, List<DataRecord> records) {
        cache.putList(cacheKey(collection, query), records);
    }

    // -------------------------------------------------------------------------
    // Cache management only — never touches persistence
    // -------------------------------------------------------------------------

    /** Evicts the cached result for a specific query. */
    public void evict(String collection, Query query) {
        cache.invalidate(cacheKey(collection, query));
    }

    /** Evicts all cached entries for a collection. */
    public void evictAll(String collection) {
        cache.invalidateAll(collectionPrefix(collection));
    }

    /** Clears the entire cache. Does not affect persisted data. */
    public void flush() {
        cache.clear();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private String cacheKey(String collection, Query query) {
        StringBuilder sb = new StringBuilder(collection).append("::");
        query.filters().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("=").append(e.getValue()).append("::"));
        if (query.getLimit() > 0) sb.append("LIMIT=").append(query.getLimit()).append("::");
        if (query.getOffset() > 0) sb.append("OFFSET=").append(query.getOffset()).append("::");
        return sb.toString();
    }

    /** Returns the underlying DataVault, or null if no persistence was configured. */
    public DataVault vault() {
        return vault;
    }

    private String collectionPrefix(String collection) {
        return collection + "::";
    }

    private EBIService ebiService() {
        return Services.getOrThrow(EBIService.class);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static class Builder {

        private DataVault vault = null;
        private CachingProvider cache = new LocalMapCaching();

        public Builder withPersistence(DataVault vault) {
            this.vault = vault;
            return this;
        }

        public Builder withCaching(CachingProvider cache) {
            this.cache = cache;
            return this;
        }

        public Shelf build() {
            return new Shelf(vault, cache);
        }
    }
}

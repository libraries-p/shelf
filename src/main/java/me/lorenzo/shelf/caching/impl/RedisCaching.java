package me.lorenzo.shelf.caching.impl;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.lorenzo.presence.vault.record.DataRecord;
import me.lorenzo.presence.vault.record.simple.SimpleDataRecord;
import me.lorenzo.shelf.caching.CachingProvider;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RedisCaching implements CachingProvider {

    // Prefix to separate single vs list entries in Redis keyspace
    private static final String SINGLE_PREFIX = "shelf:s:";
    private static final String LIST_PREFIX = "shelf:l:";

    private final JedisPool pool;
    private final Gson gson = new Gson();
    private final int ttlSeconds;

    public RedisCaching(String host, int port) {
        this.pool = new JedisPool(new JedisPoolConfig(), host, port);
        this.ttlSeconds = 0;
    }

    public RedisCaching(String host, int port, Duration ttl) {
        this.pool = new JedisPool(new JedisPoolConfig(), host, port);
        this.ttlSeconds = (int) ttl.toSeconds();
    }

    public RedisCaching(String host, int port, String password, Duration ttl) {
        this.pool = new JedisPool(new JedisPoolConfig(), host, port, 2000, password);
        this.ttlSeconds = (int) ttl.toSeconds();
    }

    @Override
    public Optional<DataRecord> get(String key) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(SINGLE_PREFIX + key);
            if (json == null) return Optional.empty();
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> map = gson.fromJson(json, mapType);
            return Optional.of(new SimpleDataRecord(map));
        }
    }

    @Override
    public void put(String key, DataRecord record) {
        try (Jedis jedis = pool.getResource()) {
            String json = gson.toJson(record.asMap());
            String redisKey = SINGLE_PREFIX + key;
            if (ttlSeconds > 0) {
                jedis.setex(redisKey, ttlSeconds, json);
            } else {
                jedis.set(redisKey, json);
            }
        }
    }

    @Override
    public Optional<List<DataRecord>> getList(String key) {
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.get(LIST_PREFIX + key);
            if (json == null) return Optional.empty();
            Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> maps = gson.fromJson(json, listType);
            List<DataRecord> records = maps.stream()
                    .map(m -> (DataRecord) new SimpleDataRecord(m))
                    .toList();
            return Optional.of(records);
        }
    }

    @Override
    public void putList(String key, List<DataRecord> records) {
        try (Jedis jedis = pool.getResource()) {
            String json = gson.toJson(records.stream().map(DataRecord::asMap).toList());
            String redisKey = LIST_PREFIX + key;
            if (ttlSeconds > 0) {
                jedis.setex(redisKey, ttlSeconds, json);
            } else {
                jedis.set(redisKey, json);
            }
        }
    }

    @Override
    public void invalidate(String key) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(SINGLE_PREFIX + key, LIST_PREFIX + key);
        }
    }

    @Override
    public void invalidateAll(String keyPrefix) {
        try (Jedis jedis = pool.getResource()) {
            deleteByPattern(jedis, SINGLE_PREFIX + keyPrefix + "*");
            deleteByPattern(jedis, LIST_PREFIX + keyPrefix + "*");
        }
    }

    @Override
    public void clear() {
        try (Jedis jedis = pool.getResource()) {
            deleteByPattern(jedis, SINGLE_PREFIX + "*");
            deleteByPattern(jedis, LIST_PREFIX + "*");
        }
    }

    public void close() {
        pool.close();
    }

    private void deleteByPattern(Jedis jedis, String pattern) {
        String cursor = "0";
        ScanParams params = new ScanParams().match(pattern).count(100);
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            List<String> keys = result.getResult();
            if (!keys.isEmpty()) jedis.del(keys.toArray(new String[0]));
            cursor = result.getCursor();
        } while (!cursor.equals("0"));
    }
}

package me.lorenzo.shelf.caching.impl;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.lorenzo.presence.vault.record.DataRecord;
import me.lorenzo.presence.vault.record.simple.SimpleDataRecord;
import me.lorenzo.shelf.caching.CachingProvider;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FileCaching implements CachingProvider {

    private final Path directory;
    private final Gson gson = new Gson();
    private final long ttlMs;

    public FileCaching(Path directory) {
        this.directory = directory;
        this.ttlMs = 0;
        init();
    }

    public FileCaching(Path directory, Duration ttl) {
        this.directory = directory;
        this.ttlMs = ttl.toMillis();
        init();
    }

    @Override
    public Optional<DataRecord> get(String key) {
        Path file = keyToPath(key);
        if (!Files.exists(file)) return Optional.empty();
        try {
            CacheFile cf = readFile(file);
            if (cf == null || cf.isList() || cf.isExpired()) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> map = gson.fromJson(gson.toJson(cf.data), mapType);
            return Optional.of(new SimpleDataRecord(map));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, DataRecord record) {
        CacheFile cf = new CacheFile(key, false, record.asMap(), null, expiryFor());
        writeFile(keyToPath(key), cf);
    }

    @Override
    public Optional<List<DataRecord>> getList(String key) {
        Path file = keyToPath(key);
        if (!Files.exists(file)) return Optional.empty();
        try {
            CacheFile cf = readFile(file);
            if (cf == null || !cf.isList() || cf.isExpired()) {
                Files.deleteIfExists(file);
                return Optional.empty();
            }
            Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> maps = gson.fromJson(gson.toJson(cf.dataList), listType);
            List<DataRecord> records = maps.stream()
                    .map(m -> (DataRecord) new SimpleDataRecord(m))
                    .toList();
            return Optional.of(records);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void putList(String key, List<DataRecord> records) {
        List<Map<String, Object>> maps = records.stream().map(DataRecord::asMap).toList();
        CacheFile cf = new CacheFile(key, true, null, maps, expiryFor());
        writeFile(keyToPath(key), cf);
    }

    @Override
    public void invalidate(String key) {
        try {
            Files.deleteIfExists(keyToPath(key));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void invalidateAll(String keyPrefix) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : stream) {
                CacheFile cf = readFile(file);
                if (cf != null && cf.key.startsWith(keyPrefix)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void clear() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : stream) Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void init() {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory: " + directory, e);
        }
    }

    private Path keyToPath(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private CacheFile readFile(Path file) throws IOException {
        String json = Files.readString(file);
        return gson.fromJson(json, CacheFile.class);
    }

    private void writeFile(Path file, CacheFile cf) {
        try {
            Files.writeString(file, gson.toJson(cf));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private long expiryFor() {
        return ttlMs > 0 ? System.currentTimeMillis() + ttlMs : 0;
    }

    private static class CacheFile {
        String key;
        boolean list;
        Object data;
        Object dataList;
        long expiresAt;

        CacheFile(String key, boolean list, Object data, Object dataList, long expiresAt) {
            this.key = key;
            this.list = list;
            this.data = data;
            this.dataList = dataList;
            this.expiresAt = expiresAt;
        }

        boolean isList() { return list; }

        boolean isExpired() {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
        }
    }
}

package com.github.lechandonga.versionedobjectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lechandonga.versionedobjectstoragejava.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 版本化对象存储核心服务。
 *
 * 一致性模型：
 * - 每个对象一份清单（manifest），记录全部版本元数据，版本号从 1 单调递增；
 * - 同一对象的写操作按 key 加锁串行化，并发上传各自产生独立版本，不会相互覆盖；
 * - 清单更新采用临时文件 + 原子移动，任何失败都不会留下半写状态；
 * - 上传时若声明了校验值且不一致，本次写入整体撤销，原有版本不受影响。
 */
@Service
public class VersionedObjectStore {

    private static final Logger log = LoggerFactory.getLogger(VersionedObjectStore.class);

    private final BlobStore blobStore;
    private final Path objectsDir;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public VersionedObjectStore(BlobStore blobStore, StorageProperties properties) {
        this.blobStore = blobStore;
        this.objectsDir = properties.getRoot().resolve("objects");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(objectsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 上传对象内容，产生一个新版本并返回其元数据。
     *
     * @param expectedSha256 调用方声明的校验值，可为 null；不一致时撤销本次写入
     */
    public VersionMetadata put(String key, byte[] content, String expectedSha256) {
        String actual = BlobStore.sha256Hex(content);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actual)) {
            log.warn("upload rejected key={} expected={} actual={}", key, expectedSha256, actual);
            throw new StoreException.ChecksumMismatch(expectedSha256, actual);
        }
        synchronized (lockFor(key)) {
            String sha256 = blobStore.put(content);
            List<VersionMetadata> versions = new ArrayList<>(readManifest(key));
            VersionMetadata meta = new VersionMetadata(versions.size() + 1L, sha256, content.length, Instant.now(), false);
            versions.add(meta);
            writeManifest(key, versions);
            log.info("version created key={} versionId={} sha256={} size={}", key, meta.versionId(), sha256, meta.size());
            return meta;
        }
    }

    /** 读取对象最新版本内容。 */
    public byte[] get(String key) {
        List<VersionMetadata> versions = requireVersions(key);
        return readVersion(key, versions.get(versions.size() - 1));
    }

    /** 读取指定版本内容。 */
    public byte[] getVersion(String key, long versionId) {
        return readVersion(key, requireVersion(key, versionId));
    }

    /** 按稳定顺序（versionId 升序）列出全部版本。 */
    public List<VersionMetadata> listVersions(String key) {
        return List.copyOf(requireVersions(key));
    }

    /** 回滚到指定历史版本：以该版本内容产生一个新版本，不改写历史。 */
    public VersionMetadata rollback(String key, long versionId) {
        synchronized (lockFor(key)) {
            VersionMetadata target = requireVersion(key, versionId);
            byte[] content = readVersion(key, target);
            String sha256 = blobStore.put(content);
            List<VersionMetadata> versions = new ArrayList<>(readManifest(key));
            VersionMetadata meta = new VersionMetadata(versions.size() + 1L, sha256, content.length, Instant.now(), false);
            versions.add(meta);
            writeManifest(key, versions);
            log.info("rollback key={} fromVersion={} newVersionId={} sha256={}", key, versionId, meta.versionId(), sha256);
            return meta;
        }
    }

    /**
     * 清理指定版本：标记为 purged，若底层 blob 已无任何版本引用则回收。
     * 清理后读取该版本抛 VersionPurged，与 VersionNotFound 可区分。
     */
    public void purgeVersion(String key, long versionId) {
        synchronized (lockFor(key)) {
            List<VersionMetadata> versions = new ArrayList<>(readManifest(key));
            VersionMetadata target = null;
            for (int i = 0; i < versions.size(); i++) {
                if (versions.get(i).versionId() == versionId) {
                    target = versions.get(i);
                    if (target.purged()) {
                        return; // 幂等
                    }
                    versions.set(i, new VersionMetadata(target.versionId(), target.sha256(), target.size(), target.createdAt(), true));
                    break;
                }
            }
            if (target == null) {
                throw new StoreException.VersionNotFound(key, versionId);
            }
            writeManifest(key, versions);
            if (!isReferenced(target.sha256())) {
                blobStore.delete(target.sha256());
                log.info("blob reclaimed sha256={}", target.sha256());
            }
            log.info("version purged key={} versionId={} sha256={}", key, versionId, target.sha256());
        }
    }

    private byte[] readVersion(String key, VersionMetadata meta) {
        if (meta.purged()) {
            throw new StoreException.VersionPurged(key, meta.versionId());
        }
        return blobStore.get(meta.sha256());
    }

    private VersionMetadata requireVersion(String key, long versionId) {
        return requireVersions(key).stream()
                .filter(v -> v.versionId() == versionId)
                .findFirst()
                .orElseThrow(() -> new StoreException.VersionNotFound(key, versionId));
    }

    private List<VersionMetadata> requireVersions(String key) {
        List<VersionMetadata> versions = readManifest(key);
        if (versions.isEmpty()) {
            throw new StoreException.ObjectNotFound(key);
        }
        return versions;
    }

    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    private Path manifestPath(String key) {
        return objectsDir.resolve(URLEncoder.encode(key, StandardCharsets.UTF_8)).resolve("manifest.json");
    }

    private List<VersionMetadata> readManifest(String key) {
        Path path = manifestPath(key);
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return mapper.readValue(Files.readAllBytes(path),
                    mapper.getTypeFactory().constructCollectionType(List.class, VersionMetadata.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeManifest(String key, List<VersionMetadata> versions) {
        Path path = manifestPath(key);
        Path tmp = null;
        try {
            Files.createDirectories(path.getParent());
            tmp = Files.createTempFile(path.getParent(), "manifest-", ".tmp");
            mapper.writeValue(tmp.toFile(), versions);
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
            throw new UncheckedIOException(e);
        }
    }

    /** 扫描全部清单，判断 blob 是否仍被未清理的版本引用。 */
    private boolean isReferenced(String sha256) {
        try (Stream<Path> dirs = Files.list(objectsDir)) {
            return dirs.filter(Files::isDirectory)
                    .map(d -> d.resolve("manifest.json"))
                    .filter(Files::exists)
                    .anyMatch(m -> {
                        try {
                            List<VersionMetadata> vs = mapper.readValue(Files.readAllBytes(m),
                                    mapper.getTypeFactory().constructCollectionType(List.class, VersionMetadata.class));
                            return vs.stream().anyMatch(v -> !v.purged() && v.sha256().equals(sha256));
                        } catch (IOException e) {
                            return true; // 读不出来时保守认为仍被引用
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

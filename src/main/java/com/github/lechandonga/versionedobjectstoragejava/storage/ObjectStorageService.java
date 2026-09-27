package com.github.lechandonga.versionedobjectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.ChecksumMismatchException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.ObjectNotFoundException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.StorageException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.VersionNotFoundException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.VersionPurgedException;
import com.github.lechandonga.versionedobjectstoragejava.storage.model.ObjectMetadata;
import com.github.lechandonga.versionedobjectstoragejava.storage.model.ObjectVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 基于本地文件系统的版本化对象存储服务。
 *
 * <p>磁盘布局：
 * <pre>
 *   {root}/blobs/{sha256}        内容寻址的去重 blob 存储（相同内容仅存一份）
 *   {root}/meta/{base64(key)}/meta.json  每个对象的版本元数据
 * </pre>
 *
 * <p>一致性模型：
 * <ul>
 *   <li>同一 key 的写操作通过 per-key 锁串行化，版本号严格递增；</li>
 *   <li>blob 与元数据均通过“临时文件 + 原子 move”落盘，不存在半写状态；</li>
 *   <li>先写 blob 再提交元数据：元数据提交失败时版本不可见，blob 可能成为无害孤儿；</li>
 *   <li>读取时重新计算 SHA-256 校验，发现损坏即抛出 {@link ChecksumMismatchException}。</li>
 * </ul>
 */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    private final Path root;
    private final Path blobsDir;
    private final Path metaDir;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public ObjectStorageService(@Value("${storage.root:./data}") String rootDir) {
        this.root = Path.of(rootDir);
        this.blobsDir = root.resolve("blobs");
        this.metaDir = root.resolve("meta");
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            Files.createDirectories(blobsDir);
            Files.createDirectories(metaDir);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to initialize storage root " + root, e);
        }
    }

    /**
     * 上传对象内容，产生一个新版本。内容相同的对象共享同一份 blob 存储。
     *
     * @param key            对象 key
     * @param content        对象内容
     * @param expectedSha256 调用方声明的 SHA-256（十六进制），可为 null；不一致时拒绝且不落盘
     * @return 新产生的版本记录
     */
    public ObjectVersion put(String key, byte[] content, String expectedSha256) {
        String sha256 = sha256Hex(content);
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(sha256)) {
            throw new ChecksumMismatchException(expectedSha256, sha256);
        }
        ReentrantLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            // 1. 写 blob（已存在则跳过，实现去重）
            writeBlobIfAbsent(sha256, content);
            // 2. 提交元数据：追加新版本
            ObjectMetadata meta = loadMetadata(key);
            long versionId = meta.getVersions().stream()
                    .mapToLong(ObjectVersion::getVersionId).max().orElse(0L) + 1L;
            ObjectVersion version = new ObjectVersion(
                    versionId, sha256, content.length, Instant.now(), ObjectVersion.State.ACTIVE);
            meta.getVersions().add(version);
            saveMetadata(key, meta);
            log.info("put ok: key={}, version={}, sha256={}, size={}", key, versionId, sha256, content.length);
            return version;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 读取对象最新版本内容。
     */
    public byte[] get(String key) {
        ObjectMetadata meta = loadMetadataExisting(key);
        ObjectVersion latest = meta.getVersions().stream()
                .filter(v -> v.getState() == ObjectVersion.State.ACTIVE)
                .max(Comparator.comparingLong(ObjectVersion::getVersionId))
                .orElseThrow(() -> new ObjectNotFoundException(key));
        return readVersionContent(key, latest);
    }

    /**
     * 读取对象指定版本内容。
     */
    public byte[] getVersion(String key, long versionId) {
        ObjectMetadata meta = loadMetadataExisting(key);
        ObjectVersion version = findVersion(meta, versionId);
        return readVersionContent(key, version);
    }

    /**
     * 列出对象全部版本（含已清理版本），顺序稳定（按 versionId 升序）。
     */
    public List<ObjectVersion> listVersions(String key) {
        ObjectMetadata meta = loadMetadataExisting(key);
        return meta.getVersions().stream()
                .sorted(Comparator.comparingLong(ObjectVersion::getVersionId))
                .toList();
    }

    /**
     * 回滚到指定历史版本：以该版本内容产生一个新版本，不改写历史。
     */
    public ObjectVersion rollback(String key, long versionId) {
        byte[] content = getVersion(key, versionId);
        ObjectVersion version = put(key, content, null);
        log.info("rollback ok: key={}, fromVersion={}, newVersion={}, sha256={}",
                key, versionId, version.getVersionId(), version.getSha256());
        return version;
    }

    /**
     * 清理（purge）指定版本：版本记录保留但内容不再可读；不影响其它版本。
     * 若没有任何 ACTIVE 版本再引用同一 blob，则回收该 blob 存储。
     */
    public void purgeVersion(String key, long versionId) {
        ReentrantLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            ObjectMetadata meta = loadMetadataExisting(key);
            ObjectVersion version = findVersion(meta, versionId);
            if (version.getState() == ObjectVersion.State.PURGED) {
                return; // 幂等
            }
            version.setState(ObjectVersion.State.PURGED);
            saveMetadata(key, meta);
            String sha256 = version.getSha256();
            boolean stillReferenced = meta.getVersions().stream()
                    .anyMatch(v -> v.getState() == ObjectVersion.State.ACTIVE
                            && v.getSha256().equals(sha256));
            if (!stillReferenced) {
                Files.deleteIfExists(blobsDir.resolve(sha256));
            }
            log.info("purge ok: key={}, version={}, sha256={}, blobReclaimed={}",
                    key, versionId, sha256, !stillReferenced);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to reclaim blob for key=" + key, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回对象元数据。
     */
    public ObjectMetadata metadata(String key) {
        return loadMetadataExisting(key);
    }

    /**
     * 返回当前 blob 存储占用的总字节数（用于验证去重效果）。
     */
    public long blobStoreSize() {
        try (Stream<Path> files = Files.list(blobsDir)) {
            return files.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- 内部实现 ----------

    private ObjectVersion findVersion(ObjectMetadata meta, long versionId) {
        ObjectVersion version = meta.getVersions().stream()
                .filter(v -> v.getVersionId() == versionId)
                .findFirst()
                .orElseThrow(() -> new VersionNotFoundException(meta.getKey(), versionId));
        if (version.getState() == ObjectVersion.State.PURGED) {
            throw new VersionPurgedException(meta.getKey(), versionId);
        }
        return version;
    }

    private byte[] readVersionContent(String key, ObjectVersion version) {
        Path blob = blobsDir.resolve(version.getSha256());
        byte[] content;
        try {
            content = Files.readAllBytes(blob);
        } catch (IOException e) {
            throw new StorageException("blob missing for key=" + key
                    + ", version=" + version.getVersionId()
                    + ", sha256=" + version.getSha256(), e);
        }
        String actual = sha256Hex(content);
        if (!actual.equals(version.getSha256())) {
            throw new ChecksumMismatchException(version.getSha256(), actual);
        }
        return content;
    }

    private void writeBlobIfAbsent(String sha256, byte[] content) {
        Path blob = blobsDir.resolve(sha256);
        if (Files.exists(blob)) {
            return; // 去重：内容已存在
        }
        try {
            Path tmp = Files.createTempFile(blobsDir, "tmp-", ".part");
            try {
                Files.write(tmp, content);
                try {
                    Files.move(tmp, blob, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException moveFailure) {
                    // 并发下其它写入者可能已落盘相同内容
                    Files.deleteIfExists(tmp);
                    if (!Files.exists(blob)) {
                        throw moveFailure;
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write blob " + sha256, e);
        }
    }

    private ObjectMetadata loadMetadata(String key) {
        Path metaFile = metaFile(key);
        if (!Files.exists(metaFile)) {
            return new ObjectMetadata(key);
        }
        try {
            return objectMapper.readValue(metaFile.toFile(), ObjectMetadata.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read metadata for key=" + key, e);
        }
    }

    private ObjectMetadata loadMetadataExisting(String key) {
        Path metaFile = metaFile(key);
        if (!Files.exists(metaFile)) {
            throw new ObjectNotFoundException(key);
        }
        return loadMetadata(key);
    }

    private void saveMetadata(String key, ObjectMetadata meta) {
        try {
            Path dir = metaDir.resolve(encodeKey(key));
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "meta-", ".tmp");
            try {
                objectMapper.writeValue(tmp.toFile(), meta);
                Files.move(tmp, metaFile(key),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save metadata for key=" + key, e);
        }
    }

    private Path metaFile(String key) {
        return metaDir.resolve(encodeKey(key)).resolve("meta.json");
    }

    private static String encodeKey(String key) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

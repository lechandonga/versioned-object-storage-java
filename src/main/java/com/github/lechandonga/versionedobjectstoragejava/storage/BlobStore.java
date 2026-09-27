package com.github.lechandonga.versionedobjectstoragejava.storage;

import com.github.lechandonga.versionedobjectstoragejava.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * 内容寻址的去重存储：内容相同的对象只落盘一份，以 SHA-256 作为文件名。
 * 写入采用临时文件 + 原子移动，避免半写状态。
 */
@Component
public class BlobStore {

    private static final Logger log = LoggerFactory.getLogger(BlobStore.class);

    private final Path blobDir;

    public BlobStore(StorageProperties properties) {
        this.blobDir = properties.getRoot().resolve("blobs");
        try {
            Files.createDirectories(blobDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 计算内容的 SHA-256 十六进制串。 */
    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 写入内容并返回其 SHA-256；相同内容已存在时不重复写盘（去重）。
     * 写入失败时清理临时文件，不留下半写状态。
     */
    public String put(byte[] content) {
        String sha256 = sha256Hex(content);
        Path target = blobDir.resolve(sha256);
        if (Files.exists(target)) {
            log.info("blob dedup hit sha256={} size={}", sha256, content.length);
            return sha256;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(blobDir, "tmp-", ".part");
            Files.write(tmp, content);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException | java.nio.file.AtomicMoveNotSupportedException e) {
                // 并发写入同一内容：另一个线程已完成，直接复用
                if (!Files.exists(target)) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("blob stored sha256={} size={}", sha256, content.length);
            return sha256;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** 按校验值读取内容，读取时重新校验，损坏则抛 DataCorrupted。 */
    public byte[] get(String sha256) {
        Path target = blobDir.resolve(sha256);
        if (!Files.exists(target)) {
            throw new StoreException.DataCorrupted(sha256 + " (missing)");
        }
        try {
            byte[] content = Files.readAllBytes(target);
            String actual = sha256Hex(content);
            if (!actual.equals(sha256)) {
                log.error("blob corrupted sha256={} actual={}", sha256, actual);
                throw new StoreException.DataCorrupted(sha256);
            }
            return content;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 删除指定校验值的 blob（仅在无任何版本引用时调用）。 */
    public void delete(String sha256) {
        deleteQuietly(blobDir.resolve(sha256));
    }

    /** 统计 blob 目录占用的总字节数（用于验证去重效果）。 */
    public long totalBytes() {
        try (Stream<Path> files = Files.list(blobDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".part"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}

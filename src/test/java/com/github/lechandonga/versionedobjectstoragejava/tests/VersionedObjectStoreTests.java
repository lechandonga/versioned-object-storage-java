package com.github.lechandonga.versionedobjectstoragejava.tests;

import com.github.lechandonga.versionedobjectstoragejava.config.StorageProperties;
import com.github.lechandonga.versionedobjectstoragejava.storage.BlobStore;
import com.github.lechandonga.versionedobjectstoragejava.storage.StoreException;
import com.github.lechandonga.versionedobjectstoragejava.storage.VersionMetadata;
import com.github.lechandonga.versionedobjectstoragejava.storage.VersionedObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖重复上传（去重）、并发写入与数据损坏场景。
 */
class VersionedObjectStoreTests {

    private static final Logger log = LoggerFactory.getLogger(VersionedObjectStoreTests.class);

    @TempDir
    Path root;

    private BlobStore blobStore;
    private VersionedObjectStore store;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setRoot(root);
        blobStore = new BlobStore(props);
        store = new VersionedObjectStore(blobStore, props);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void duplicateUploadsShareSingleBlob() {
        byte[] content = bytes("identical-content");
        VersionMetadata v1 = store.put("obj-a", content, null);
        VersionMetadata v2 = store.put("obj-b", content, null);
        VersionMetadata v3 = store.put("obj-a", content, null);
        log.info("dedup check: v1={} v2={} v3={} sha256={}", v1.versionId(), v2.versionId(), v3.versionId(), v1.sha256());

        // 三个上传记录都保留，且校验值一致
        assertEquals(v1.sha256(), v2.sha256());
        assertEquals(v1.sha256(), v3.sha256());
        // 但磁盘上只占用一份空间
        assertEquals(content.length, blobStore.totalBytes());
        // 各自版本独立可读
        assertArrayEquals(content, store.getVersion("obj-a", 1));
        assertArrayEquals(content, store.getVersion("obj-a", 2));
        assertArrayEquals(content, store.getVersion("obj-b", 1));
    }

    @Test
    void purgeDoesNotAffectOtherVersionsSharingBlob() {
        byte[] content = bytes("shared-content");
        store.put("obj-a", content, null);
        store.put("obj-b", content, null);
        store.purgeVersion("obj-a", 1);

        // obj-a 的版本被清理：原因可区分（GONE 语义）
        StoreException.VersionPurged purged =
                assertThrows(StoreException.VersionPurged.class, () -> store.getVersion("obj-a", 1));
        log.info("purged reason: {}", purged.getMessage());
        // obj-b 引用同一 blob，仍可读取
        assertArrayEquals(content, store.getVersion("obj-b", 1));
        // blob 仍被引用，未被回收
        assertEquals(content.length, blobStore.totalBytes());
    }

    @Test
    void versionOrderIsStableAndRollbackCreatesNewVersion() {
        store.put("doc", bytes("v1-content"), null);
        store.put("doc", bytes("v2-content"), null);
        VersionMetadata v3 = store.put("doc", bytes("v3-content"), null);
        log.info("before rollback: latest versionId={} sha256={}", v3.versionId(), v3.sha256());

        List<VersionMetadata> versions = store.listVersions("doc");
        assertEquals(List.of(1L, 2L, 3L), versions.stream().map(VersionMetadata::versionId).toList());

        // 回滚到 v1：产生新版本 v4，历史不被改写
        VersionMetadata v4 = store.rollback("doc", 1);
        log.info("rollback: new versionId={} sha256={}", v4.versionId(), v4.sha256());
        assertEquals(4L, v4.versionId());
        assertArrayEquals(bytes("v1-content"), store.get("doc"));
        assertArrayEquals(bytes("v3-content"), store.getVersion("doc", 3));
        assertEquals(List.of(1L, 2L, 3L, 4L),
                store.listVersions("doc").stream().map(VersionMetadata::versionId).toList());
    }

    @Test
    void missingAndPurgedVersionsAreDistinguishable() {
        store.put("doc", bytes("x"), null);
        store.purgeVersion("doc", 1);

        assertThrows(StoreException.ObjectNotFound.class, () -> store.get("ghost"));
        assertThrows(StoreException.VersionNotFound.class, () -> store.getVersion("doc", 99));
        assertThrows(StoreException.VersionPurged.class, () -> store.getVersion("doc", 1));
        assertThrows(StoreException.VersionNotFound.class, () -> store.rollback("doc", 99));
        assertThrows(StoreException.VersionPurged.class, () -> store.rollback("doc", 1));
    }

    @Test
    void checksumMismatchRollsBackWriteAndKeepsPreviousVersion() {
        byte[] original = bytes("original");
        store.put("doc", original, null);

        byte[] tampered = bytes("tampered");
        String wrongExpected = BlobStore.sha256Hex(bytes("something-else"));
        StoreException.ChecksumMismatch mismatch = assertThrows(StoreException.ChecksumMismatch.class,
                () -> store.put("doc", tampered, wrongExpected));
        log.info("checksum mismatch: {}", mismatch.getMessage());

        // 本次写入被撤销：仍只有一个版本，内容不变
        assertEquals(1, store.listVersions("doc").size());
        assertArrayEquals(original, store.get("doc"));
        // 声明正确校验值时上传成功
        VersionMetadata v2 = store.put("doc", tampered, BlobStore.sha256Hex(tampered));
        log.info("accepted upload: versionId={} sha256={}", v2.versionId(), v2.sha256());
        assertEquals(2L, v2.versionId());
    }

    @Test
    void concurrentUploadsProduceDistinctVersionsWithoutOverwrite() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    VersionMetadata meta = store.put("hot-key", bytes("content-" + n), null);
                    log.info("concurrent upload: n={} versionId={} sha256={}", n, meta.versionId(), meta.sha256());
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());

        // 每次上传都产生独立版本：1..16 完整无缺、无相互覆盖
        List<VersionMetadata> versions = store.listVersions("hot-key");
        assertEquals(threads, versions.size());
        for (int i = 0; i < threads; i++) {
            assertEquals(i + 1L, versions.get(i).versionId());
            byte[] content = store.getVersion("hot-key", versions.get(i).versionId());
            assertTrue(new String(content, StandardCharsets.UTF_8).startsWith("content-"));
        }
    }

    @Test
    void corruptedBlobIsDetectedOnRead() throws Exception {
        byte[] content = bytes("integrity-matters");
        VersionMetadata meta = store.put("doc", content, null);
        log.info("stored: versionId={} sha256={}", meta.versionId(), meta.sha256());

        // 直接篡改磁盘上的 blob，模拟数据损坏
        Path blob = root.resolve("blobs").resolve(meta.sha256());
        byte[] raw = Files.readAllBytes(blob);
        raw[0] ^= 0xFF;
        Files.write(blob, raw);

        StoreException.DataCorrupted corrupted =
                assertThrows(StoreException.DataCorrupted.class, () -> store.get("doc"));
        log.info("corruption detected: {}", corrupted.getMessage());
    }

    @Test
    void purgedBlobIsReclaimedWhenUnreferenced() {
        byte[] content = bytes("single-owner");
        store.put("solo", content, null);
        assertEquals(content.length, blobStore.totalBytes());

        store.purgeVersion("solo", 1);
        // 无其它版本引用，blob 被回收，存储占用归零
        assertEquals(0, blobStore.totalBytes());
        assertThrows(StoreException.VersionPurged.class, () -> store.getVersion("solo", 1));
    }
}

package com.github.lechandonga.versionedobjectstoragejava.tests;

import com.github.lechandonga.versionedobjectstoragejava.storage.ObjectStorageService;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.ChecksumMismatchException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.ObjectNotFoundException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.VersionNotFoundException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.VersionPurgedException;
import com.github.lechandonga.versionedobjectstoragejava.storage.model.ObjectVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：重复上传去重、并发写入、数据损坏、回滚、失败原因可区分。
 */
class ObjectStorageServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageServiceTest.class);

    @TempDir
    Path tempDir;

    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        service = new ObjectStorageService(tempDir.toString());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void duplicateUploadsShareSingleBlobButKeepOwnVersionRecords() {
        byte[] content = "identical-content".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(content);

        ObjectVersion v1 = service.put("obj-a", content, null);
        ObjectVersion v2 = service.put("obj-b", content, null);
        ObjectVersion v3 = service.put("obj-a", content, null);
        log.info("dedup test: v1={} v2={} v3={} sha256={}",
                v1.getVersionId(), v2.getVersionId(), v3.getVersionId(), checksum);

        // 三次上传同一内容，blob 存储只占一份
        assertEquals(content.length, service.blobStoreSize());
        // 各自上传记录与版本关系保留
        assertEquals(1L, v1.getVersionId());
        assertEquals(1L, v2.getVersionId());
        assertEquals(2L, v3.getVersionId());
        assertEquals(2, service.listVersions("obj-a").size());
        assertEquals(1, service.listVersions("obj-b").size());
        // 内容均可读
        assertArrayEquals(content, service.get("obj-a"));
        assertArrayEquals(content, service.get("obj-b"));

        // 覆盖（purge）obj-a 的版本不得影响 obj-b 的可读取性
        service.purgeVersion("obj-a", 1L);
        assertArrayEquals(content, service.get("obj-b"));
        assertArrayEquals(content, service.getVersion("obj-a", 2L));
        // 两个 ACTIVE 版本仍引用该 blob，存储不回收
        assertEquals(content.length, service.blobStoreSize());
    }

    @Test
    void concurrentUploadsNeverLoseVersions() throws Exception {
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<ObjectVersion>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final int n = i;
            tasks.add(() -> {
                start.await();
                byte[] content = ("payload-" + n).getBytes(StandardCharsets.UTF_8);
                ObjectVersion v = service.put("hot-key", content, null);
                log.info("concurrent put: writer={} version={} sha256={}",
                        n, v.getVersionId(), sha256(content));
                return v;
            });
        }
        List<Future<ObjectVersion>> futures = tasks.stream().map(pool::submit).toList();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<Long> versionIds = new ArrayList<>();
        for (Future<ObjectVersion> f : futures) {
            versionIds.add(f.get().getVersionId());
        }
        // 版本号互不重复，且恰好覆盖 1..writers：无相互覆盖、无半写状态
        assertEquals(writers, versionIds.stream().distinct().count());
        List<ObjectVersion> versions = service.listVersions("hot-key");
        assertEquals(writers, versions.size());
        for (long id = 1; id <= writers; id++) {
            long vid = id;
            assertTrue(versions.stream().anyMatch(v -> v.getVersionId() == vid),
                    "missing version " + vid);
        }
        // 每个版本内容均可读且校验通过
        for (ObjectVersion v : versions) {
            byte[] read = service.getVersion("hot-key", v.getVersionId());
            assertEquals(v.getSha256(), sha256(read));
        }
        log.info("concurrent test done: totalVersions={} blobStoreSize={}",
                versions.size(), service.blobStoreSize());
    }

    @Test
    void corruptedBlobIsDetectedOnRead() throws Exception {
        byte[] content = "integrity-matters".getBytes(StandardCharsets.UTF_8);
        ObjectVersion v = service.put("obj", content, null);
        log.info("corruption test: version={} sha256={}", v.getVersionId(), v.getSha256());

        // 篡改 blob 文件模拟数据损坏
        Path blob = tempDir.resolve("blobs").resolve(v.getSha256());
        Files.write(blob, "tampered".getBytes(StandardCharsets.UTF_8));

        ChecksumMismatchException ex = assertThrows(ChecksumMismatchException.class,
                () -> service.get("obj"));
        log.info("corruption detected: version={} expectedSha256={} message={}",
                v.getVersionId(), v.getSha256(), ex.getMessage());
    }

    @Test
    void checksumMismatchOnUploadRejectsWriteAndKeepsOldVersion() {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        ObjectVersion v1 = service.put("obj", original, null);

        byte[] bad = "bad-content".getBytes(StandardCharsets.UTF_8);
        String wrongChecksum = sha256("something-else".getBytes(StandardCharsets.UTF_8));
        assertThrows(ChecksumMismatchException.class,
                () -> service.put("obj", bad, wrongChecksum));

        // 本次写入被撤销：没有产生新版本，原有版本仍可读
        assertEquals(1, service.listVersions("obj").size());
        assertArrayEquals(original, service.get("obj"));
        log.info("rejected upload: keptVersion={} sha256={}", v1.getVersionId(), v1.getSha256());
    }

    @Test
    void rollbackCreatesNewVersionWithoutRewritingHistory() {
        ObjectVersion v1 = service.put("obj", "v1-data".getBytes(StandardCharsets.UTF_8), null);
        ObjectVersion v2 = service.put("obj", "v2-data".getBytes(StandardCharsets.UTF_8), null);
        log.info("rollback test: v1 sha256={}, v2 sha256={}", v1.getSha256(), v2.getSha256());

        ObjectVersion v3 = service.rollback("obj", 1L);
        log.info("rollback produced: newVersion={} sha256={}", v3.getVersionId(), v3.getSha256());

        // 回滚产生新版本，内容等于历史版本
        assertEquals(3L, v3.getVersionId());
        assertEquals(v1.getSha256(), v3.getSha256());
        assertArrayEquals("v1-data".getBytes(StandardCharsets.UTF_8), service.get("obj"));
        // 历史不被改写
        assertArrayEquals("v1-data".getBytes(StandardCharsets.UTF_8), service.getVersion("obj", 1L));
        assertArrayEquals("v2-data".getBytes(StandardCharsets.UTF_8), service.getVersion("obj", 2L));
        // 版本顺序稳定可复现
        assertEquals(List.of(1L, 2L, 3L),
                service.listVersions("obj").stream().map(ObjectVersion::getVersionId).toList());
    }

    @Test
    void failureReasonsAreDistinguishable() {
        ObjectVersion v1 = service.put("obj", "data".getBytes(StandardCharsets.UTF_8), null);
        service.purgeVersion("obj", v1.getVersionId());
        log.info("purge test: purgedVersion={} sha256={}", v1.getVersionId(), v1.getSha256());

        // 已清理的版本 -> VersionPurgedException
        assertThrows(VersionPurgedException.class, () -> service.getVersion("obj", 1L));
        // 从未存在的版本 -> VersionNotFoundException
        assertThrows(VersionNotFoundException.class, () -> service.getVersion("obj", 99L));
        // 不存在的对象 -> ObjectNotFoundException
        assertThrows(ObjectNotFoundException.class, () -> service.get("no-such-key"));
        // 回滚到已清理版本同样被拒绝
        assertThrows(VersionPurgedException.class, () -> service.rollback("obj", 1L));
        // purge 后 blob 无 ACTIVE 引用，存储被回收
        assertEquals(0, service.blobStoreSize());
    }
}

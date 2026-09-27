package com.github.lechandonga.versionedobjectstoragejava.web;

import com.github.lechandonga.versionedobjectstoragejava.storage.StoreException;
import com.github.lechandonga.versionedobjectstoragejava.storage.VersionMetadata;
import com.github.lechandonga.versionedobjectstoragejava.storage.VersionedObjectStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对象上传、读取与版本管理的 REST 接口。
 */
@RestController
@RequestMapping("/objects")
public class ObjectController {

    private final VersionedObjectStore store;

    public ObjectController(VersionedObjectStore store) {
        this.store = store;
    }

    /** 上传对象内容，产生新版本；可通过 X-Content-Sha256 头声明校验值。 */
    @PostMapping("/{key}")
    public VersionMetadata upload(@PathVariable String key,
                                  @RequestBody byte[] content,
                                  @RequestHeader(value = "X-Content-Sha256", required = false) String expectedSha256) {
        return store.put(key, content, expectedSha256);
    }

    /** 读取最新版本。 */
    @GetMapping("/{key}")
    public ResponseEntity<byte[]> download(@PathVariable String key) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(store.get(key));
    }

    /** 读取指定版本。 */
    @GetMapping("/{key}/versions/{versionId}")
    public ResponseEntity<byte[]> downloadVersion(@PathVariable String key, @PathVariable long versionId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(store.getVersion(key, versionId));
    }

    /** 列出全部版本（versionId 升序，稳定可复现）。 */
    @GetMapping("/{key}/versions")
    public List<VersionMetadata> versions(@PathVariable String key) {
        return store.listVersions(key);
    }

    /** 回滚到指定历史版本，产生一个新版本。 */
    @PostMapping("/{key}/rollback/{versionId}")
    public VersionMetadata rollback(@PathVariable String key, @PathVariable long versionId) {
        return store.rollback(key, versionId);
    }

    /** 清理指定版本。 */
    @DeleteMapping("/{key}/versions/{versionId}")
    public ResponseEntity<Void> purge(@PathVariable String key, @PathVariable long versionId) {
        store.purgeVersion(key, versionId);
        return ResponseEntity.noContent().build();
    }

    /** 将失败原因映射为可区分的 HTTP 状态码。 */
    @ExceptionHandler(StoreException.class)
    public ResponseEntity<Map<String, String>> handle(StoreException e) {
        HttpStatus status = switch (e) {
            case StoreException.ObjectNotFound ignored -> HttpStatus.NOT_FOUND;
            case StoreException.VersionNotFound ignored -> HttpStatus.NOT_FOUND;
            case StoreException.VersionPurged ignored -> HttpStatus.GONE;
            case StoreException.ChecksumMismatch ignored -> HttpStatus.BAD_REQUEST;
            case StoreException.DataCorrupted ignored -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(Map.of(
                "reason", e.getClass().getSimpleName(),
                "message", e.getMessage()));
    }
}

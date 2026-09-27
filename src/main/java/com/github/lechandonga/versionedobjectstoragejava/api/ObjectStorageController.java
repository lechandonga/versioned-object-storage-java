package com.github.lechandonga.versionedobjectstoragejava.api;

import com.github.lechandonga.versionedobjectstoragejava.storage.ObjectStorageService;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.ChecksumMismatchException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.ObjectNotFoundException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.VersionNotFoundException;
import com.github.lechandonga.versionedobjectstoragejava.storage.exception.VersionPurgedException;
import com.github.lechandonga.versionedobjectstoragejava.storage.model.ObjectVersion;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对象存储对外 REST 接口。
 */
@RestController
@RequestMapping("/objects")
public class ObjectStorageController {

    private final ObjectStorageService storageService;

    public ObjectStorageController(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    /**
     * 上传对象，产生新版本。可选请求参数 sha256 用于调用方声明内容校验值。
     */
    @PostMapping("/{key}")
    public ObjectVersion put(@PathVariable String key,
                             @RequestBody byte[] content,
                             @RequestParam(required = false) String sha256) {
        return storageService.put(key, content, sha256);
    }

    /**
     * 读取最新版本。
     */
    @GetMapping("/{key}")
    public ResponseEntity<byte[]> get(@PathVariable String key) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(storageService.get(key));
    }

    /**
     * 读取指定版本。
     */
    @GetMapping("/{key}/versions/{versionId}")
    public ResponseEntity<byte[]> getVersion(@PathVariable String key,
                                             @PathVariable long versionId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(storageService.getVersion(key, versionId));
    }

    /**
     * 列出全部版本（含已清理版本）。
     */
    @GetMapping("/{key}/versions")
    public List<ObjectVersion> listVersions(@PathVariable String key) {
        return storageService.listVersions(key);
    }

    /**
     * 回滚到指定历史版本（产生新版本）。
     */
    @PostMapping("/{key}/rollback/{versionId}")
    public ObjectVersion rollback(@PathVariable String key, @PathVariable long versionId) {
        return storageService.rollback(key, versionId);
    }

    /**
     * 清理指定版本。
     */
    @PostMapping("/{key}/purge/{versionId}")
    public Map<String, Object> purge(@PathVariable String key, @PathVariable long versionId) {
        storageService.purgeVersion(key, versionId);
        return Map.of("key", key, "purged", versionId);
    }

    @ExceptionHandler(ObjectNotFoundException.class)
    public ResponseEntity<Map<String, String>> objectNotFound(ObjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "OBJECT_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(VersionNotFoundException.class)
    public ResponseEntity<Map<String, String>> versionNotFound(VersionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "VERSION_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(VersionPurgedException.class)
    public ResponseEntity<Map<String, String>> versionPurged(VersionPurgedException e) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", "VERSION_PURGED", "message", e.getMessage()));
    }

    @ExceptionHandler(ChecksumMismatchException.class)
    public ResponseEntity<Map<String, String>> checksumMismatch(ChecksumMismatchException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CHECKSUM_MISMATCH", "message", e.getMessage()));
    }
}

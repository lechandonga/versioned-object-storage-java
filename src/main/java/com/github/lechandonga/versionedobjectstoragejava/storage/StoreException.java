package com.github.lechandonga.versionedobjectstoragejava.storage;

/**
 * 对象存储失败原因基类，子类用于区分不同的失败场景。
 */
public class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    /** 对象不存在。 */
    public static class ObjectNotFound extends StoreException {
        public ObjectNotFound(String key) {
            super("object not found: " + key);
        }
    }

    /** 版本不存在。 */
    public static class VersionNotFound extends StoreException {
        public VersionNotFound(String key, long versionId) {
            super("version not found: " + key + "@" + versionId);
        }
    }

    /** 版本已被清理，内容不可再读取。 */
    public static class VersionPurged extends StoreException {
        public VersionPurged(String key, long versionId) {
            super("version purged: " + key + "@" + versionId);
        }
    }

    /** 上传内容校验不一致，本次写入被撤销。 */
    public static class ChecksumMismatch extends StoreException {
        public ChecksumMismatch(String expected, String actual) {
            super("checksum mismatch: expected=" + expected + " actual=" + actual);
        }
    }

    /** 底层数据损坏（读取时校验值与元数据不符）。 */
    public static class DataCorrupted extends StoreException {
        public DataCorrupted(String sha256) {
            super("data corrupted for blob: " + sha256);
        }
    }
}

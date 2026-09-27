package com.github.lechandonga.versionedobjectstoragejava.storage.exception;

/**
 * 版本号从未存在过。
 */
public class VersionNotFoundException extends StorageException {

    public VersionNotFoundException(String key, long versionId) {
        super("version not found: key=" + key + ", version=" + versionId);
    }
}

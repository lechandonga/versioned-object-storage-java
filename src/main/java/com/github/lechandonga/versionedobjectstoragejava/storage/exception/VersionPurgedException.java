package com.github.lechandonga.versionedobjectstoragejava.storage.exception;

/**
 * 版本存在但已被清理（purge），与“从未存在”可区分。
 */
public class VersionPurgedException extends StorageException {

    public VersionPurgedException(String key, long versionId) {
        super("version purged: key=" + key + ", version=" + versionId);
    }
}

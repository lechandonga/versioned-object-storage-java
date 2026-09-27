package com.github.lechandonga.versionedobjectstoragejava.storage.exception;

/**
 * 对象（key）不存在。
 */
public class ObjectNotFoundException extends StorageException {

    public ObjectNotFoundException(String key) {
        super("object not found: " + key);
    }
}

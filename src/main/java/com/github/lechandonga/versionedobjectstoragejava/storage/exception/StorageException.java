package com.github.lechandonga.versionedobjectstoragejava.storage.exception;

/**
 * 存储层异常基类。
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.github.lechandonga.versionedobjectstoragejava.storage.exception;

/**
 * 内容校验不一致（上传声明的校验值不符，或读取时发现数据损坏）。
 */
public class ChecksumMismatchException extends StorageException {

    public ChecksumMismatchException(String expected, String actual) {
        super("checksum mismatch: expected=" + expected + ", actual=" + actual);
    }
}

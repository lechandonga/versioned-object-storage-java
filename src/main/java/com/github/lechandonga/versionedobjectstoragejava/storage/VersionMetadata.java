package com.github.lechandonga.versionedobjectstoragejava.storage;

import java.time.Instant;

/**
 * 单个版本的元数据。
 *
 * @param versionId  版本标识（对象内单调递增，从 1 开始）
 * @param sha256     内容的 SHA-256 校验值（同时是去重存储的键）
 * @param size       内容字节数
 * @param createdAt  版本创建时间
 * @param purged     该版本是否已被清理（清理后不可读取，但记录保留以保证可区分）
 */
public record VersionMetadata(long versionId, String sha256, long size, Instant createdAt, boolean purged) {
}

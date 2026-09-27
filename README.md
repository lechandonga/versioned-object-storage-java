# versioned-object-storage-java

基于本地文件系统的版本化对象存储服务（Spring Boot），提供对象上传、读取与版本管理能力，对重复上传、并发写入与数据损坏给出可预期、可追溯的处理结果。

## 运行与本地验证

```bash
# 运行全部测试（去重 / 并发 / 损坏 / 回滚 / 失败原因区分）
mvn test

# 启动服务（默认随机端口，可用 --server.port=8080 指定）
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --storage.root=./data"

# 上传（可选 X-Content-Sha256 头声明校验值）
curl -X POST --data-binary 'hello' http://localhost:8080/objects/doc
# 读取最新版本 / 指定版本
curl http://localhost:8080/objects/doc
curl http://localhost:8080/objects/doc/versions/1
# 版本列表 / 回滚 / 清理版本
curl http://localhost:8080/objects/doc/versions
curl -X POST http://localhost:8080/objects/doc/rollback/1
curl -X DELETE http://localhost:8080/objects/doc/versions/1
```

## 一致性模型

- 每个对象对应一份清单文件 `objects/<key>/manifest.json`，记录全部版本元数据（versionId、sha256、size、createdAt、purged）。
- 版本号在对象内从 1 开始**单调递增**，版本列表按 versionId 升序返回，顺序稳定可复现。
- 清单更新采用**临时文件 + 原子移动**（`ATOMIC_MOVE`），任何时刻清单要么完整要么不存在，不会出现半写状态。
- 上传时可声明 `X-Content-Sha256`；若与实际内容校验值不一致，本次写入**整体撤销**（不落盘、不改清单），原有可用版本保持不变。
- 读取时按元数据中的 sha256 对 blob **重新校验**，内容被篡改或丢失时抛出 `DataCorrupted`，不会静默返回坏数据。

## 去重与版本管理规则

- 内容寻址存储：blob 以内容的 SHA-256 命名存放于 `blobs/` 目录，**相同内容只占用一份磁盘空间**（上传时命中已有 blob 直接复用）。
- 每次上传（包括重复内容）都会产生**独立的版本记录**，上传历史与版本关系完整保留。
- **回滚不改写历史**：`rollback` 读取目标历史版本内容并产生一个新版本，历史版本保持可读。
- **版本清理**（purge）只做标记并回收无引用 blob：
  - 被清理的版本读取时返回 `VersionPurged`（HTTP 410），与版本不存在 `VersionNotFound`（HTTP 404）可区分；
  - 若同一 blob 仍被其它版本（含其它对象）引用，则不会回收，删除/覆盖某个版本不影响其它版本的可读性；
  - 仅当 blob 不再被任何未清理版本引用时才真正删除文件。

## 并发边界

- 同一对象的写操作（上传 / 回滚 / 清理）按 key 加锁**串行化**：并发上传各自产生独立、连续递增的版本号，不会相互覆盖。
- 不同对象之间互不阻塞，可并行写入。
- 并发写入相同内容的 blob 时，先完成者落盘，其余线程复用结果（原子移动 + 已存在检测），不产生重复文件或半写文件。
- 本服务面向单实例本地部署；多进程共享同一存储目录不在一致性保证范围内。

## 失败原因一览

| 场景 | 异常 | HTTP 状态 |
| --- | --- | --- |
| 对象不存在 | `ObjectNotFound` | 404 |
| 版本不存在 | `VersionNotFound` | 404 |
| 版本已被清理 | `VersionPurged` | 410 |
| 上传校验值不一致 | `ChecksumMismatch` | 400 |
| 底层数据损坏 | `DataCorrupted` | 500 |

## 测试覆盖

`src/test/.../VersionedObjectStoreTests.java`：

- `duplicateUploadsShareSingleBlob`：重复上传后 `totalBytes()` 不重复增长，验证去重；
- `purgeDoesNotAffectOtherVersionsSharingBlob` / `purgedBlobIsReclaimedWhenUnreferenced`：共享 blob 的版本互不影响，无引用时回收；
- `versionOrderIsStableAndRollbackCreatesNewVersion`：版本顺序稳定、回滚产生新版本；
- `missingAndPurgedVersionsAreDistinguishable`：失败原因可区分；
- `checksumMismatchRollsBackWriteAndKeepsPreviousVersion`：校验不一致撤销写入；
- `concurrentUploadsProduceDistinctVersionsWithoutOverwrite`：16 线程并发上传，版本号 1..16 完整无缺；
- `corruptedBlobIsDetectedOnRead`：篡改磁盘 blob 后读取检出损坏。

`ObjectControllerTests` 验证 REST 层失败原因与 HTTP 状态码映射。单测日志中会打印每次上传的 sha256 校验值与 versionId，便于追溯。

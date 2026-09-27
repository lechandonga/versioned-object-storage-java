# versioned-object-storage-java

基于本地文件系统的版本化对象存储服务（Spring Boot），提供对象上传、读取与版本管理能力，对重复上传、并发写入与数据损坏给出可预期、可追溯的处理结果。

## 磁盘布局

```
{storage.root}/                       默认 ./data，可用 --storage.root= 覆盖
├── blobs/{sha256}                    内容寻址的去重 blob 存储
└── meta/{base64url(key)}/meta.json   每个对象的版本元数据
```

## 一致性模型

- **写入串行化**：同一 key 的 `put` / `purge` 通过 per-key 锁串行执行，版本号严格递增（从 1 开始），不会出现相互覆盖。
- **原子落盘**：blob 与元数据均通过“临时文件 + 原子 move”写入，任何时刻读到的要么是旧版本、要么是新版本，不存在半写状态。
- **先 blob 后元数据**：`put` 先写 blob 再提交元数据。元数据提交失败时新版本不可见，原有可用版本保持不变；blob 可能成为无害孤儿（不影响正确性）。
- **读时校验**：每次读取重新计算内容的 SHA-256 并与版本记录比对，发现数据损坏即抛出 `ChecksumMismatchException`，不会返回坏数据。
- **上传校验**：`put` 可携带调用方声明的 `sha256`，不一致时拒绝写入且不落盘，原有版本保留。

## 去重与版本管理规则

- **去重存储**：blob 以内容 SHA-256 命名，内容相同的对象（无论 key 是否相同）只占用一份存储空间；重复上传时 blob 写入被跳过。
- **独立版本记录**：每次上传（包括重复内容）都产生独立的版本记录（versionId、sha256、size、createdAt、state），版本关系完整保留。
- **版本顺序稳定**：版本列表按 versionId 升序返回，顺序可复现。
- **回滚不改写历史**：`rollback(key, versionId)` 读取历史版本内容并作为**新版本**提交（因去重不增加存储占用），历史版本保持原样。
- **清理（purge）**：`purgeVersion` 将版本标记为 `PURGED`，记录保留但内容不再可读；当没有任何 ACTIVE 版本再引用同一 blob 时才回收该 blob，因此删除/覆盖某个版本不影响其它版本的可读取性。
- **失败原因可区分**：
  | 场景 | 异常 | HTTP 状态 |
  |---|---|---|
  | 对象不存在 | `ObjectNotFoundException` | 404 `OBJECT_NOT_FOUND` |
  | 版本从未存在 | `VersionNotFoundException` | 404 `VERSION_NOT_FOUND` |
  | 版本已被清理 | `VersionPurgedException` | 410 `VERSION_PURGED` |
  | 校验不一致（上传声明不符 / 读时发现损坏） | `ChecksumMismatchException` | 409 `CHECKSUM_MISMATCH` |

## 并发边界

- 同一 key：写操作串行化，并发上传各自获得唯一递增版本号，全部可读。
- 不同 key：完全并行，互不阻塞。
- 不同 key 并发上传相同内容：blob 原子 move 保证只落盘一份，后写者检测到已存在即跳过。
- 锁与原子 move 均在单进程内生效；本服务不保证跨进程/多实例并发访问同一存储目录的一致性。

## REST 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/objects/{key}?sha256=<hex>` | 上传（body 为原始字节），产生新版本 |
| GET | `/objects/{key}` | 读取最新版本 |
| GET | `/objects/{key}/versions` | 列出全部版本（含已清理） |
| GET | `/objects/{key}/versions/{versionId}` | 读取指定版本 |
| POST | `/objects/{key}/rollback/{versionId}` | 回滚到指定版本（产生新版本） |
| POST | `/objects/{key}/purge/{versionId}` | 清理指定版本 |

## 本地验证

```bash
# 运行全部单元测试（覆盖重复上传去重、并发写入、数据损坏、回滚、失败原因区分；
# 日志中打印校验值 sha256 与版本标识 versionId）
mvn test

# 启动服务并手动验证
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --storage.root=./data"

curl -X POST --data-binary 'hello' http://localhost:8080/objects/demo
curl -X POST --data-binary 'hello v2' http://localhost:8080/objects/demo
curl http://localhost:8080/objects/demo/versions
curl -X POST http://localhost:8080/objects/demo/rollback/1
curl http://localhost:8080/objects/demo          # 输出 hello
```

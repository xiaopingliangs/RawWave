# 0.12.41-step 封存提速、元数据上限、同步租约复用 r01

日期：2026-11-15
版本：**115 / `0.12.41-step`**
Run ID：`seal-sync-20261115-r01`
状态：DEV `PASS`（517 tests / 1 skipped / 0 失败）；双端 release + lintRelease PASS；同证书；真机 `PASS`（封存耗时、归档接收、端到端同步）。

## 问题

### 1. 两小时采集结束后卡在“正在保存 / 正在结束采集”20–30 分钟

2026-11-15 的长会话 `e98e02b6-2b30-42bc-95e7-6391aa1a997c`（2 小时“乱跳”，3,588,332 行，原始 1,857,753,757 B）：

- 手表日志 `CAPTURE_SESSION_END` **13:41:43.923Z** → `ACTIVITY_WATCH_SYNC_STARTED` **14:13:00.104Z**，中间 **31 分 16 秒**。
- 原因不是数据没落盘，而是 `ActivityStore.seal()` 在收尾时把已经持久化的全部约 7.6 万个 block 文件**重新读一遍、重新算一遍 SHA-256**，再写 `seal.json`。
- 数据在采集期间早已按 block 落盘；收尾阶段做全量复算，等于把“采集 2 小时”变成“采集 2 小时 + 读 1.86 GB”。

### 2. 收尾后同步失败（`ACTIVITY_METADATA_LIMIT` + 前台服务无法重启）

- 该会话的 `seal.json` 为 **8,389,157 B**，旧上限 8 MiB（8,388,608 B）只差 **549 B**，被拒：
  `ACTIVITY_METADATA_LIMIT:sessions/e98e02b6-.../seal.json:8389157`
- 自动重试又撞上 `ForegroundServiceStartNotAllowedException`：首次尝试失败时把 `SyncGuardService` 的 lease 关掉了，重试失去了前台服务后台启动豁免。
- 结果：数据完好、会话一直在“待同步 1 次”，但手机收不到。

### 3. 两晚没有睡眠数据

- 睡眠不是原波的功能；手表上注册 `SleepDetector` / `SleepAnalyzer` 的是 `com.samsung.android.wear.shealth`。
- 三星健康 `SleepActivity` 这两晚记录 “Number of records: 0”，睡眠卡片停在“请戴着您的 Galaxy Watch 睡觉。”
- 同一时段 `sensors.sec` 的 PPG HAL 崩溃：`Ppg25HzSensor::parsePPGMultiData(...)` SIGSEGV，11-15 21:41:51 崩溃并在 21:41:51.949 重启；11-12 / 11-13 / 11-14 / 11-15 都有同类崩溃。
- 结论：**不是原波吞了睡眠数据**，是设备侧睡眠管线没有产出；PPG HAL 反复崩溃是高度可疑的诱因，需要重启手表后复测确认。

## 修复

### 1. 封存不再全量复算

- `ActivityStore.seal()` 改为使用**写入时记录的清单**：block 落盘时记下字节数与 SHA-256，封存时只校验“文件存在 + 长度一致 + 清单条数一致”，不再读回内容。
- `seal.json` 增加 `fileIntegrity: "SEALED_APPEND_VERIFIED"`，明确这是追加时校验语义。
- 全量逐字节校验保留在真正需要的地方：归档构建（`verifySealedSource()`）、传输、手机端 `verify()` / `commit`。

### 2. block 写入批量下调

- 采集侧 flush 改为 **5 s / 1000 行**（`BLOCK_FLUSH_INTERVAL_MS` / `BLOCK_FLUSH_MAX_ROWS`），结束前强制 drain。
- 同样的数据量产生的 block 文件数显著减少，`seal.json` 条目数随之下降，从源头避免再次贴到上限。

### 3. 元数据上限分层

- `SEAL_METADATA_LIMIT` = **32 MiB**，`BUNDLE_METADATA_LIMIT` = 16 MiB，`PLAN_METADATA_LIMIT` = 8 MiB。
- 原始载荷上限仍是 2 GiB（`MAX_UNCOMPRESSED_BYTES`），不放松数据量限制，只放松“派生元数据”这一层。
- 新增回归测试 `ActivityArchiveTest.sealManifestOverOldEightMiBLimitIsAcceptedAsMetadata`：9 MiB 的 `seal.json` 必须被当作元数据接受。

### 4. 同步租约跨重试复用

- `WatchActivitySyncSession.openLease()` 在已有活动 lease 时复用它并只延长计时器，记录 `ACTIVITY_WATCH_SYNC_LEASE_REUSED`。
- `startRetry()` 不再关闭 lease，保留前台服务后台启动豁免；失败文案改为可读说明，而不是抛系统异常名。

### 5. 归档构建少读一遍

- `preparePending()` 复用 `create()` 返回的 `Verified`，不再把刚写出的 ZIP 重新解压校验一遍（原有 `verify()` 只是重复证据）。
- 归档仍需对源文件做一次逐文件哈希，这部分是构建期的真实校验，未削减。

## 测试

| 套件 | 结果 |
|---|---:|
| `:shared:test` | 397 通过 |
| `:wear:testDebugUnitTest` | 91 通过，1 skipped |
| `:mobile:testDebugUnitTest` | 29 通过 |
| **合计** | **517 通过，0 失败 / 0 错误，1 skipped** |

- `:wear:assembleRelease`、`:mobile:assembleRelease`、`:wear:lintRelease`、`:mobile:lintRelease` 全部 PASS。
- 双端 APK 使用同一本地测试证书。

## 真机验证

- 手表：SM-L350，`0.12.41-step` / 115，无线 ADB `192.168.10.7:45023`。
- 手机：SM-S9210，`0.12.41-step` / 115，USB `RFCX10F9QVD`。

### 封存耗时

新会话 `6f25f5ed-29bc-415c-bb72-5567fa690717`（短“乱跳”，52,556 行，434 blocks）：

- `CAPTURE_SESSION_END` **14:53:59.787698Z** → `ACTIVITY_WATCH_SYNC_STARTED` **14:54:00.646322Z** = **0.86 s**。
- 同一路径此前需要 **31 分 16 秒**，量级从“分钟”降到“亚秒”。

### 端到端同步

- 归档 **1,983,261 B** / 434 个原始文件 / 26,902,040 B 原始数据，传输约 **12.3 s**。
- 手表 `ACTIVITY_WATCH_SYNC_FINISHED success=true` **14:55:25.884Z**；
- 手机 `ACTIVITY_FILE_COMMITTED sessions:1 rawFiles:434 rawBytes:26902040` **14:55:25.459Z**；
- 全程 **没有** `ACTIVITY_METADATA_LIMIT`，重试路径没有 FGS 异常。
- 手机界面：“已收到 **158** 次活动 / 最近：乱跳 · 2026-11-15 22:52 / 原始包约 953.8 MB”。

## 产物（`D:\RawWave-APK\`）

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `Watch_RawWave_0.12.41-step_seal-sync-20261115-r01.apk` | 7,142,853 | `1510DE12D3F2DB536A7566FD6BF990697015338810B93F8459D69F6FCC985281` |
| `Phone_RawWave_0.12.41-step_seal-sync-20261115-r01.apk` | 8,140,292 | `642DBF230346B0CB8A9E81235D742AAE06BD2C0E542D83CF7192C078A2A74804` |
| `Both_RawWave_0.12.41-step_seal-sync-20261115-r01.zip` | 5,493,687 | `4CB1B12257954B7B57EB76F5EC5040BC3E04CD0816062DA041F245337426DF15` |

清单：`build-seal-sync-20261115-r01.json`；校验：`SHA256SUMS-seal-sync-20261115-r01.txt`。

## 已知限制 / 风险

- **旧会话 `e98e02b6…` 仍在“待同步 1 次”**：1.86 GB / 3.59M 行 / 约 7.6 万个 block，`seal.json` 8,389,157 B。新上限已能接收它，但每次尝试都要先做约 10 分钟的归档重建，传输还有单次 5 分钟上限；今晚没有完成。数据在手表上完好，可以在后续几次前台会话里让它自然补完，或直接放弃这一条测试数据。
- 大于 8 MiB 的 `seal.json` 路径目前只有单元测试覆盖（9 MiB 用例），还没有用真实的 8 MiB 会话在设备上跑通一次完整同步。
- 睡眠缺失属于三星健康 / 设备侧，本轮没有改动；建议睡前重启手表再观察一夜。
- `sensors.sec` 的 PPG HAL 崩溃是供应商问题，原波只能记录并继续采集，不能修复。
- 验证过程在手表留下测试短会话（`6f25f5ed…` 等），不属于真实活动，可按需删除。

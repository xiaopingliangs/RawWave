# 0.12.46-step 息屏同步：手机节点发现等待 r06

日期：2026-11-17
版本：**120 / `0.12.46-step`**
Run ID：`sync-discovery-20261117-r06`
状态：DEV `PASS`（522 tests / 1 skipped / 0 失败）；双端 release + lintRelease PASS；真机验证通过（手机 App 未运行、屏幕 Doze 状态下成功同步）。

## 问题

2026-11-17 09:10，手表自动同步失败：

```
停在：ACTIVITY_DISCOVERY_STARTED
原因：CONNECTED_NODE_COUNT_0
```

`ActivityTransferWindow` 只查询一次 `transport.nodes()`，拿到 0 个节点就直接失败；重试策略是 3 次、每次间隔 3 秒。手机在充电、屏幕黑、蓝牙处于低功耗窗口时，节点列表可能短暂为空；8 分钟后手机又能收到手表消息（09:18），说明只是瞬时不可见。

## 修复

- `ActivityTransferWindow.send()`：节点发现改为**最多等待 45 秒**，每 2 秒轮询一次；找到节点后继续 HELLO/建链。
- `ActivityTransferBudget`：
  - `DISCOVERY_MS = 45_000`
  - `HANDSHAKE_MS = 60_000`（HELLO + 建链）
  - `INITIAL_MS = 150_000`（覆盖发现+握手+小档案发送/提交）
- `ActivityRetryPolicy.RETRY_DELAY_MS = 8_000`（给手机脱离 Doze、重新注册节点留时间）。
- 失败仍保留数据，不丢；5 分钟单次上限与断点续传不变。

## 测试

| 套件 | 结果 |
|---|---:|
| `:shared:test` | 397 通过 |
| `:wear:testDebugUnitTest` | 96 通过，1 skipped |
| `:mobile:testDebugUnitTest` | 29 通过 |
| **合计** | **522 通过，0 失败** |

## 真机验证（SM-L350 + SM-S9210，120）

场景：手机 App 未启动（进程不存在）、手机屏幕 Doze、USB 充电；手表在充电座上息屏。

| 步骤 | 结果 |
|---|---|
| 手表点“立即同步” | SyncGuardService 启动，进入“正在准备活动原始数据…” |
| 打包完成、发现节点 | 手机端 RawWave 进程被按需拉起（pid 7127） |
| 传输 | 成功 |
| 手机接收状态 | **181 次活动**，最近：**散步 · 2026-11-17 11:03** |
| 手表端 | 同步会话结束，无 ANR、无崩溃 |

说明：
- 手机端 `PhoneWakeService`（WearableListenerService）在 App 未运行时被唤醒，节点发现等待窗口覆盖了这次唤醒延迟。
- 这次同步的是测试中误触产生的一个 2 分钟“散步”会话（已同步，可在手机/手表记录里删除）。

## 三星官方数据核查（同日导出，8.5 MB JSON）

`SamsungHealthExport_AllHistory_2026-11-17T10-45.zip`：

| 夜晚 | 睡眠记录 |
|---|---|
| 11/8→9、11/9→10 | 有 |
| **11/10→11** | **缺失** |
| 11/11→12 | 有 |
| **11/12→13、11/13→14、11/14→15、11/15→16** | **缺失** |
| 11/16→17 | 有（修复后） |

- 血氧、皮肤温度记录与睡眠完全同步：有睡眠的夜晚才有。
- **白天心率每天都有**（11/09–11/16 每天 840–1440 分钟覆盖），说明丢的是三星健康的夜间连续测量会话，不是整条传感器管线。
- 步数被手机与手表合并，不能单独证明手表数据；心率是手表独有，故用它作证据。

## 产物（`D:\RawWave-APK\`）

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `Watch_RawWave_0.12.46-step_sync-discovery-20261117-r06.apk` | 7,149,665 | `FC2967455029AA924145E4E1144214A46C7FCEDBB46D770B17C684CF3C319646` |
| `Phone_RawWave_0.12.46-step_sync-discovery-20261117-r06.apk` | 8,143,404 | `289BCCB845C60BE3A9A0ACE769FFD59F03AFBFD4B0B575373A00C8EDB7A8BD84` |
| `Both_RawWave_0.12.46-step_sync-discovery-20261117-r06.zip` | 5,496,091 | `930C2FAD6DE47DFBBA991CDFA74D478452829F2051096C9CC7966018ADB3CA7B` |

清单：`build-sync-discovery-20261117-r06.json`；校验：`SHA256SUMS-sync-discovery-20261117-r06.txt`。

## 限制

- 大档案仍受 5 分钟/次限制，跨多次打开续传；这是功耗与可靠性的折中。
- 若手机彻底 force-stop（包被冻结），节点仍不可见；本次验证的是系统回收/Doze 场景。
- 今晚不重启手表，正常佩戴睡眠，明早核对官方睡眠记录与过夜耗电，验证“无需每晚重启”。

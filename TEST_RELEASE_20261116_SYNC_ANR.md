# 0.12.45-step 长会话同步 ANR 修复 r04

日期：2026-11-16
版本：**119 / `0.12.45-step`**
Run ID：`sync-anr-20261116-r04`
状态：DEV `PASS`（522 tests / 1 skipped / 0 失败）；双端 release + lintRelease PASS；真机验证通过（打开、同步页、问题报告页均不再卡死）。

## 问题

长会话（1 小时）之后，App 在主线程扫描待同步会话的全部块文件，`File.listFiles` + `isFile` 卡 10 秒以上。系统判定输入超时，按“user request after error”杀进程。

用户可见现象：

1. 结束采集时界面卡死、黑屏（`finishNow` 自动同步在主线程扫描）。
2. 再次打开 App，点“手动同步”卡死退出。
3. 点“发送问题报告到手机”同样卡死退出（首页自动同步先占住主线程）。

## 证据（系统 ANR 堆栈，4 次完全一致）

```
"main" prio=5 tid=1 Runnable / Native(state=D)
  at java.io.File.listFiles(File.java:1312)
  at com.rawwave.core.ActivityArchive.sessionFiles(ActivityArchive.java:291)
  at com.rawwave.core.ActivityArchive.pendingRawBytes(ActivityArchive.java:80)
  at com.rawwave.shell.WatchActivitySyncSession.start(WatchActivitySyncSession.kt:59)
  at com.rawwave.shell.ProbeApplication.startWatchActivitySync(ProbeApplication.kt:56)
  at com.rawwave.shell.ActivityRecordingService.finishNow(...)
```

ANR 时间：15:38:59、15:39:23、15:39:50、15:40:19（另 15:46:20 一次）。`state=D` 说明主线程卡在不可中断的文件 I/O 上。

## 修复

`WatchActivitySyncSession`：

- 新增 `background` 执行器，`pendingRawBytes()` 的扫描移到后台线程，扫完 `post` 回主线程继续。
- 扫描前先开 `SyncGuardService` 前台租约（初始终 185 秒），保证后台扫描期间进程不被冻结；扫描完成后再按真实字节数延长租约。
- `leaseReady` 标志处理“租约先就绪、传输对象后创建”的顺序，避免漏启动。
- 主线程只做状态更新、界面刷新。
- 新增回归单测 `pendingByteScanRunsInsideBackgroundNotOnCallerThread`，并在同步相关测试里注入同步执行的背景执行器。

## 测试

| 套件 | 结果 |
|---|---:|
| `:shared:test` | 397 通过 |
| `:wear:testDebugUnitTest` | 96 通过，1 skipped |
| `:mobile:testDebugUnitTest` | 29 通过 |
| **合计** | **522 通过，0 失败** |

## 真机验证（SM-L350，119）

在仍有 2 个待同步会话（含 1 小时会话）的状态下：

| 操作 | 结果 |
|---|---|
| 冷启动 App，5 秒内点“更多” | 立即进入页面，无 ANR |
| 同步运行中（SyncGuardService 前台）打开“同步状态” | 立即显示，状态“同步中…” |
| 点“立即同步” | 后台继续，界面可操作，进程存活 |
| 点“发送问题报告到手机” | 打开问题报告页，提示“正在接收手表，请等待本轮完成”，无崩溃 |
| 15:57–16:02 全程 | 进程 pid 11004 一直存活；没有新的 ANR 记录（最后一条 ANR 是 15:46，修复包安装前） |

## 产物（`D:\RawWave-APK\`）

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `Watch_RawWave_0.12.45-step_sync-anr-20261116-r04.apk` | 7,149,265 | `2657AEAE5752EDC5DB3FA101CCA1AC57105FEDABBF44975406C21244130F375D` |
| `Phone_RawWave_0.12.45-step_sync-anr-20261116-r04.apk` | 8,142,932 | `801B0593CE9F3634FB134759924C62A707C1C829CA2B765CDDCEC2641E5D8DA0` |
| `Both_RawWave_0.12.45-step_sync-anr-20261116-r04.zip` | 5,495,943 | `DA803B2C33815FB937B972763DF40CE2429655AC73F4A3C26FBBDDC78E8AAEF7` |

清单：`build-sync-anr-20261116-r04.json`；校验：`SHA256SUMS-sync-anr-20261116-r04.txt`。

## 说明与限制

- 这次只修“主线程被扫描卡死”。1 小时会话的整体打包/传输仍然是分钟级任务，界面会显示“同步中…”，属于预期，不是卡死。
- `pendingRawBytes` 仍会遍历块文件（只是不再在主线程）；后续可在封存时写入字节数缓存，把扫描成本降到一次读取。
- 待同步的 2 个会话（含 1 小时测试会话）仍保留在手表；不想传可在同步状态页停止，数据不会丢。
- 过夜睡眠验证仍未完成（今晚）。

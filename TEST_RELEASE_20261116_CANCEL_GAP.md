# 0.12.44-step 取消/超时收尾修复（PPG 间隔） r03

日期：2026-11-16
版本：**118 / `0.12.44-step`**
Run ID：`cancel-gap-20261116-r03`
状态：DEV `PASS`（521 tests / 1 skipped / 0 失败）；双端 release + lintRelease PASS；真机取消 ×2、正常结束 ×1 零崩溃。

## 问题一：取消路径原本是一次性停止

`CANCEL`、80 秒超时、服务销毁都走 `finish()` → `drainSources { }`，PPG 和其他传感器同批拆除，正是 0.12.42 修掉的那个厂商崩溃条件。

修复：新增 `drainForFinish()`。先停其他来源 → 间隔 → 单独停 PPG → `finishNow()`；PG 单独在跑、且没有其他 client 刚被停过时不等间隔；间隔中服务被销毁也不跳过 PPG 单独释放。

## 问题二：drain 超时会让 3 秒间隔被跳过（今天真机抓到）

11:35 的日志显示，第一批 drain 里 4 个磁力计类传感器 5 秒预算用尽，失败分支直接走到“PPG 单独停”，**间隔只有 0.4 秒**：

```
11:35:39     停其他传感器
11:35:43.975 最后一批 stop（超时）
11:35:44.0   旧逻辑：PPG 单独 drain  ← 间隔 0.4 s
```

10:55 那次取消崩溃（`parsePPGMultiData` SIGSEGV，+11.6 秒）就是这条路径。实验室结论是“其他 client 全部退出后必须留出间隔”，而不是“PPG 那一刻没有并发 client 就行”。

修复：间隔改为从**最后一个非 PPG 传感器真正 unset 的时刻**计算，超时、失败、服务销毁路径一致。

## 真机证据（SM-L350，118）

| 时间 | 操作 | 最后其他 stop → PPG drain | 结果 |
|---|---|---:|---|
| 11:18 | 正常结束（修复前） | 0.4 s | 未崩（侥幸） |
| 11:35 | 取消（修复后） | 3.010 s | 零崩溃 |
| 11:41 | 正常结束（修复后） | 3.005 s | 零崩溃 |
| 11:45 | 取消（修复后） | 3.006 s | 零崩溃 |

- 三次修复后运行，`sensors.sec` pid 均未变化（25552），HAL 未重启。
- 停止后 30–45 秒 `logcat -b crash` 为空。
- 2026-11-16 10:55 的崩溃日志作为反例保留：`Fatal signal 11 ... Ppg25HzSensor::parsePPGMultiData(...)+146`，pid 14385，发生在取消后 11.6 秒。

## 代码

- `ActivityRecordingService.finish()`：改为 `drainForFinish()` 两段式。
- 新增 `lastOtherStopMs`：在 drain 完成回调和 `stopSources()` 里更新；`releasePpgAfterGap()` 按剩余间隔调度。
- `finishPhase`（1=其他来源，2=PPG）区分失败处理：第一阶段失败仍等间隔，第二阶段失败才立即 `finishNow`。
- `destroyed` 在收尾 drain 中不再短路，先完成 PPG 单独释放。
- 新增诊断日志：`RawWaveStop`（事件名 + elapsed）与 `RawWaveVendor`（unset 请求/返回阶段），只含阶段名与时间，不含采样值。

## 测试

| 套件 | 结果 |
|---|---:|
| `:shared:test` | 397 通过 |
| `:wear:testDebugUnitTest` | 95 通过，1 skipped |
| `:mobile:testDebugUnitTest` | 29 通过 |
| **合计** | **521 通过，0 失败** |

新增/更新：
- `cancelReleasesOtherSensorsBeforePpgAndWaitsForSettleGap`
- `cancelWithPpgOnlyReleasesImmediatelyWithoutGap`
- `cancelAfterDrainTimeoutStillWaitsSettleGapBeforePpg`
- `destroyDuringCancelSettleStillReleasesPpgAlone`
- `timeoutClosesSensorsAndLateAckCannotReviveCapture`（两段各 5 秒预算 + 间隔）
- `Rig.ackAll()` 会自动推进间隔。

## 产物（`D:\RawWave-APK\`）

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `Watch_RawWave_0.12.44-step_cancel-gap-20261116-r03.apk` | 7,146,973 | `DDB81FB3A245F27F97A9DC715B910B3142183E30E87C7B5F2EFB376BF6C7062B` |
| `Phone_RawWave_0.12.44-step_cancel-gap-20261116-r03.apk` | 8,140,416 | `44FBECBE8BF4C907F987100C10AC5864B0B6E6E09AD4FECA1DE21F657F4A6CAB` |
| `Both_RawWave_0.12.44-step_cancel-gap-20261116-r03.zip` | 5,494,794 | `07B18FD3387FC3AA7F3C1CD2959174896EEB38C033B1BEC7B271838F13C1CF09` |

清单：`build-cancel-gap-20261116-r03.json`；校验：`SHA256SUMS-cancel-gap-20261116-r03.txt`。

## 已知问题（下一轮）

- **长会话后主线程阻塞 / ANR。** 今天多次复现：1 小时会话封存后，打开 App 的主线程会卡 10 秒以上，此时点屏幕会触发 `Input dispatching timed out`，系统按“user request after error”杀进程。疑似首页渲染/自动同步在主线程序列化大文件；需要把封存/同步扫描移出主线程，并给首页加载加超时保护。
- 后置 ECG 路径的停止顺序本次未重新实验；按同一套两段式逻辑执行，但缺少真机对照。
- 过夜睡眠恢复待今晚验证。

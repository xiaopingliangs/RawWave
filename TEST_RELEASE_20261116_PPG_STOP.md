# 0.12.42-step 厂商 PPG 停止崩溃修复 r01

日期：2026-11-16
版本：**116 / `0.12.42-step`**
Run ID：`ppg-stop-order-20261116-r01`
状态：DEV `PASS`（517 tests / 1 skipped / 0 失败）；双端 release + lintRelease PASS；真机部分验证（长采集停止零崩溃、绿灯熄灭）。

## 问题

2026-11-12 起，每次原波 PPG 采集结束 6–14 秒后，手表厂商传感器进程 `sensors.sec` 在 `Ppg25HzSensor::parsePPGMultiData()` 里 SIGSEGV。HAL 重启后，三星健康的传感器管线被卡死，**睡眠数据从那天起整夜空**，用户只能靠重启手表恢复。

原波的采集、封存、同步本身都正常；问题出在“停止”这一个动作上，而且只在 PPG 25 Hz 会话上出现。

## 定位过程（2026-11-16 上午，同一块 SM-L350，逐变量）

| 时间 | 停止方式 | 结果 |
|---|---|---|
| 08:08 | 线上顺序：PPG 先停，心率仍开着 | **崩溃** +14.5 s |
| 08:13 | 跳过 flush，仍立即 unset | **崩溃** +7.1 s |
| 08:17 | PPG 完全不 unset，靠断开连接 | 不崩 |
| 08:22 | unset 延后 10 s（其他传感器已停） | 不崩 |
| 08:25 | 线上顺序复测 | **崩溃** +14.5 s |
| 08:29 | PPG 不 unset（二次确认） | 不崩 |
| 08:32 / 08:35 / 08:36 | 延后 10 s，三次 | 不崩 ×3 |
| 08:39 | 延后 16 s，但心率仍开着 | **崩溃** |
| 08:46 | 心率先停、PPG 最后停（同一批内） | **崩溃** |
| 08:49 | 只 unset 一次，其他传感器仍活着 | **崩溃** |
| 08:51 / 08:53 | **其他全部先停 → 3 秒 → PPG 单独停** | 不崩 ×2 |
| 09:09 | 正式包，约 9 分钟采集，同一顺序 | 不崩，绿灯熄灭 |

结论：**不是 flush，也不是 unset 太早，而是 PPG 25 Hz 会话不能和其他传感器 client 同时被拆除。** 哪怕 PPG 排在最后，只要在同一批里，厂商解析器仍会在 6–14 秒后崩溃；必须等其他 client 全部退出、留出一个间隔，再单独释放 PPG。

## 修复

`ActivityRecordingService` 的 END 路径改成两段：

1. `drainSources(keep = PPG)`：先 drain/stop 除 PPG 外的所有来源；
2. `writer.postDelayed(..., ppgReleaseGapMs)`：默认 **3000 ms**；
3. `drainSources(only = PPG)`：单独 flush + unset PPG，然后进入 post 流程。

- 事件：`LED_RELEASE_DRAIN_PPG_LAST`（`keep=PPG; gapMs=3000; reason=VENDOR_PPG_STOP_REQUIRES_LAST_CLIENT`）。
- 原来的“PPG 先停、保留心率”分支及其 `LED_RELEASE_DRAIN_PPG_FIRST` 已删除。
- 绿灯仍然熄灭：PPG 单独 unset 后厂商正常执行停止，真机目视确认。
- 代价：结束采集到封存多等 3 秒。

## 测试

| 套件 | 结果 |
|---|---:|
| `:shared:test` | 397 通过 |
| `:wear:testDebugUnitTest` | 91 通过，1 skipped |
| `:mobile:testDebugUnitTest` | 29 通过 |
| **合计** | **517 通过，0 失败** |

- 新增 `CaptureServiceTest.noPostEcgReleasesEveryOtherSensorBeforePpgAndOnlyThenPpgAlone`：断言先 drain 非 PPG、间隔未到时不 drain PPG、3 秒后才单独 drain PPG、最后封存。
- 旧测试 `noPostEcgDrainsPpgBeforeHeartRateForLedRelease` 删除。
- 受影响的 9 个用例（11 源、超时、磁盘失败等）改用 `Rig.ackAll()` 按新顺序收尾。
- `:wear:assembleRelease`、`:mobile:assembleRelease`、`:wear:lintRelease`、`:mobile:lintRelease` PASS。
- 另外修复了工作区里 `ActivityArchive.create()` 返回值丢失导致的编译失败（恢复 `Verified` 返回，`:shared:test` 全绿）。

## 真机验证

- 手表 SM-L350，无线 ADB；手机 SM-S9210。
- 正式包（116 同源代码）跑了一次约 9 分钟采集，停止后观察 40 秒：`logcat -b crash` 为空，`sensors.sec` PID 未变。
- 停止后目视表背：绿灯熄灭。
- 停止序列在实验包和正式包上一致（其他传感器先停、3 秒、PPG 单独停）。

## 产物（`D:\RawWave-APK\`）

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `Watch_RawWave_0.12.42-step_ppg-stop-order-20261116-r01.apk` | 7,143,957 | `649CC3083DC6D5FD81DD601620F36A31F375CB8D7B6C3BD93B5B4EB3FEDE56BE` |
| `Phone_RawWave_0.12.42-step_ppg-stop-order-20261116-r01.apk` | 8,140,424 | `B2D3CEEAF86A5D7920EE65D808A444A13B1223C9C7E507F80CA1CF8AB107871D` |
| `Both_RawWave_0.12.42-step_ppg-stop-order-20261116-r01.zip` | 5,494,202 | `12CD80309693FAA291380CBDDF7302A13C25CC2B0925049118532B6417C06AEC` |

清单：`build-ppg-stop-order-20261116-r01.json`；校验：`SHA256SUMS-ppg-stop-order-20261116-r01.txt`。

## 已知限制 / 风险

- **取消路径尚未修复。** “中断并保留已有数据”（`CANCEL`）在没有进行中的 drain 时仍走 `finishNow` 的一次性停止，PPG 和其他传感器同批拆除，可能仍触发厂商崩溃。下一步用同样的两段式收尾处理 `finish()`/`finishNow()`。
- **后置 ECG 路径保留旧顺序**（`postEcg` 时 PPG 会被保留到 post 流程结束时统一停），未在本次实验覆盖。
- 正式的一小时连续采集没有跑；已验证的是 9 分钟连续采集。崩溃点在停止动作，不在时长，但长采集仍建议再做一次验收。
- 过夜睡眠恢复待今晚验证：停止顺序修复后，三星健康不应再被 HAL 崩溃带死。
- `sensors.sec` 的解析器缺陷本身是厂商问题；本次只是绕开触发条件。

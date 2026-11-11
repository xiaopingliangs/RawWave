# RawWave 原波

RawWave 是一个开源的手表数据采集软件，主要面向三星 Galaxy Watch（Wear OS Powered by Samsung）。

**目标：让用户自己决定采集哪些数据，并尽可能完整地保存和导出。**

心率、ECG、血氧、PPG、皮肤温度、皮肤电导、身体成分、运动与姿态等数据，只要手表和系统支持，就尽量让用户自由选择和组合，而不是只能使用官方预设的运动模式。

采集的数据先保存在手表，再传到手机，导出成文件，方便用户自己使用。

### 我们不做数据分析

**RawWave 只负责采集、保存和导出数据，不负责解释数据。**

我们不提供健康评分、疾病诊断、运动建议或 AI 分析。

你可以把数据交给其他软件、AI，或者自己研究。

**简单来说：你决定采什么，RawWave 负责把数据尽可能完整地记录下来。**

目前项目仍在开发中，具体支持哪些数据，取决于手表硬件和系统开放的功能。

## 最新测试版（2026-11-11）

- `export-refresh-20261111-r01` / `0.12.35-step` / versionCode 109
- 这是测试版，不是稳定发布；0.12.35 真机 `NOT_RUN`，上一版 0.12.34 r07 已在 Galaxy Watch 上完成冒烟验证。
- 安装包、SHA256 和完整说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，也可以到 [Releases](https://github.com/xiaopingliangs/RawWave/releases) 下载。

0.12.35 重点（包含 0.12.23–0.12.35）：

- 手表首页以活动为先；活动模板改为“活动前 / 活动中 / 活动后”三段独立勾选。
- 采集结束后的 60 秒厂商释放移到应用级 keeper，所有同步都在后台运行，首页可以立即开始下一次活动。
- ECG 改为接触驱动：检测到接触立即开始 30 秒；80 秒只作为“始终没有接触”的退出保险，不会自动开始。
- BIA/MF-BIA 失败后 5 秒重试；重试不再重建按钮，滑动后的“跳过”仍在原位；失败审阅页的“跳过”置顶。
- 采集时由独立 worker 增量写 ZIP；同步支持断点续传，单次文件传输硬上限 5 分钟，到点暂停并在下次打开时续传。
- 手机导出支持“最近一次 / 最近三次 / 今天”；选中整包时直接复制已接收 ZIP，不再解包重压；最近三次会重新读取 inbox，不会漏掉新到的包。
- 手机首页新增一键“发送报告到手机”。

**已知限制：** 0.12.35 真机未跑；MF-BIA/SpO2 在部分佩戴/接触条件下仍可能被 SDK 判为质量失败（原始状态码保留）；Samsung Health 导出是官方处理后数据，不是原始 PPG/ECG 波形；厂商传感器停止没有硬件回执。

---

## 历史测试版（2026-11-09）

- `cache-visible-20261109-r04` / `0.12.22-step` / versionCode 96
- 这是测试版，不是稳定发布；手机真机验收待安装后完成，手表 r04 已安装。
- 安装包、SHA256 和完整说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，也可以到 [Releases](https://github.com/xiaopingliangs/RawWave/releases) 下载。

0.12.22 重点：

- 手机首页、活动导出页、官方导出页启用锁屏可见；首页不再按空闲或后台同步完成自行退出。
- 活动列表优先使用 `<包ID>.verified.json`，只对缺失/变化的包重新深度校验；旧包首次列出后补写回执。
- 单活动导出直接复制原始 ZIP，不再解包重打包；导出页显示百分比和 MB 进度。
- 问题报告改用独立工作线程，进度显示在报告按钮下方。
- 手表同时勾选连续 PPG 和前后短测 PPG 时跳过活动前 100Hz，降低背面灯常亮风险；连续 PPG 和活动后 100Hz 保留。
- 手表 RawWave 页面使用 `screenOrientation=nosensor`，充电时也不旋转；表盘/系统桌面仍受三星充电态策略影响。

**已知限制：** 手机真机尚未完成本轮验收；Samsung Health 导出是官方处理后数据，不是原始 PPG/ECG 波形；系统级旋转开关在用户版 ADB 无权限修改。

---

## 历史测试版（2026-11-08）

- `sync-packaging-20261108-r03` / `0.12.14-step` / versionCode 88
- 这是测试版，不是稳定发布；真机 `NOT_RUN`。
- 安装包、SHA256 和完整更新说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，也可以到 [Releases](https://github.com/xiaopingliangs/RawWave/releases) 下载。

0.12.14 重点：

- 活动打包改为独立预算：按未确认 session 的原始字节数、`256 KiB/s` 保守估算，封顶 25 分钟；小活动仍保持 80 秒初始窗口。
- 手表租约覆盖打包；自动重试重新开租约，不再出现约 1.9 秒的 `SYNC_DEADLINE`。
- 手机确认并持久提交后，手表删除本批运输 ZIP；原始 session、`seal.json`/`plan.json` 和 ack 继续保留。
- 手机数据页空闲改为 180 秒；CORE 无数据、ECG/SpO2 短测和手表界面仍保持 80 秒。
- 同时包含 0.12.13 的短测倒计时、BIA 两项区分和手表首页顺序。

**已知限制（仍然存在）：**

- 真机 `NOT_RUN`；需要一次 ≥45 分钟、≥400 MB 原始数据的活动复测。
- 没有 `PACKING_STARTED/PACKING_PROGRESS` 手机心跳；没有“最近一次 only”打包策略；没有打包断点续传。
- 手机 App 被划掉后，后台 `PhoneWakeService` + `SyncGuardService` 的唤醒仍需真机证据。
- ZIP 删除点在手机 `COMMITTED` + 手表 ack 持久化之后；如果最后的 `CONFIRM` 丢失，手机数据已保存，但手机端可能显示本轮未完成。
- 2026-11-08 的数据核对仍显示 step counter 与 step detector 不一致（2,048 vs 1,796）；两者都保留，不做补步或合并。
- 官方 Samsung Health 导出是 **Samsung Health 存储/处理后的数据**，不是原始 PPG/ECG 波形；官方 SDK 不开放原始波形。

---

# RawWave

RawWave is an open-source data collection app for Samsung Galaxy Watch (Wear OS Powered by Samsung).

**Our goal is simple: let users choose what data to collect, and save and export as much of it as possible.**

This includes heart rate, ECG, blood oxygen, PPG, skin temperature, electrodermal activity, body composition, movement and orientation data, and more. Users can choose and combine available sensors instead of being limited to preset workout modes.

Recorded data is saved on the watch, transferred to a phone, and exported as files.

### No Data Analysis

**RawWave collects, saves, and exports data. It does not analyze or interpret it.**

We do not provide health scores, medical diagnoses, fitness advice, or AI analysis.

You can use other apps, AI tools, or your own methods to explore the exported data.

**Simply put: you choose what to record. RawWave helps you keep the data.**

RawWave is still in development. Available features depend on the watch hardware and system support.

### Latest test build (2026-11-11)

`export-refresh-20261111-r01` / `0.12.35-step` / versionCode 109. Test build, not a stable release. Device verification for 0.12.35 is `NOT_RUN`; the previous 0.12.34 r07 passed a smoke test on a Galaxy Watch. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for SHA256 and installation notes.

0.12.35 (includes 0.12.23–0.12.35) makes the watch home activity-first, splits templates into pre/in/post phases, moves the 60-second vendor release into an app-level keeper so sync never blocks the home screen, starts ECG on real contact (80 s only as a no-contact exit guard), retries BIA/MF-BIA after 5 s without resetting scroll, writes the outgoing ZIP incrementally during capture, resumes interrupted transfers with a 5-minute file window, adds “latest / latest 3 / today” phone export with direct ZIP copy when the selection matches a received package, refreshes the export inbox on every selection, and adds a one-tap “send report to phone” button.

**Known limitations:** 0.12.35 device verification is pending; MF-BIA/SpO2 can still fail SDK quality checks under some contact conditions (raw status codes are retained); Samsung Health export is processed store data, not raw PPG/ECG; the vendor sensor stop has no hardware acknowledgement.


### Historical test build (2026-11-09)

`cache-visible-20261109-r04` / `0.12.22-step` / versionCode 96. Test build, not a stable release. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for SHA256 and installation notes.

0.12.22 adds verified-archive caching for activity listings, direct single-session ZIP export, a separate problem-report worker with progress beside the button, a persistent phone home that reappears over the lock screen, and `nosensor` lock for RawWave watch screens. The watch skips pre-activity 100 Hz PPG when continuous CORE PPG is also enabled to reduce the known vendor LED-retention path.

**Known limitations:** phone device verification is pending; Samsung Health export is processed store data, not raw PPG/ECG; the system-level rotation preference on the charging dock is controlled by Samsung and not changeable from a user-build ADB shell.


### Historical test build (2026-11-07)

`ux-sync-20261107-r01` / `0.12.12-step` / versionCode 86. Test build, not a stable release. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for SHA256 and installation notes.

0.12.12 adds a simplified phone home, date/session-based raw activity export with success dialogs, always-visible watch sync status/manual retry, background-permission guidance, screen-off-friendly sync with a 25-minute large-package ceiling, auto-height buttons, radial green button styling, and clearer short-measurement guidance/success/failure states.

**Known limitations:** device verification is `NOT_RUN`; the 18-minute test shows step counter 156 vs step detector 103 (both raw values retained, no reconciliation); no GPS fix indoors; Samsung Health export is processed store data, not raw PPG/ECG; seven official Samsung types had no data in this window; system-level power/sensor dumps are still not included in the app report.

## License

See [LICENSE](LICENSE).

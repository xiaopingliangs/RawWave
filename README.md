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

## 最新测试版（2026-11-07）

- `ux-sync-20261107-r01` / `0.12.12-step` / versionCode 86
- 这是测试版，不是稳定发布。
- 安装包、SHA256 和完整更新说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，也可以到 [Releases](https://github.com/xiaopingliangs/RawWave/releases) 下载。

0.12.12 重点：

- 手机首页收敛为：活动原始数据、Samsung Health 官方数据、问题报告、后台运行、退出。
- 新增按“最近一次 / 今天 / 日期范围”导出原始活动，按 session 导出，成功弹窗显示 `Download/RawWave` 和 SHA-256。
- 手表首页始终有同步状态和手动同步/刷新入口；同步不再强制亮屏，大包时限提高到 25 分钟。
- 手机/手表增加后台运行权限引导；报告增加手机接收结果和同步结论。
- 按钮改为随文字自动增高；绿色按钮改为中心偏亮径向材质。
- 活动计时只在活动记录期间显示；短测增加示意图、接触状态、成功提示和失败重测/跳过。

**已知限制（仍然存在）：**

- 真机 `NOT_RUN`；没有本轮设备截图。
- 0.12.12 的 18 分钟测试中，step counter 与 step detector 数值不一致（156 vs 103）；两者都保留，不做补步或合并。
- 室内工位测试没有 GPS fix；GPS 0 点属于环境结果，不是应用崩溃。
- 官方 Samsung Health 导出是 **Samsung Health 存储/处理后的数据**，不是原始 PPG/ECG 波形；官方 SDK 不开放原始波形。
- 部分 Samsung Health 类型为 NO_DATA（本次：血糖、血压、体温、心律不齐通知、营养、睡眠呼吸暂停、饮水），不是导出失败。
- 问题报告的手机端应用层证据完整；系统功耗、传感器硬件停止和完整系统 dump 仍需另抓。

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

### Latest test build (2026-11-07)

`ux-sync-20261107-r01` / `0.12.12-step` / versionCode 86. Test build, not a stable release. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for SHA256 and installation notes.

0.12.12 adds a simplified phone home, date/session-based raw activity export with success dialogs, always-visible watch sync status/manual retry, background-permission guidance, screen-off-friendly sync with a 25-minute large-package ceiling, auto-height buttons, radial green button styling, and clearer short-measurement guidance/success/failure states.

**Known limitations:** device verification is `NOT_RUN`; the 18-minute test shows step counter 156 vs step detector 103 (both raw values retained, no reconciliation); no GPS fix indoors; Samsung Health export is processed store data, not raw PPG/ECG; seven official Samsung types had no data in this window; system-level power/sensor dumps are still not included in the app report.

## License

See [LICENSE](LICENSE).

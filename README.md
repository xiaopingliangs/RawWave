# RawWave 原波

RawWave 是一个开源的手表数据采集软件，主要用于三星 Galaxy Watch（我现在只有三星S24和三星watch 9，其他设备不保证能用）。

**我们的目标很简单：让用户自己决定采集哪些数据，并尽可能完整地保存和导出。**

比如心率、心电图、血氧、皮肤温度、身体活动等数据。只要手表和系统支持，就希望用户能够自由选择和组合，而不是只能使用官方预设的运动模式。

采集的数据可以保存到手表，再传到手机，导出成文件，方便用户自己使用。

### 我们不做数据分析

**RawWave 只负责采集、保存和导出数据，不负责解释数据。**

我们不提供健康评分、疾病诊断、运动建议或 AI 分析。

你可以把数据交给其他软件、AI，或者自己研究。

**简单来说：你决定采什么，RawWave 负责把数据尽可能完整地记录下来。**

目前项目仍在开发中，具体支持哪些数据，取决于手表硬件和系统开放的功能。

## 最新测试版（2026-11-04）

- `ppg-mode-test-20261104-r02` / `0.8.9-ppg-mode-test` / versionCode 65
- 模板可独立选择“活动中连续 PPG 25Hz”和“活动前后 PPG 100Hz 30秒”，并提供四个测试预设；同时包含手表同步成功退出修复。
- 这是诊断测试版，真机尚未验收，不是稳定发布；LED 仍未闭环。
- 安装包、SHA256 和测试步骤见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，也可到 [Releases](https://github.com/xiaopingliangs/RawWave/releases/tag/ppg-mode-test-20261104-r02) 下载。

---

# RawWave

RawWave is an open-source data collection app for Samsung Galaxy Watch.

**Our goal is simple: let users choose what data to collect, and save and export as much of it as possible.**

This includes heart rate, ECG, blood oxygen, skin temperature, movement, and more. Users can choose and combine available sensors instead of being limited to preset workout modes.

Recorded data can be saved on the watch, transferred to a phone, and exported as files.

### No Data Analysis

**RawWave collects, saves, and exports data. It does not analyze or interpret it.**

We do not provide health scores, medical diagnoses, fitness advice, or AI analysis.

You can use other apps, AI tools, or your own methods to explore the exported data.

**Simply put: you choose what to record. RawWave helps you keep the data.**

RawWave is still in development. Available features depend on the watch hardware and system support.

### Latest test build (2026-11-04)

`ppg-mode-test-20261104-r02` / `0.8.9-ppg-mode-test` / versionCode 65. Independent continuous 25 Hz PPG and pre/post 100 Hz PPG modes, four test presets, and the watch sync-exit fix. Diagnostic build; not device-verified and not a stable release. See [RELEASE_NOTES.md](RELEASE_NOTES.md).

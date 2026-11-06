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

## 最新测试版（2026-11-06）

- `bia-retry-20261106-r01` / `0.12.3-step` / versionCode 77
- 这是测试版，不是稳定发布。
- 安装包、SHA256 和更新说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，也可以到 [Releases](https://github.com/xiaopingliangs/RawWave/releases) 下载。

本版包含：

- BIA / MF-BIA 中断后的进度、原因和重试提示；测量中显示剩余秒数
- 活动前/后短测的自动接触、自动开始、80 秒保存退出
- PPG / 血氧成功后自动完成
- 线性加速度、姿态四元数、平台传感器清单
- 手机自动接收和自动同步修复

0.12.3 主要是交互修复，没有新增传感器；0.12.2 新增的线性加速度和姿态四元数已在 Galaxy Watch 上采到 25 Hz 数据。

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

### Latest test build (2026-11-06)

`bia-retry-20261106-r01` / `0.12.3-step` / versionCode 77. Test build, not a stable release. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for SHA256 and installation notes.

This build includes BIA/MF-BIA retry progress, short-test auto contact/auto start/80-second save-and-exit, automatic completion after successful PPG/SpO2, linear acceleration, game rotation vector, platform sensor inventory, and phone receive/sync fixes.

0.12.3 is mainly an interaction fix and does not add new sensors. The two fused sources added in 0.12.2 have been verified at 25 Hz on a Galaxy Watch.

## License

See [LICENSE](LICENSE).

# RawWave 原波

RawWave 是一个公开源代码的手表数据采集软件（非商业许可），主要面向三星 Galaxy Watch（Wear OS Powered by Samsung）。

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

- `gravity-geomag-20261106-r01` / `0.12.5-step` / versionCode 79
- 这是测试版，不是稳定发布。
- 安装包、SHA256 和更新说明见 [RELEASE_NOTES.md](RELEASE_NOTES.md)，也可以到 [Releases](https://github.com/xiaopingliangs/RawWave/releases) 下载。

0.12.5 新增：

- `GRAVITY` 重力矢量（平台融合，25 Hz）
- `GEOMAGNETIC_ROTATION_VECTOR` 地磁姿态四元数（磁力计 + 加速度，25 Hz）

0.12.4 已完成：

- BIA / MF-BIA 改为只在活动前测量，活动后不再测体成分
- `ROTATION_VECTOR` 绝对姿态旋转矢量
- `WRIST_TILT_GESTURE` 抬腕手势事件

0.12.3 已完成：

- BIA / MF-BIA 中断后显示进度、原因和第几次尝试
- 2 秒后自动重试
- 移除手表不支持的“未校准加速度”

0.12.5 已在 Galaxy Watch 上验证：重力/地磁姿态均为 25 Hz；BIA/MF-BIA 仅活动前且成功；ECG 前后连续；后置 PPG 成功；自动同步第一次唤醒成功。SpO2 本轮的两次尝试均超时，属于接触/血流质量问题，不是数据管线错误。

---

# RawWave

RawWave is a source-available data collection app for Samsung Galaxy Watch (Wear OS Powered by Samsung), released under a noncommercial license.

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

`gravity-geomag-20261106-r01` / `0.12.5-step` / versionCode 79. Test build, not a stable release. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for SHA256 and installation notes.

0.12.5 adds `GRAVITY` and `GEOMAGNETIC_ROTATION_VECTOR`. 0.12.4 made BIA/MF-BIA pre-activity only and added `ROTATION_VECTOR` and `WRIST_TILT_GESTURE`. 0.12.3 improved BIA retry feedback and removed the unsupported uncalibrated accelerometer.

## License

This project is **source-available** under the [PolyForm Noncommercial License 1.0.0](LICENSE).

- Individuals may use, copy, modify, and share it for any **noncommercial** purpose.
- You may build on it and add features, provided derivative distributions keep the same license and notices.
- **Commercial use is not permitted.** Commercial use requires separate written permission from the copyright holder.
- Because commercial use is restricted, this is not an OSI-approved open-source license. The source is public and available for personal/noncommercial collaboration.
- Earlier releases published under MIT remain under MIT for copies obtained under that license; this license applies to this and future versions.

For commercial licensing, contact the maintainer via GitHub: <https://github.com/xiaopingliangs>.

Copyright (c) 2026 肖平亮 Sean. See [LICENSE](LICENSE) for the full terms.

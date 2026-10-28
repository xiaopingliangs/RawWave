# RawWave 原波

**一个专注于可自定义生理传感器数据采集、保存和导出的开源项目。**

RawWave 是一款面向 Samsung Galaxy Watch（Wear OS）与 Android 手机的传感器数据采集工具。

项目的核心目标是：**尽可能开放设备能够提供的数据采集能力，让用户自主决定采集什么、何时采集、如何组合，并保留可供后续研究和处理的数据。**

不同于传统健康和运动应用，RawWave 不以运动成绩、健康评分或分析结果为核心，而是关注数据本身。

## 主要功能与目标

- **自定义数据采集：** 根据设备和 SDK 的实际支持情况，自由选择心率、心搏间期、ECG、PPG、血氧、体成分、皮肤电活动、皮肤温度、加速度及其他运动传感器数据。
- **灵活组合传感器：** 支持建立不同活动模板，自定义连续采集项目，以及活动前后的短时测量。
- **保留数据细节：** 优先保存 SDK 提供的原始数据，并明确区分原始数据和厂商加工数据，同时尽可能保留时间戳、单位、采样状态及数据质量信息。
- **本地保存与导出：** 以可靠的数据保存、完整性校验、手表与手机之间的传输，以及标准化文件导出为目标。
- **用户自主控制：** 尽量减少不必要的数据筛选、自动加工和功能限制，让用户能够自行决定后续如何使用数据。

## 不提供数据分析

**RawWave 是数据采集工具，不是数据分析工具。**

本项目不提供：

- 自动健康评分或健康状况评价
- 疾病诊断、预测或医疗建议
- 运动表现分析或个性化训练建议
- 基于采集数据的自动结论或 AI 解读

RawWave 不试图替用户解释数据的意义。

**我们的目标是尽可能真实、完整、透明地保存设备能够提供的数据，而不是告诉用户这些数据意味着什么。**

用户可以将导出的数据用于个人研究、可视化、第三方分析工具或其他合法用途。

## 开发状态与限制

RawWave 仍处于开发阶段。

部分功能依赖 Samsung Health Sensor SDK、Wear OS 系统接口及设备硬件能力。不同手表型号、系统版本和授权条件下，可采集的数据类型、采样频率及并发能力可能不同。

并非所有列出的数据类型都已在所有设备上实现或验证。具体支持情况以实际版本和真机测试结果为准。

本项目不属于医疗器械软件，不能替代专业医疗设备或医疗服务。

---

# RawWave

**An open-source project focused on customizable physiological sensor data collection, storage, and export.**

RawWave is a sensor data acquisition tool designed for Samsung Galaxy Watch (Wear OS) and Android phones.

Its primary goal is to **give users greater control over the data their devices can provide — allowing them to choose what to collect, when to collect it, how to combine sensors, and how to preserve the resulting data for further use.**

Unlike conventional health and fitness applications, RawWave focuses on the data itself rather than fitness scores, health assessments, or analytical conclusions.

## Key Features and Goals

- **Customizable data collection:** Select from available data sources such as heart rate, inter-beat intervals, ECG, PPG, blood oxygen, body composition, electrodermal activity, skin temperature, accelerometer, and other motion sensors, depending on device and SDK support.
- **Flexible sensor combinations:** Create reusable activity templates with configurable continuous recording and optional measurements before and after activities.
- **Data preservation:** Prioritize raw sensor data when available, clearly distinguish raw measurements from vendor-processed values, and preserve timestamps, units, measurement status, and quality metadata whenever possible.
- **Local storage and export:** Aim to provide reliable recording, integrity verification, watch-to-phone transfer, and export in accessible file formats.
- **User control:** Minimize unnecessary filtering, automatic processing, and predefined restrictions, allowing users to decide how to use their data.

## No Data Analysis

**RawWave is a data collection tool, not a data analysis platform.**

The project does not provide:

- Automated health scores or health assessments
- Medical diagnosis, disease prediction, or medical advice
- Sports performance analysis or personalized training recommendations
- AI-generated interpretations or automated conclusions based on recorded data

RawWave does not attempt to interpret the meaning of your measurements.

**Our goal is to preserve available sensor data as faithfully, completely, and transparently as possible — not to tell users what their data means.**

Exported data can be used for personal research, visualization, third-party analysis tools, or other lawful purposes.

## Development Status and Limitations

RawWave is currently under active development.

Some features depend on the Samsung Health Sensor SDK, Wear OS APIs, and device-specific hardware capabilities. Available data types, sampling rates, and simultaneous recording support may vary by device, firmware, and permissions.

Not all listed data sources have been implemented or verified across all devices. Actual availability depends on the application version and hardware testing.

RawWave is not medical device software and is not intended to replace professional medical equipment or healthcare services.

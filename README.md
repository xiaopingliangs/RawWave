# RawWave 原波

Wear OS 手表与 Android 手机配套的传感器采集项目。

包含 ECG、血氧、体成分、心率、PPG、EDA、皮温及运动传感器采集，支持活动记录和传输。部分功能依赖兼容的三星手表及 Samsung Health Sensor SDK。

## 构建

需要 Android Studio、Android SDK 36、Gradle 9.3.1、JDK 17 或以上。请从三星开发者渠道取得 Samsung Health Sensor SDK 1.4.1，并将 AAR 放至 `wear/libs/samsung-health-sensor-api-1.4.1.aar`。SDK 及设备功能受三星许可与硬件支持限制。

项目仍在开发中；开发侧自动测试通过不代表所有机型经过实机验证。

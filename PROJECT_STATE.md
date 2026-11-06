# 当前状态

2026-11-06：最新测试版 `bia-retry-20261106-r01` / `0.12.3-step` / versionCode 77。

- 这是测试版，不是稳定发布。
- 0.12.3 主要修复 BIA / MF-BIA 中断后的提示和重试体验，并移除手表不支持的“未校准加速度”选项；**没有新增传感器**。
- 0.12.2 已在 Galaxy Watch（One UI 9.0）上验证：`LINEAR_ACCELERATION` 与 `GAME_ROTATION_VECTOR` 各 12,128 行、25 Hz、accuracy 3；平台传感器清单 41 项；手机第一次唤醒自动同步成功。
- 已覆盖数据源：Samsung Health Sensor SDK 的 ECG、PPG（连续 + 100 Hz 按需）、SpO2、BIA、MF-BIA、心率（含 IBI）、EDA、皮肤温度、加速度；Android 平台的陀螺仪、未校准陀螺仪、磁场、未校准磁场、气压、环境光、GPS 定位点、步数、每步事件、佩戴状态、线性加速度、姿态四元数。
- 不支持的源：`ACCELEROMETER_UNCALIBRATED`；`SIGNIFICANT_MOTION` 不在平台传感器列表。
- BIA / MF-BIA 活动后仍可能在测量中途因 STATUS 9 / 19 失败；SDK 不能续测，应用只能重试。下一版计划改为只在活动前测量。
- 下一版计划：BIA / MF-BIA 活动前 only；增加 `WRIST_TILT_GESTURE`、`ROTATION_VECTOR`。

自动测试：424 项通过，1 项跳过（私有语料未配置）；双端 release / lint / APK 构建通过。0.12.3 未单独真机测试。

安装包和 SHA256 见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。项目不是医疗器械，不提供诊断或治疗建议。

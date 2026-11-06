# 当前状态

2026-11-06：最新测试版 `gravity-geomag-20261106-r01` / `0.12.5-step` / versionCode 79。

- 这是测试版，不是稳定发布。
- 0.12.5 新增 `GRAVITY`（平台融合重力矢量，25 Hz）和 `GEOMAGNETIC_ROTATION_VECTOR`（磁力计 + 加速度四元数，25 Hz）。
- 0.12.4 已将 BIA/MF-BIA 改为只在活动前测量，并新增 `ROTATION_VECTOR` 和 `WRIST_TILT_GESTURE`。
- 0.12.3 已修复 BIA/MF-BIA 中断后的进度/原因/重试提示，并移除手表不支持的“未校准加速度”。
- 0.12.5 已在 Galaxy Watch（One UI 9.0）上验证：重力/地磁姿态 25 Hz、accuracy 3；BIA/MF-BIA 仅活动前且成功；ECG 前后连续；后置 PPG 成功；自动同步第一次唤醒成功。
- 本轮 SpO2 前置/后置均只收到 STATUS=0，30 秒超时；属于接触/血流质量问题，不是数据管线错误。
- 公开可用的标准平台源基本已接入完毕。剩余是 `DEVICE_ORIENTATION`（低价值）、三星私有 vendor 传感器（不接）、`SWEAT_LOSS`（仅跑步专项）。
- 后续方向：30 分钟长测、跑步专项或数据质量优化，不再无脑增加派生姿态/重力数据。

自动测试：440 项通过，1 项跳过（私有语料未配置）；双端 release / lint / APK 构建通过。

安装包和 SHA256 见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。项目不是医疗器械，不提供诊断或治疗建议。

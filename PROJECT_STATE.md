# 当前状态

2026-11-09：最新测试版 `cache-visible-20261109-r04` / `0.12.22-step` / versionCode 96。

- 这是测试版，不是稳定发布；手机真机验收仍需用户安装后完成，手表 r04 已安装并确认版本。
- 手机首页、原始活动导出页、Samsung Health 导出页启用锁屏可见；手机首页不再按空闲或后台同步完成自行退出。
- 活动列表优先读取 `<包ID>.verified.json`，只对缺失或变化的包重新深度校验；旧包首次列出后补写回执。
- 单活动导出直接复制原始 ZIP，边复制边算 SHA-256，发布前仍回读校验；导出页显示百分比和 MB 进度。
- 问题报告使用独立工作线程，不再排在活动校验后面；进度显示在报告按钮下方。
- 手表同时勾选连续 PPG 和前后短测 PPG 时跳过活动前 100Hz，避免厂商 25 Hz client 残留导致背面灯常亮；连续 PPG 和活动后 100Hz 保留。
- 手表三个 Activity 使用 `screenOrientation=nosensor`，RawWave 页面在充电时也不旋转；表盘/系统桌面仍受三星充电态旋转策略影响。
- 已验证：shared 384 + wear 82 + mobile 23 = 489 tests，失败 0、错误 0、跳过 1；lint 0 errors。
- 已知限制：手机真机尚未完成本轮验收；Samsung Health 导出仍是官方处理后数据，不是原始 PPG/ECG 波形；系统级旋转开关在用户版 ADB 无权限修改。

安装包和 SHA256 见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。项目不是医疗器械，不提供诊断或治疗建议。
# 当前状态

2026-11-17：最新测试版 `sync-discovery-20261117-r06` / `0.12.46-step` / versionCode 120。

- 这是测试版，不是稳定发布；0.12.46 已在 Galaxy Watch（SM-L350）+ Galaxy S9210 验证。
- 核心修复：PPG 25 Hz client 不能与其他传感器 client 同批拆除。结束、取消、80 秒超时、服务销毁统一走「先停其他来源 → 等 3 秒（从最后一次其他 stop 起算）→ 单独 flush+unset PPG → 封存」。修复后 `sensors.sec` 零崩溃，三星健康夜间睡眠记录恢复（11/16→17）。
- 同步：长会话的 `pendingRawBytes()` 字节扫描移到后台线程并先开前台租约（修 ANR）；手机节点发现最多等 45 秒、每 2 秒轮询（修息屏/Doze 下的 `CONNECTED_NODE_COUNT_0`）。
- 封存：改用写入时清单 + 元数据分层上限（seal 32 MiB / bundle 16 MiB / plan 8 MiB）；2 小时会话封存从 31 分 16 秒降到 0.86 秒。
- 息屏：删除 `FLAG_KEEP_SCREEN_ON`，由系统息屏；同步成功后只退出一次，空闲自动补同步不退出。
- 测试：shared 397 + wear 96（1 skipped）+ mobile 29 = **522**，失败 0；双端 release + lintRelease PASS；同证书。
- 真机：1 小时采集 08:09–09:09（PPG 91,081 行）封存并同步到手机（180 次活动，原始约 1108.6 MB）；过夜耗电 0.001 mAh、无服务/传感器/wakelock 残留；手机 App 未运行 + 屏幕 Doze 时息屏同步成功。
- 待验证：11/17→18 不重启手表的正常睡眠（三星官方睡眠记录 + 过夜耗电）。
- 已知限制：SpO2/MF-BIA 受佩戴/接触影响；后置 ECG 按正常位置；Samsung Health 导出是官方处理后数据；厂商传感器停止无硬件回执；大档案仍 5 分钟/次断点续传；手机 force-stop 时节点不可见。

安装包和 SHA256 见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。项目不是医疗器械，不提供诊断或治疗建议。

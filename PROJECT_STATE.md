# 当前状态

2026-11-08：最新测试版 `sync-packaging-20261108-r03` / `0.12.14-step` / versionCode 88。

- 这是测试版，不是稳定发布；真机 `NOT_RUN`。
- 活动打包改为独立预算：按未确认 session 原始字节 / 256 KiB/s，封顶 25 分钟；小活动仍 80 秒。
- 手表租约覆盖打包；自动重试重新开租约；手机 ack 后删除本批运输 ZIP，保留原始 session 和 ack。
- 手机数据页空闲 180 秒；CORE 无数据、ECG/SpO2 短测和手表界面仍 80 秒。
- 包含 0.12.13 的短测倒计时、BIA 两项区分、手表首页顺序。
- 2026-11-08 数据核对：48 分钟活动 37,952 个文件；37,952/37,952 SHA-256 匹配；`SEALED_VERIFIED`；GPS 2,455 点 / 3.17 km；881,979 行 committed。
- 仍然存在：没有打包进度心跳；没有“最近一次 only”；没有打包断点续传；手机后台 FGS 唤醒需真机；Step counter vs Step detector 不一致，原始值保留。

自动测试：454 项通过 / 1 skip（私有语料未配置）；双端 release / lint / APK 构建通过；真机 `NOT_RUN`。

安装包和 SHA256 见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。项目不是医疗器械，不提供诊断或治疗建议。

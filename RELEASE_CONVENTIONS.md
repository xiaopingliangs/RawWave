# RawWave 构建产物与安装包命名约定

更新：2026-11-07。此约定从本日起对所有后续构建生效。

## 1. 固定交付目录

所有面向用户安装的 APK / ZIP 统一放在：

```text
D:\RawWave-APK\
```

- 不要再把交付 APK 只放在项目内的 `dist/<runId>/`、临时目录或聊天附件目录。
- `dist/<runId>/` 可以继续作为构建历史/中间产物，但每次完成后必须把最终交付文件复制到 `D:\RawWave-APK\`。
- 该目录按文件名直接存放，不强制再建子目录，方便一眼找到最新包。

## 2. 文件名必须以英文设备名开头

Android 安装器/文件管理器在窄界面可能只显示文件名前几个字。文件名必须以：

- `Phone_` 开头，表示手机 APK；
- `Watch_` 开头，表示手表 APK；
- `Both_` 开头，表示同时包含手机和手表的 ZIP。

推荐格式：

```text
Phone_RawWave_<versionName>_<runId>.apk
Watch_RawWave_<versionName>_<runId>.apk
Both_RawWave_<versionName>_<runId>.zip
```

示例（0.12.9-spike）：

```text
Phone_RawWave_0.12.9-spike_ui-spike-20261107-r01.apk
Watch_RawWave_0.12.9-spike_ui-spike-20261107-r01.apk
```

要求：

- 设备名必须是文件名的第一个词，不能写成 `RawWave_Phone_...` 或 `RawWave-mobile-...`。
- 不再使用中文 `手机_` / `手表_` / `双端_` 作为新交付前缀；中文前缀只保留为历史文件。
- 不使用空格；用 `_` 或 `-` 分隔。
- 文件名保留 `versionName` 和 `runId`，避免覆盖旧包。
- 不要用 `final`、`latest` 等会过期的词作为唯一标识；最新包通过版本号/构建记录判断。

## 3. 每次交付同时写记录

在 `D:\RawWave-APK\` 同时写入：

- `build-<runId>.json`：版本号、APK 文件名、字节数、SHA-256、构建时间；
- `SHA256SUMS-<runId>.txt`：每行 `SHA256  <文件名>`。

示例：

```text
build-ui-spike-20261107-r01.json
SHA256SUMS-ui-spike-20261107-r01.txt
```

## 4. 发布文档同步

每次生成安装包后，同步更新：

- 本次 release note 的 APK 路径和 SHA-256；
- `AI_HANDOFF.md` 顶部当前版本段；
- `PROJECT_STATE.md` 当前段（如适用）。

## 5. 历史说明

`D:\RawWave-APK\` 中的 `RawWave-mobile-*` / `RawWave-wear-*` 以及中文 `手机_` / `手表_` / `双端_` 前缀文件是旧命名，只作历史参考；新构建一律使用 `Phone_` / `Watch_` / `Both_` 前缀。

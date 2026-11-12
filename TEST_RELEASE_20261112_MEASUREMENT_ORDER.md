# 0.12.39-step 前后置测量顺序按姿势重排 r05

日期：2026-11-12
版本：**113 / `0.12.39-step`**
Run ID：`measurement-order-20261112-r05`
状态：DEV `PASS`（514 tests / 1 skipped / 0 失败）；双端 release + lintVital PASS；同证书；真机 PASS（表单顺序、血氧引导页、BIA 双项引导页、取消路径）。

## 问题

0.12.37 的前置顺序是 BIA → 血氧 → ECG → PPG，把血氧夹在中间；血氧是唯一强制要求改变手表位置的测量（三星要求把表戴到腕骨上方），夹在中间会让用户上移、测、下移，再继续 ECG/PPG。旧文案还写着"手表正常贴合即可，不用调表带"，与三星要求相反。

## 三星官方姿势（查证）

| 测量 | 手表位置 | 姿势 |
|---|---|---|
| BIA / MF-BIA | 正常腕部，表带贴合 | 双臂抬到胸口、腋下张开；中指/无名指轻触上下键，两指不相碰 |
| ECG | 正常腕部，表带贴合；接触差时可稍向肘部 | 前臂放桌，指尖盖住上键不按压，30 秒不动不说话 |
| 血氧 SpO₂ | **沿前臂上移 1–2 指宽，戴到腕骨上方**，贴合不勒紧 | 手肘放桌，手腕靠近心脏，静止不说话 |
| PPG 100Hz | 正常腕部 | 手臂放松，静止 30 秒 |

来源：Samsung Support（BIA `ANS10003647`、ECG UK/`ANS10003433`、SpO2 AU 支持页与手表说明书"above the wrist"）。

## 新顺序

```
活动前：血氧（上移）→ 戴回正常位置 → BIA/MF-BIA → ECG → PPG → 开始活动
活动后：ECG → PPG → 血氧（上移）→ 结束
```

- 每个阶段只动一次手表：前置上移/戴回各一次，之后 BIA、ECG、PPG 全在正常位置，活动也从正常位置开始；后置在最后上移一次，测完直接结束。
- BIA 引导和说明里加了"先把手表戴回正常位置"。

## 同时修掉的引导页问题（真机发现）

- 状态行和 `HomeKeyGuide` 仍写着旧的血氧文案 → 改为"手表上移到腕骨上方 / 表带贴合，手肘放桌"。
- 120dp 的引导动画把第 3 行说明挤出 480px 圆屏 → 引导高度 84dp，所有引导压缩到两行；BIA 双项时用"第 1/2 项"一行 + 一行说明。
- 活动表单勾选顺序改为 血氧 → 体成分 → ECG → PPG，并写明"血氧要先把表上移，测完戴回，之后不用再动表"；活动管理摘要按执行顺序显示。

## 测试

- 更新 `spo2RunsFirstForTheStrapPositionThenBiaAndSpo2RemainsThePostLastMeasurement`、`preMeasurementsRunInStrapPositionOrderAndPpgNeedsNoPlacementPause`。
- 全套 514 通过（shared 396 + wear 89，1 skipped + mobile 29），lintVital PASS。

## 真机验证

- 手表 SM-L350，0.12.39-step：活动表单顺序与提示正确；启动「散步」后第一屏为血氧引导，文案完整可见（不再被圆屏裁切）；跳过后 BIA 双项引导页两行完整可见；取消会话正常。
- 手机 SM-S9210 安装 0.12.39-step。

## 产物（`D:\RawWave-APK\`）

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `Watch_RawWave_0.12.39-step_measurement-order-20261112-r05.apk` | 7,118,536 | `2B2DDFB77E5E9033DC397F9350A809EA36971345D2A0FBF9A2BC0FE757294CAA` |
| `Phone_RawWave_0.12.39-step_measurement-order-20261112-r05.apk` | 8,136,636 | `F5346EE82C87488EB132AFFF87FE9D690F1FE2CC8C996246EDCFCD9B7F11636D` |
| `Both_RawWave_0.12.39-step_measurement-order-20261112-r05.zip` | 5,479,728 | `2D025C5E545024B0C4D4A5BF2F62CD18FCA3159FA791AFE6723BF074283EA2B2` |

清单 `build-measurement-order-20261112-r05.json`；校验 `SHA256SUMS-measurement-order-20261112-r05.txt`。

## 未验证 / 风险

- 只验证了引导页和顺序，没有跑完整的前后置测量；血氧成功率和 MF-BIA 仍是既有问题。
- 验证过程中留下几条被取消的短会话（本地记录里可见），会随后台同步进入手机；可忽略。
- 后置 ECG 仍按正常位置做（三星标准要求；"稍向肘部"只是接触不佳时的建议）。若要求后置 ECG 也在腕骨上方，需要把后置顺序改为 PPG → ECG → SpO2，并重新评估 vendor LED 释放逻辑。

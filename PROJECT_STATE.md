2026-11-01 测试版交付：core-audit-20261101-r01。按用户最新要求提供双端测试APK。已复核126源码输入及双APK哈希0差异，既有27份报告共305测试/失败错误跳过0，原构建154任务PASS；本轮未重建、未改生产代码。DEVICE NOT_RUN；LED与EDA实机收尾未闭环。交付及更新日志见TEST_RELEASE_20261101.md。此前不请求安装的记录为历史阶段策略。

2026-11-01 当前：exit-sync-20261101-r01 内部候选，303测试/154任务、双端release/lint PASS。修复任务划掉先取消同步、采集收尾却又启动同步的竞争；taskRemoved标记在最终交接前阻止新同步，已有数据仍保存。新增排空期间及封存后划掉任务测试，正常结束同步资格测试通过。真实数据及异常回归继续通过。LED/EDA及物理后台行为仍未证实，不请求安装。证据：evidence/sensors/20261101-exit-sync.md。

2026-11-01 当前：stream-owner-20261101-r01 内部候选，301测试/154任务与双端release/lint PASS。输入输出流纳入终止式资源清理，取消后晚到流也请求关闭；有界后台线程执行close，异常进入诊断。实际TransferResourceOwner晚到/重复关闭和阻塞close测试通过。三次数据重放及故障回归通过。物理资源停止、LED、EDA尾部仍未证实，不请求安装。见evidence/sensors/20261101-stream-owner.md。

2026-11-01 当前：sync-owner-20261101-r01 内部候选，299测试/154任务、双端release/lint PASS。修复传输对象初始化异常遗漏清理导致同步占用及running状态滞留；owner关闭独立尝试取消与lease释放。新增实际WatchActivitySyncSession初始化失败/已有owner保护测试通过。三次数据重放与故障回归继续通过。物理设备后台行为、LED、EDA尾部仍未证实，不请求安装。证据：evidence/sensors/20261101-sync-owner.md。

2026-11-01 当前：sync-cleanup-20261101-r01 内部候选，297测试/154任务、双端release及lint PASS。修复传输通道关闭抛异常时跳过工作线程关闭及完成回调的缺口；两项清理均尝试，错误记录，完成状态不冒充成功。三次私有活动回放继续通过。未改变同步80秒预算；物理设备后台同步、LED与EDA尾部仍未证实，不请求安装。见evidence/sensors/20261101-sync-cleanup.md。

2026-11-01 当前：corpus-recovery-20261101-r01 内部候选，294测试/154任务及双端release/lint PASS。修复停止后先提交尾部再次撞上磁盘积压上限的收尾顺序：先排空已接收写盘，再保存缓冲尾部。阻塞磁盘直至真实上限触发后恢复，已提交缓冲行全部保存，异常原因保留，adapter仅关闭一次。三次真实活动CORE共191915行经受控速度重放，逐字段/数值/每源顺序全部匹配；不代表旧包未写盘尾部恢复或原始时序验证。证据：evidence/sensors/20261031-core-corpus-replay.md。候选构建需RAWWAVE_REPLAY_INPUT指向三份10-30活动包目录；缺私有输入不会算回放PASS。LED、EDA实机尾部及后台同步物理行为尚未证实，不请求用户安装/测试，不发布。

2026-10-31 当前：payload-admission-20261031-r01 内部候选，292测试/154任务、双端release及lint PASS。三星原始回调改为按有效载荷估算积压预算（16MiB/100000行/8192批），控制回调仍为256；同一FIFO保持数据及排空顺序。1000个待处理数据回调后结束活动，保存1000行并封存/归档校验通过。上一轮vendor-diagnostics候选289测试PASS，SDK诊断已独立异步。预算耗尽仍明确失败，不丢弃数据冒充成功。LED硬件停止、EDA实机尾部、后台同步物理设备行为仍未证实。不请求安装/真机测试、不发布GitHub。详见evidence/sensors/20261031-payload-admission.md和20261031-vendor-diagnostics.md。

后续重点：把真实三次活动的批次形状和回调节奏加入新入口的重放、增加原始字段逐值及边界故障检查；持续确认清理和同步生命周期。以下为历史阶段记录，其中256数据回调限制和同步磁盘等描述不代表当前实现。

2026-10-31 最新：persistence-evidence-20261031-r01 内部候选，287测试/154任务PASS。修复异步诊断写入失败连带抑制原始数据保存的问题；增加持久化已接收/已提交/未提交计数，封存前输出摘要并标明诊断故障。事件日志异步与原始数据共享有界队列。LED/SDK占用及通用回调上限尚未闭环，不请求安装或真机测试。见evidence/sensors/20261031-persistence-failure-isolation.md。

2026-10-31 最新：slow-disk-service-20261031-r01 内部候选，285测试/154任务PASS。实际采集服务+八源+阻塞磁盘联合测试4001样本数量与封存导出校验通过；无生产代码变更，无真机请求。见evidence/sensors/20261031-slow-disk-service.md。LED/硬件占用仍未确认。

2026-10-31 最新：persistence-budget-20261031-r01 内部候选，284测试/154任务PASS。八源20000样本生产服务路径、异步写失败、划掉任务回归通过；修复EDA先收后排的结束边界判定；持久化增加估算64MiB内存限额及嵌套快照。三次活动最终释放/断开/owner关闭证据已汇总，但LED仍未证实。详见evidence/sensors/20261031-follow-up.md、20261031-production-stress.md。非交付，不请求真机测试。

2026-10-31 更新：内部候选 disk-owner-20261031-r01 已完成独立数据写盘线程、阶段/封存保存屏障、异步错误与有界积压。278测试及154任务通过。慢盘1000批提交/恢复无缺行、写盘失败测试通过。端到端并发传感器压力回放尚未完成；256回调保护仍在，EDA尾部/LED未解决；不要求用户安装。详见 evidence/sensors/20261031-persistence-isolation.md。

2026-10-30 当前状态（优先于下方历史）：

- 已交付 vc60 / capture-handoff-20261030-r01：原始平台回调取消同线程重复排队；同步启动请求先于采集前台退出；合并导出清单限额16MB（总展开512MB原已存在）；单滚动测量UI。不是稳定性或LED验证通过。
- 10-30实测9c649160/fcda16dc因256回调队列超限提前结束；7bb96cde完成但EDA末尾未确认。三个导出包7364数据块哈希通过。实际三活动合并重放7367文件、83332617字节、allSealed=true。见evidence/sensors/20261030-three-session-audit.json、export-replay.log。
- 目前工作源码在vc60基础上增加onTaskRemoved取消采集、同步即时启动失败返回false、自动收集全部JUnit XML及总测试数。内部候选lifecycle-audit-20261030-r01，非交付版本，不要求用户安装。
- 未解决：采集线程与同步写盘耦合、256回调上限仍会触发、EDA未交付尾部、LED硬件占用根因。禁止宣称全部修复或硬件停止已证实。用户明确拒绝立即真机测试。
- 下一步：采集与持久化职责拆分，设计有界内存/磁盘故障处理及封存屏障；对批量回调和慢盘进行故障注入，确保原始样本不丢弃、不降频、不改值。补充task removal和同步异步失败回归测试。

历史记录（以下不代表当前完成状态）：

2026-10-28：code57 / eda-end-boundary-20261028-r01，EDA新增独立结束时间覆盖收尾，保留SDK刷新路径与失败保护；273测试、154任务、双端release/lint/安装包检查PASS，源码和APK哈希复核0差异。真实code55的273条EDA回放通过。DEVICE NOT_RUN；结束时间覆盖不等于SDK刷新确认或全程信号质量证明。见 evidence/sensors/20261028-eda-end-boundary.md。

2026-10-28：code56 / contact-preparation-20261028-r03，体成分5秒触键准备及实时进度修复；268测试、154任务、双端构建/lint/安装包检查通过。DEVICE NOT_RUN；EDA刷新无回调仍未解决，不能宣称全传感器验收通过。详见 evidence/sensors/20261028-contact-preparation.md。

# RawWave Rebuild 当前状态

2026-10-28：code55 / mf-bia-magnetic-20261028-r01，多频阻抗短测模式＋三轴磁场连续采集完成。267测试/154任务、双端release/lint/APK检查PASS；112源码输入及双APK哈希复核0差异。短测成功立即保存推进，不等待30秒；血氧首尾保持。DEVICE NOT_RUN；只需后续新功能集中验收，不为旧顺序单独测试。 详见evidence/sensors/20261028-mf-bia-magnetic.md。

2026-10-28：code55多频阻抗短测模式＋三轴磁场连续采集开发完成，mf-bia-magnetic-20261028-r01构建中；成功即结束，无30秒最低等待，仍保持血氧首尾。见evidence/sensors/20261028-mf-bia-magnetic.md。DEVICE NOT_RUN。

2026-10-28：code54 / posture-order-20261028-r01，血氧固定第一/最后，BIA等特殊姿势集中首尾，长期规则已记录。265测试/154任务、双端release/lint/APK检查PASS；111源码输入与双APK哈希0差异。新顺序DEVICE NOT_RUN。code53新数据BIA前重试成功/后失败，血氧后99%，气压排空确认，EDA尾部仍未知。 见evidence/sensors/20261028-code53-review.md。

2026-10-28：code53 / profile-keypad-20261028-r01：应用内数字按钮＋个人资料记忆。265测试/154构建任务及双端release/lint/APK检查PASS；111源码输入与双APK哈希复核一致。真机输入验证NOT_RUN。 见evidence/sensors/20261028-profile-keypad.md。

2026-10-28 最终：code51 / 0.7.0-bia-pressure，交付bia-pressure-20261028-r02。BIA资料/姿势/短测＋气压连续采集/独立排空完成，旧模板兼容。265测试/154任务、双端release/lint/签名/入口PASS；111源码输入及2APK哈希复核0差异。DEVICE NOT_RUN；EDA尾部未知仍未解决，血氧保留现状。详见evidence/sensors/20261028-code50-review-and-bia-pressure.md。仅交付r02。

2026-10-28：BIA＋气压开发中（用户已选择通用活动方案）。code51/0.7.0-bia-pressure；源码已接入资料/姿势/短测及压力连续采集。20261027-r01中断，缺少build.json，不能交付或标完整PASS；20261028-r01构建通过但清单漏计新增5项测试，已修正；下一步等bia-pressure-20261028-r02完整结果并核查哈希。新实测audit见evidence/sensors/20261027-code50-data-audit.json。

2026-10-27：code50 / 0.6.4-measurement-results，候选measurement-results-20261027-r01。血氧按尝试显示计算中/无有效结果/成功百分比，原始状态行不当作有效测量；EDA新增flush请求、返回及回调时序诊断，未宣称根治排空失败。256测试/154构建任务、双端release/lint/APK检查PASS；107源码输入及双APK哈希一致。DEVICE NOT_RUN。见evidence/sensors/20261027-measurement-results.md。

2026-10-27：code49三次实测已核查（10-26 22:54、10-27 08:21/15:59），1885归档文件/3 seal/行数/回调完整性通过；血氧7次尝试1次17秒成功99%、6次仅计算状态后超时。4段ECG通过当前连续样本标准；EDA三次排空未确认，心率有效率下降明显。不是整体验收PASS。见evidence/sensors/20261027-code49-data-review.md及data-audit.json；本轮未改生产代码。

2026-10-27：新增双端系统兼容长期要求，评估见SYSTEM_COMPATIBILITY.md；代码声明minSdk30/target36，有适配边界但跨系统验收未完成。本轮只更新要求/评估，未改源码或生成APK，code49仍为候选。

2026-10-26：code49 / 0.6.3-activity-management，候选activity-management-20261026-r01。主页活动仅显示名称；创建、更改、删除和采集项目统一在活动管理。253测试/154构建任务、双端release/lint和APK校验PASS；107源码输入及双APK哈希复核一致。DEVICE NOT_RUN；包含code48血氧流程。详见evidence/sensors/20261026-activity-management.md。

2026-10-26：code48 / 0.6.2-measurement-posture，最终候选posture-20261026-r02。活动前血氧准备→测量→恢复佩戴确认→ECG→CORE；活动后ECG→血氧准备→测量。有效血氧结果即结束，最长30秒；准备/恢复窗口80秒。253测试（实际Service 20项）及154构建任务、双端lint/签名/入口校验PASS，源码及双APK哈希复核一致。DEVICE NOT_RUN；EDA尾部及持续亮灯未宣称修复。详情evidence/sensors/20261026-measurement-posture.md。

2026-10-26：code47最终候选capture-edit-20261026-r01，活动编辑及ECG断流保护完成；80秒失败选择窗口强化防休眠并记录截止迟到，三星停止调用逐源记录。自动状态见build.json，真机未验证；持续亮灯/EDA尾部未知不能标已修复。详见evidence/sensors/20261026-capture-edit.md。

2026-10-25晚实测：code46已有设备证据，不再统称NOT_RUN；详见evidence/sensors/20261025-day-review.md。最近3次导出完整性和传输有证据，下午前血氧重测成功、陀螺仪约25Hz；但后ECG断流约4秒后失败、80秒review实际迟到约854秒，后血氧未执行。持续亮灯原因未明，EDA三次停止确认超时。不能标整体验收通过。本轮未改源码/未构建，优先资源停止/截止排查，其次完成模板编辑；不追加传感器。

code46最终结果：sensors-spo2-gyro-20261025-r02，248测试/154任务PASS，107源码输入/2APK哈希0差异；设备NOT_RUN。四项新功能开发已完成，只交付r02，不交付r01。用户无须重测全部旧功能，后续用新增传感器时合并观察和导出最近3次即可。

2026-10-25新功能：模板删除/保留历史、8项源独立可选（ECG/血氧短测＋6连续源）、血氧/陀螺仪接入、最近3次session合包导出。code46/sensors-spo2-gyro-20261025-r02，自动结果以build.json为准；真机NOT_RUN。先读evidence/sensors/20261025-spo2-gyro-recent.md。原始JSON保持，SpO₂是SDK处理结果、陀螺仪为Android平台值，来源明确。不宣称EDA底层问题已解决。

code45最终自动确认：selected-export-post-20261024-r02，241测试/154任务PASS，106源码输入与2APK哈希0差异，DEVICE NOT_RUN。本次交付选择导出与CORE超时后保留警告继续POST；不代表EDA底层不回确认已治愈。

2026-10-24最新：code44新活动/报告校验及传输有真实通过证据，但后置ECG因EDA停止确认超时未执行，不能标整轮通过。按用户确认实施code45超时保存后继续POST（警告保留，磁盘失败不继续）及按时间列表勾选导出。候选selected-export-post-20261024-r02结果看build.json，设备NOT_RUN。详见evidence/sensors/20261024-post-ecg-selected-export.md。

code44最终自动确认：contracts-capture-host-20261024-r02，237测试/154任务PASS，106源码输入和2APK独立复核0差异，真机NOT_RUN。只交付r02，两端覆盖安装后做一次合并验收。

2026-10-24最新：有限模块化开发清单已落地，候选code44/contracts-capture-host-20261024-r02；最终自动结果以dist内build.json为准。剩余是一次集中设备验收，不再追加内部接口拆分。实际采集宿主10项测试覆盖停止保存、销毁、队列溢出、磁盘失败、80秒空闲与绝对截止。详见evidence/sensors/20261024-capture-host.md。DEVICE NOT_RUN；不能把自动通过当作三星SDK/无线/系统退出通过。

当前最终候选contracts-summary-inbox-20261024-r01/code43：227测试/128任务PASS，104源码及2APK哈希一致；DEVICE NOT_RUN。摘要接收与实际协调器基本异常回归本轮已完成；剩余采集Service整体异常收尾验证，不要求用户安装中间包。

2026-10-24摘要接收收尾：SummaryStore v1及FileSummaryStore/SummaryReceiver已接入系统接收入口；10项实际协调器测试覆盖正常、丢READY、摘要失败后完整报告继续、双路径断流/过期/取消。候选contracts-summary-inbox-20261024-r01/code43，最终检查以build.json为准，DEVICE NOT_RUN。详见evidence/sync/20261024-summary-inbox.md。主要剩余采集Service整体验证，用户暂不安装或重复测试。

2026-10-24最新覆盖：已实施SensorPort v2/Samsung adapter-3连续源SDK flush、逐源异步交接、回调复制/停止屏障及保存后推进。ECG无SDK flush，单独保留DELIVERED_ONLY限制。结束/跳过/超时/取消均接入；5秒收尾等待失败标异常而非进入下一阶段。contracts-drain-20261024-r02/code42：216测试/128任务PASS，101源码哈希一致，双端构建/静态/APK检查通过，DEVICE NOT_RUN。见evidence/sensors/20261024-capture-drain.md。全模块化仍未完成，用户要求收尾后集中真机测试，不安装中间包。

2026-10-23停止交接缺口优先：官方五种连续源支持SDK flush，ECG不支持同接口；当前仅flush应用buffers、忽略onFlushCompleted，不能证明SDK尾部完整。见CAPTURE_DRAIN_CONTRACT.md。需先实施异步排空/持久确认再推进阶段；本轮未改生产代码，未生成新候选。用户要求收尾完再统一真机测，立即测试计划撤回。

2026-10-23进度校正：进入code41合并设备验收节点，具体3项与剩余开发/验证见ACCEPTANCE_CHECKPOINT.md。先收实际反馈再扩展重构，禁止仅凭205测试声明全模块化完成。当前源码/候选哈希一致，设备结果未返回。

contracts-capture-owner-20261023-r01/code41最终DEV PASS：205项测试、128任务、99源码输入及2APK哈希一致。DEVICE NOT_RUN。采集回调控制权已按Run隔离，整个采集收尾执行及异常路径仍需继续检查。

2026-10-23采集回调：CaptureRuns接入源启动/失败/停止，旧失败不控制新Run；候选contracts-capture-owner-20261023-r01/code41结果见build.json，DEVICE NOT_RUN。code40前步已202测试/128任务通过。详见evidence/sensors/20261023-capture-owner.md。

2026-10-23采集阶段：超时、ECG结束、核心活动结束判断抽到CapturePhasePolicy并实际接入Service；code40/contracts-phase-20261023-r01结果见build.json，DEVICE NOT_RUN。硬件/缓冲/收尾执行仍在Service，不能称采集模块化全完成。详见evidence/sensors/20261023-capture-phase.md。

contracts-report-20261023-r02/code39最终DEV PASS：198测试（含4实际协调器）、128任务、97源码输入及2APK哈希一致。DEVICE NOT_RUN。报告缓存包及摘要成功/失败编排已通过电脑端验证，采集阶段职责和异常/平台边界尚待收尾。

2026-10-23报告编排恢复：contracts-report-20261023-r02/code39，增加真实完整报告协调器的摘要成功/失败后继续传输测试；r01中断不算PASS。详见evidence/sync/20261023-report-host.md。最终结果见build.json，DEVICE NOT_RUN。

contracts-summary-20261023-r02/code38最终DEV PASS：196测试、128任务、97源码输入及2APK哈希一致。r01 lint失败后已修正。DEVICE NOT_RUN。摘要发送已迁入通信契约，整体模块化仍按MODULE_HANDOFFS顶部清单收尾。

2026-10-23摘要发送迁入统一TransportPort：contracts-summary-20261023-r01/code38，结果见build.json，DEVICE NOT_RUN。完整有限收尾清单见MODULE_HANDOFFS.md顶部。已有主要契约不等于全功能验收；下一步采集阶段职责与报告/异常联动，不重复增加空接口。

contracts-host-20261023-r01/code37最终DEV PASS：196项测试（含2实际协调器）、128任务、97源码输入与2APK哈希一致。DEVICE NOT_RUN。活动全握手电脑端已覆盖正常和首READY丢失；报告/Service/真实无线仍待验证。

2026-10-23实际活动协调器：新增电脑端宿主运行两个生产ActivityTransferWindow实例，正常/首READY丢失全握手及原字节/回执/单次关闭验证。contracts-host-20261023-r01/code37自动结果见build.json，DEVICE NOT_RUN。默认装配与协议不变；完整报告和Service/真实无线尚未覆盖。详见evidence/sync/20261023-coordinator-host.md。

contracts-ready-20261023-r01/code36最终DEV PASS：194项测试、102任务、96源码输入与2APK哈希一致。DEVICE NOT_RUN。修补活动首次READY丢失后的恢复，不代表最终确认超时已解决。

2026-10-23活动握手：补首次READY丢失后的同窗口再次回应，Channel后/过期仍拒绝，不续预算、不改数据。contracts-ready-20261023-r01/code36结果见build.json，DEVICE NOT_RUN。详见evidence/sync/20261023-activity-ready.md；完整协调器测试宿主仍未完成。

contracts-integration-20261022-r01最终DEV PASS：193项测试、102任务、96源码输入与2APK哈希一致。仅测试增量，生产版本仍35；DEVICE NOT_RUN。不等于真实Android协调器/无线链路端到端通过。

2026-10-22联动增量：真实磁盘存储、准入及确认交换组合，新增6测试（每项覆盖活动/报告）。生产未改，仍code35；contracts-integration-20261022-r01结果见build.json。DEVICE NOT_RUN，无需安装仅测试增量包。范围与未覆盖项见evidence/sync/20261022-disk-integration.md。

contracts-lease-20261022-r01/code35最终DEV PASS：187项测试、102任务、96源码输入及2APK哈希一致。DEVICE NOT_RUN；不要求逐内部候选安装。下一步仍需完整装配验证，不能以租约单元测试替代Android生命周期或确认超时闭环。

2026-10-22：SyncGuard执行保护加入LeaseScope，具体Lease限定关闭权，启动与收尾串行，增加6项JVM反例；code35/contracts-lease-20261022-r01自动结果见build.json，DEVICE NOT_RUN。协议、数据和截止常量未改。详见evidence/sync/20261022-lease-scope.md；Android实际调度及全协调器验收尚未完成。

2026-10-22：补双端活动同步宿主状态所有权检查，旧任务不再无条件发布/清空新任务状态。候选contracts-owner-20261022-r01/code34，最终自动结果见build.json，DEVICE NOT_RUN。无新增接口/传感器/数据协议变化。详见evidence/sync/20261022-owner-status.md；底层SyncGuard并发释放及完整协调器演练仍待完成。

contracts-commit-20261022-r01/code33最终DEV PASS：181项测试、102任务、95源码输入及2APK哈希一致；DEVICE NOT_RUN。最终确认交换已有生产代码共用与双端替换信号演练，不等于整套Android协调器端到端完成。

2026-10-22确认交接：CommitExchange v1实际接入双路径，新增7项确认联动/失败/版本测试；7契约号进入日志与诊断包。code33/contracts-commit-20261022-r01自动结果见build.json，DEVICE NOT_RUN。未更改采集、无线格式、截止常量、SyncGuard；完整协调器端到端/系统暂停迟报仍未解决。详见evidence/sync/20261022-commit-exchange.md。

contracts-transport-20261022-r01/code32最终DEV PASS：174测试、102任务及双端候选检查；93源码输入和2APK哈希0差异。DEVICE NOT_RUN。内部候选不要求立即安装，下一步完整握手替换演练与运行截止/版本矩阵。

2026-10-22通信接口：TransportPort/GoogleTransport接入两条完整文件传输，新增6项异步/截止测试，TransportAwait不接受已过期异步结果。contracts-transport-20261022-r01/code32自动结果看build.json，DEVICE NOT_RUN。摘要传输仍独立旧路径；真实协调器全握手演练、确认超时根因和模块版本矩阵未完成。详细见evidence/sync/20261022-transport-handoff.md。

contracts-resource-20261021-r01/code31最终DEV PASS：168测试、102任务、90源码输入与2APK哈希核对一致；DEVICE NOT_RUN。本步通信资源所有权已接入，完整可替换通信接口/确认超时根因尚未完成。不要求用户现在安装中间候选。

2026-10-21资源契约：ResourceHandoff/TransferResourceOwner双传输接入，新增4项所有权/失败/并发/重入反例，细分回执阶段；contracts-resource-20261021-r01/code31自动状态以build.json为准，DEVICE NOT_RUN。详细见evidence/sync/20261021-resource-handoff.md。新传感器未增加，协议未变；确认超时根因未闭环，Google通信整体替换接口仍未完成。

contracts-sensor-20261021-r03最终DEV PASS：164项测试、102构建任务及双端候选检查通过；88源码输入/2APK哈希0差异。code30 DEVICE NOT_RUN。保留为内部候选，不要求用户本轮安装；下一阶段针对code29真实确认超时完成通信/资源交接。

2026-10-21：续接SensorPort v1采集接口、不可变数据交接和唯一装配点；候选contracts-sensor-20261021-r03/code30，以build.json为最终自动证据，DEVICE NOT_RUN。详见evidence/sensors/20261021-sensor-handoff.md。最新code29辅助实机：3活动包644载荷及手机22载荷通过；POST中断80秒停止有证据；15:18完整活动各源有数据，但同步已提交后最终确认失败且超时迟报。不能标全部通过。暂不让用户安装本步中间候选，后续通信契约与失败反例合并验证。

ecg-idle-20261020-r01 / code29最终自动结果：158项测试、102任务和双端候选检查PASS；84源码输入及2APK哈希一致。未接触ECG80秒停止保存/关页且不自动同步的新行为DEVICE NOT_RUN。后续只需一次定向验证，不重新要求全套传输/导出/采集测试。

2026-10-20最新：code28的22活动文件名与plan时间逐包一致，9468载荷通过；正常新活动传输成功。无人接触ECG测试暴露80+80秒串联等待。已实现code29等待80秒直接停止/保存/关页且不启动自动同步，日志CAPTURE_IDLE_STOP_COMPLETED。候选ecg-idle-20261020-r01，自动结果看build.json，DEVICE NOT_RUN。详见evidence/sync/20261020-ecg-idle-stop.md。减少用户测试频次：内部拆分按阶段合并，当前只修复此生命周期缺陷。

最终交付contracts-scope-20261020-r02 / code28：156项自动测试、102构建任务和双端候选检查通过；83源码输入及2APK哈希一致。r01因新命名API兼容性复查被r02取代，不给用户测试中间包。真机NOT_RUN，整体模块化尚未完成。

2026-10-20最新：contracts-scope-20261020-r02 / code28同时实现活动导出文件名含开始时间（当前手机时区/偏移、UUID；多会话最早时间标数量）与SyncStopScope停止权限。原字节/时间筛选/合包均未改。code26新活动首次自动同步被旧手机页面IDLE误关，手动重试成功；不能冒充code27验证。详见evidence/sync/20261020-export-time-stop-scope.md。新候选自动结果看build.json，真机未测，模块化仍未全部完成。

contracts-attempt-20261020-r01最终自动检查：151项测试、102任务及双端候选检查PASS，81源码输入和2APK哈希一致，code27 DEVICE NOT_RUN。下一步Google通信适配/回调与流资源所有权；本轮导出逻辑未改。

2026-10-20最新：code26回传19活动及62bc4ff7报告共9055载荷校验一致，新活动CAPTURE_FINISHED自动同步成功，摘要与完整报告成功。已推进TransferAttempt v1双路径状态准入契约；候选contracts-attempt-20261020-r01/code27，自动结果以dist/build.json为准，新候选DEVICE NOT_RUN。详见evidence/sync/20261020-attempt-contract.md。导出文件随活动增多的体验问题已记录，按用户要求暂不开发；通信适配/资源所有权及采集边界仍待实施。

contracts-send-20261020-r01最终结果：143项测试/102任务及双端release、lint、DEX、签名检查PASS；78源码输入与2APK哈希核对一致。code26真机未测，不能沿用code25真机结论。下一步通信适配与异步状态契约。

2026-10-20最新：code25用户回传18活动+手机报告，8882载荷长度/哈希全过；最新活动重新打开补同步与统一摘要/报告有保存确认。已接入SendStore v1双发送存储边界，code26/contracts-send-20261020-r01自动结果以dist/build.json为准，DEVICE NOT_RUN。详见evidence/sync/20261020-send-contract.md。接收边界与发送边界已落地，不等于全部模块化完成；后续通信适配/异步协议、采集适配和模块版本矩阵仍待完成。

contracts-isolation-20261020-r01最终验证：138项测试、102构建任务与双端候选校验PASS；76源码输入/2APK哈希一致，stage=RECEIVE_CONTRACT_ISOLATION。仅最新code25需要真机回归。

2026-10-20最新候选：contracts-isolation-20261020-r01 / 0.5.8-receive-isolation / code25，替代未测试code24。接收深校验内容失败隔离原包与原因；导出同时处理已有坏包，正常包继续且提示隔离数量。阶段标签已修正，校验回调活动专用含义已明确。[审查修正](evidence/sync/20261020-receive-isolation.md)。新行为真机NOT_RUN；无需重复测试旧候选。

2026-10-20交付复核：contracts-receive-20261019-r01 / code24已完成134项测试、102任务与双端候选校验，75源码输入/2APK哈希一致。新接收边界真机NOT_RUN。按USER_TEST_CONTRACTS.md对问题报告和一次短活动做针对性接收回归；确认后继续发送存储与通信适配契约，不能宣称全项目模块化已完成。

## 2026-10-19 最新：模块交接契约第一步

- code23统一报告真机通过：5e9bb453报告22文件校验正常，摘要7858fcbd保存确认后完整报告879eaf75提交确认。原始数据仅有历史双端成功证据，本轮无新活动包。
- 已保留code23源码基线ZIP；ReceiveStore v1实际接入报告及原始数据的接收落盘，返回成功必须包含回读/活动深校验/回执/目录同步。候选contracts-receive-20261019-r01 / 0.5.7-receive-contract / code24，新边界真机NOT_RUN。
- [全链条契约与未完成项](MODULE_HANDOFFS.md)、[本轮证据](evidence/sync/20261019-receive-contract.md)。接收交接只是第一步，发送存储/通信/采集适配完整拆分仍待实施。

## 以下为此前状态

## 2026-10-19 最新：统一问题报告入口

- 用户明确目的：小摘要应由底层自动优先发送，离线诊断只负责查看/拍照。已去掉独立摘要按钮，报告发送内部先摘要再完整包；摘要失败不阻止完整包，成功也不冒充完整报告成功。
- 新候选report-flow-20261019-r01 / 0.5.6-report-flow / code23；130项测试及双端候选验证PASS，新编排真机NOT_RUN。详见[实现与回传核验](evidence/sync/20261019-report-flow.md)及[构建结果](dist/report-flow-20261019-r01/build.json)。
- code22已有用户确认圆屏可滑动阅读；bdc5d647报告22个载荷校验通过，小摘要3706e078双端保存确认、完整包5d67b263手机提交确认均成功。不要继续让用户重复完整传感器或圆屏检查。

## 以下为此前状态

## 2026-10-19 最新：修复诊断正文挤压与报告握手缺口

- code21照片只有页码与按钮，详情切换可用，但正文被固定按钮挤压。本次小摘要实际发送结果未知。
- 新候选diagnostic-visible-20261019-r01 / 0.5.5-diagnostic-visible / code22：正文与按钮整体滚动，小摘要显示发送阶段；完整诊断ZIP复用有界HELLO，允许手机稍晚进入接收。
- 130项测试及102个重跑构建任务、双端候选检查PASS。[实现记录](evidence/sync/20261019-diagnostic-visible.md)，[构建证据](dist/diagnostic-visible-20261019-r01/build.json)。真机NOT_RUN，需先验证上述问题，再继续模块交接契约。

## 以下为此前状态
更新：2026-10-19 反馈显示层收尾已构建。详见 [诊断快速页记录](evidence/sync/20261019-diagnostic-quick-view.md)。

## 最新结论：反馈机制进入最小真机显示确认

- 当前候选 **feedback-quick-20261019-r01 / 0.5.4-diagnostic-quick / versionCode 21**。默认诊断固定为5页关键摘要，可切换“完整详情”；重复的批量回调静默/恢复只在显示层按传感器汇总，原始日志和ZIP不删除、不改写。
- 快速页与完整页读取同一只读快照；独立摘要身份不会覆盖活动传输windowId。弹窗、状态栏和导航区改为全黑，减少截图露出后方页面。
- 130项JVM测试、102个完整构建任务、双端release/lint/权限/精确入口/DEX/签名/哈希检查PASS。新界面真机 **NOT_RUN**；r03已经真机通过的活动同步、完整报告和独立摘要仍是底层基线。
- [手表APK](dist/feedback-quick-20261019-r01/RawWave-wear-feedback-quick-20261019-r01.apk)，SHA-256：`C7232CE19A60685E82C30A8564B56BB7271E2E814590391FCC55C5AAFFB3E55C`。
- [手机APK](dist/feedback-quick-20261019-r01/RawWave-mobile-feedback-quick-20261019-r01.apk)，SHA-256：`BB17DD1A8C4E989B65D2BB1327CAA9FFCAF04C6E346A97E1BDAC539E488A4235`。
- 下一步只需双端打开诊断页，确认默认5页、完整详情可切换、手表圆屏与黑色边缘；无需新活动或重新传输。通过后开始模块交接契约，不增加传感器。旧生命周期/ECG双等待/成功5秒倒计时本步未改。

## 以下为快速页收尾之前的状态

更新：2026-10-19 真机回传已核验。两次活动自动同步、完整问题报告、独立小摘要保存回执均有双端证据；完整五传感器活动归档/数据完整性通过。详见 [真机与新活动核验](evidence/sync/20261019-diagnostic-device-and-new-activity.md)。

## 最新结论：传输反馈链已走通，拍照页仍需一次轻量收尾

- `diagnostic-20261019-r03` 真机确认：活动97d307fd（8秒用户中断）和80c6adbd（约36分41秒完整五传感器）均由CAPTURE_FINISHED自动同步，手机FILE_COMMITTED、手表REMOTE_COMMIT_CONFIRMED及导出SHA一致。
- 完整诊断包b2c75702与receipt匹配；独立摘要080c6b8a在手机持久保存并匹配回执。完整包与小摘要均PASS一次，但底层完全断链仍只能离线拍照。
- 新完整活动1298个载荷清单全匹配：加速度53407、PPG53200、心率2134、EDA2132、皮温35、前ECG18410、后ECG15330。前后连续30秒均通过；详细状态不作医学解释。
- 手机诊断页可读但有46页、概览省略924事件；SDK批量回调造成重复silence/resumed提示，是明显反馈噪声。手机previous-process仍为RUNNING，是退出证据不完整，不能单独断言崩溃原因。
- 建议进入模块化之前最后收尾：拍照用优先摘要约5页，完整40事件详情保留第二层，并聚合重复数据静默提示。当前未改代码；手表圆屏和断连15秒仍NOT_RUN。

## 以下为本次真机回传之前的状态

更新：2026-10-19 本轮审查后续。采纳关键事件/字段补全、安全渲染、上次运行状态、日志锁说明及圆屏边距修正；保留日志原始顺序而非UTC重排。本地与网络分别限长。

## 最新接续点：诊断审查修正 + 独立小摘要

- 新增手表诊断页手动“优先发送小摘要（15秒）”：不等待活动大文件、不抢停采集/健康传输、无自动重试；手机新增一个精确消息入口，保存回读完成才回UUID+SHA确认。既有Data Layer完全故障仍无法传输。
- 手机问题报告独立附received-summary/latest.json，明确最近一条缓存/部分证据；不能当作健康数据同步成功或完整手表报告。
- 版本0.5.3-diagnostic-summary / code20。最终候选 **diagnostic-20261019-r03**：128项测试、102构建任务、双端release/lint/权限/精确入口/DEX/签名/哈希检查PASS。真机NOT_RUN。r01失败记录保留、r02为中间候选，不安装旧候选；以[最终构建证据](dist/diagnostic-20261019-r03/build.json)为准。
- [审查逐项决定、通道契约和最小验收](evidence/sync/20261019-feedback-review-and-summary.md)。旧退出/ECG双80秒问题未改；尚未开始全面模块重构或新增传感器。

## 以下为本轮之前的状态

更新：2026-10-19。用户已授权先完善反馈机制，不增加传感器；下方10月18日“不改代码”仅是当时任务范围。

## 反馈第一步：离线取证入口（自动验证通过，真机未测）

- 新增两端只读诊断弹窗；手表首页、采集中、同步页均可进入，不跳转 Activity、不启动新传输、不增加常驻服务。快照显示版本/系统、最新40条相关事件、操作关联ID、失败原文、预算和已有字节证据；保留旧故障和轮转/损坏提示，不把开始步骤当作完成。
- 手机导出及手表新建诊断包包含同源 diagnostic-feedback.txt；修正报告中过时的活动“未接入同步”表述。重发的旧诊断包仍保持原字节，不冒充新快照。
- 明确 Java UTF-8 编译，新增5项诊断投影测试。候选 **feedback-20261019-r02 / 0.5.2-feedback / code19**，121项测试、双端release/lint、权限/DEX/签名/哈希检查通过；真机NOT_RUN。r01为中间验证候选，不建议安装。详见 [本步记录](evidence/sync/20261019-feedback-step1.md) 与 [构建证据](dist/feedback-20261019-r02/build.json)。
- 尚未完成：独立小诊断消息的优先级、自动阶段汇总/持久成功摘要、逐模块版本矩阵；既有诊断ZIP通道仍依赖同一Data Layer。旧空闲截止/ECG双重等待/5秒退出交互本步未改。

## 以下为10月18日数据核验记录

更新：2026-10-18。本轮用户明确暂不改代码，仅数据核验、故障定位及反馈评估。

## 当前接续点：传感器归档传输修复有效；生命周期问题待处理

- 当前实际候选 **archivefix-20261017-r01 / 0.5.1-sensor-archive-fix / versionCode 18**。上轮116项测试与双端候选检查通过；本轮68项源码哈希一致，未重新构建。
- 新导出13包：21次活动、5455个载荷条目长度/哈希及封存清单核验通过；已取回10月17日四次滞留五传感器活动和今天的新活动。新增归档兼容修复已有真机成功证据，不代表所有自动同步/退出场景通过。
- 两次无人操作ECG约81秒停止传感器，随后再84/90秒才结束会话；不满足“中断起80秒内全部结束”。结束时手表后台启动同步被系统拒绝，数据后来已补传。
- 手机旧空闲截止取消正在接收的数据、重开时旧页面先退出、OPEN_IDLE_AUTO手机成功不退出均有证据/代码对应，详见本轮分析。仍待授权实施，不继续扩功能或擅自重做退出。
- [本轮数据、故障与完整需求评估](evidence/sync/20261018-data-and-feedback-review.md)。包括5秒成功倒计时、上次同步状态、模板编辑删除、独立诊断、两类可视化、交接契约、版本矩阵及官方健康逻辑日导出。

## 以下为此前交接状态（由上方最新记录取代）

更新：2026-10-16。用户负责真机测试，开发者提供源码/自动化/测试包。

2026-10-17 交接复核：68项源码输入及2个APK与当前候选构建清单一致，无变更；新增传感器组合仍待真机数据。新对话先读 [交接入口](HANDOFF.md)。

## 当前接续点：新增连续传感器候选等待一次真机测试

- 当前候选：**sensors-20261016-r01 / 0.5.0-continuous-sensors / versionCode 17**。双端覆盖安装，不卸载、不清数据。
- 创建活动时可选择活动中的心率、加速度、原始 PPG、皮肤温度、原始 EDA；旧活动继续按心率+加速度运行。某项失败只记录该项，其他项继续。
- 原始 PPG 保存三星 SDK 的绿光、红光、红外及各通道状态；EDA 保存 SDK 的皮肤电导及状态；加速度仍为原始整数轴值。皮肤温度和心率是 SDK 处理结果，文件中明确标注，不冒充原始波形或体温。
- 113 项 JVM 测试、102 个完整构建任务、双端 release/lint、权限、DEX、签名和哈希检查通过。DEVICE：新增传感器组合尚未运行。
- 与上一真机通过候选逐文件对比：活动传输、手机接收、双端握手和导出模块均未修改；同步结论仍以旧候选的真机证据为基线，新候选仍需一次端到端回归。
- [手表 APK](dist/sensors-20261016-r01/RawWave-wear-sensors-20261016-r01.apk)，SHA-256：`E843C24CF3304AE3EA4B6AF4A701232D205E10C72FE53481D865BA4A70ABF505`。
- [手机 APK](dist/sensors-20261016-r01/RawWave-mobile-sensors-20261016-r01.apk)，SHA-256：`71B7CC01E2A2DABDAC0E0D55907BEFA3852C25355E56CDF173BE135D737EF602`。
- [一次真机测试](USER_TEST_SENSORS.md)、[实现与证据](evidence/sensors/20261016-continuous-sensor-modules.md)、[构建证据](dist/sensors-20261016-r01/build.json)。
- 下一步：只做一次约 2 分钟的五传感器组合活动并导出原始数据；通过后再规划前后短测项目（如血氧/BIA），不把短测强行放进长时 CORE。

## 已被传感器候选取代：活动结束自动同步已真机通过

- 真机基线候选：**syncfix-20261016-r01 / 0.4.4-sync-handshake / versionCode 16**。已被顶部候选取代，但其真机结论仍是当前同步基线。
- 10 月 16 日真机报告确认：手表以 `CAPTURE_FINISHED` 主动唤醒手机；手机在收到 HELLO 后进入接收，完成整包和内部逐文件校验、持久提交；手表回传确认，手机记录 `SUCCESS`、`uiExitRequested=true`、`automaticRetry=false`，随后释放同步锁并停止工作。用户同时观察到手表自动退出。
- 手机在同步前一秒刚因 80 秒空闲完成收尾，之后仍由手表单向唤醒并成功接收。因此本次不是依赖用户先在手机点“等待接收”，也不是打开后的 `OPEN_IDLE_AUTO` 补同步。
- 新活动包 `2bb60a16-...` 共 168 个活动文件、8,835,651 字节，逐文件长度和 SHA-256 复算失败数为 0。前 ECG 第二段取得连续 15,000 点/29,947 ms；后 ECG 第三段取得连续 15,000 点/29,953 ms；无效片段保留。活动中保存 6,300 行加速度和 252 行心率。
- ECG 与加速度是三星 SDK 开放的 raw 数据；心率/IBI 是三星 SDK 给出的处理结果，不是原始 PPG。加速度仍采用 SDK 批量回调，停止时可能存在尚未交付的缓冲区尾部，本候选没有虚造或补齐。
- DEV：104 项 JVM 测试通过；102 个任务全部重跑；双端 release/lint、最终清单、DEX 接线、签名、来源及 APK 哈希检查通过。DEVICE：活动结束自动同步、手机持久提交和成功后收尾 `PASS`。
- [手表 APK](dist/syncfix-20261016-r01/RawWave-wear-syncfix-20261016-r01.apk)，SHA-256：`6C007350F537E67E671232A6BA25D498A07CEE64D394A5FC68A1068B8EE99D6C`。
- [手机 APK](dist/syncfix-20261016-r01/RawWave-mobile-syncfix-20261016-r01.apk)，SHA-256：`E84D834C327F55AC2837E0DE618FE5F6ADD2A3C4B2B004959EF5C179099930E9`。
- [真机通过记录](evidence/sync/20261016-syncfix-device-pass.md)、[本轮修复记录](evidence/sync/20261016-sync-handshake-fix.md)、[构建证据](dist/syncfix-20261016-r01/build.json)。
- 下一步：单独研究并验证加速度停止时的三星 SDK `flush()`/最终回调边界；同步模块保持冻结，避免已通过功能被无关改动破坏。

## 已被握手修复取代：flowfix-20261016-r02

- 历史候选：**flowfix-20261016-r02 / 0.4.3-capture-priority / versionCode 15**。它证明数据最终能够完整传输，但活动结束第一轮被取消，同一进程又进行了第二轮，不能作为活动结束自动同步通过的版本。
- 对应事实与时间线见 [设备报告复核](evidence/sync/20261016-r02-device-evidence.md)。

## 已被当前流程修复取代：autosync-20261015-r01

- 历史候选：**autosync-20261015-r01 / 0.4.2-auto-sync / versionCode 14**。已被顶部候选取代，仅保留为本次真机证据对应版本。
- 用户提供的活动导出包已完整收到：6 个活动、932 个活动文件；逐文件长度与 SHA-256 全部通过，0 项错误。五个活动正常完成，一个活动明确记录为用户中断且保留已有 ECG。
- 最新两个完整活动的活动前、活动后 ECG 均分别取得 15,000 个连续样本，约 30 秒；无效/重试片段仍原样保留。ECG 和加速度是三星 SDK 开放的直接值；心率是 SDK 计算后的心率/状态/IBI，不是原始 PPG 波形。
- 用户说明本轮由手表手动开始同步。报告证明手机从接收、整包与逐文件校验、持久提交、手表确认到手机导出全部成功。因此手动路径 DEVICE PASS；自动路径不能据此判为通过。
- 旧版自动路径存在两个明确问题：任意触摸会永久删掉三秒回调；离开再返回页面也可能不再安排。新版用独立 `AutoSyncGate` 修复，并记录 `OPEN_IDLE_AUTO`、`USER_MANUAL`、`CAPTURE_FINISHED` 三种来源。
- DEV：94 项 JVM 测试通过；102 个任务全部重跑；双端 release/lint、最终清单、DEX 接线、签名、来源及 APK 哈希检查通过。DEVICE：手动传输/导出已通过，自动触发仍需用户按 USER_TEST_SYNC.md 复测。
- [手表 APK](dist/autosync-20261015-r01/RawWave-wear-autosync-20261015-r01.apk)，SHA-256：`A9142F2CF8A7F39F82003F8276D17D36A87F47A9D66C70B3D0E50A62994F6B5C`。
- [手机 APK](dist/autosync-20261015-r01/RawWave-mobile-autosync-20261015-r01.apk)，SHA-256：`F8F6FC7E6EBEE2F89AC6D3EC4F4ACDAF8F6FF30D4289E66669F71D5F1D9A6102`。
- [简明测试](USER_TEST_SYNC.md)、[协议](ACTIVITY_SYNC_PROTOCOL.md)、[实物核验与自动同步修复](evidence/sync/20261015-activity-data-and-auto-sync.md)、[构建证据](dist/autosync-20261015-r01/build.json)。
- 下一步：只验证自动触发一次。通过后再规划新增活动前后数据和活动中传感器；本轮没有提前增加传感器。
## 已被优先级修复取代：活动原始数据自动同步与手机导出

- 历史候选：**sync-20261015-r02 / 0.4.0-activity-sync / versionCode 12**。已被顶部优先级修复候选取代，仅保留为问题复现基线。
- 三项采集内容未改：活动前/后 ECG，活动中心率和加速度。活动封存后手表自动制作原字节归档并单向唤醒手机；无需先在手机点等待。
- 手机必须完成整包哈希、磁盘回读、内部逐文件哈希及持久验证回执，之后才发 COMMITTED。手表收到后重新校验本地包、保存活动确认，再完成双端握手。
- 同步最多 80 秒，成功立即收尾，失败不自动重试且手表数据保留。下次打开手表，用户 3 秒内选择活动则采集优先；无操作才同步积压数据。
- 手机新增“导出活动原始数据”，与“导出问题报告”分开。导出当前收到的全部活动包，流式复制并回读校验，不使用问题报告的 60 秒限制。
- 已封存活动完整打包；崩溃/掉电留下的未封存活动打包已原子保存的分块并标记尾部未知，`.part` 尾部不冒充有效数据。
- DEV：86 项 JVM 测试通过；102 个任务全部重跑；双端 release/lint、最终清单、DEX、签名、来源及 APK 哈希检查通过。DEVICE：**NOT_RUN**，需要用户按 USER_TEST_SYNC.md 真机测试。
- [手表 APK](dist/sync-20261015-r02/RawWave-wear-sync-20261015-r02.apk)，SHA-256：`16E79A8CD080B22442E778548B783A002A4EA5F423A4492D8BE684D764C992D5`。
- [手机 APK](dist/sync-20261015-r02/RawWave-mobile-sync-20261015-r02.apk)，SHA-256：`97144449F661B6115C182D5A290FCFC79DAA7BF6E62543120867DD06F063838B`。
- [简明测试](USER_TEST_SYNC.md)、[协议](ACTIVITY_SYNC_PROTOCOL.md)、[实现证据](evidence/sync/20261015-raw-activity-sync.md)、[构建证据](dist/sync-20261015-r02/build.json)。
- 尚未完成：真机原始数据包核验、按日期/逻辑日导出、最近 30 次清理、新增 PPG/EDA/温度/血氧/BIA 等传感器。

## 已被同步候选择代：ECG 无限重试与三星批次时间真机复测

- 历史交付 **capture-20261015-r13 / 0.3.3-ecg-retry / versionCode 11**，已被顶部同步候选取代。
- 用户报告 `rawwave-phone-0766...zip` 确认 r11 的根因：三星一次 ECG 回调中的 5/10 个原始点可以共用同一毫秒时间戳；r11 错把同批次的相等时间戳判为“不递增”，导致 39120 个原始点全部无法形成成功连续段。报告没有发现“两次后禁止重试”的硬编码；会话最终记录为用户中断。
- 已改成按“三星回调批次”判断连续性：同一批次共用时间戳合法；批次之间用本地回调序号和批次锚点时间检查明显缺口。原始时间戳仍原样保存，不给每个点虚造时间。
- ECG 重试次数不设上限。接触或连续性中断一次记一次失败并自动等待下一次；累计失败两次后显示“跳过 ECG”，用户既可继续无限次尝试，也可跳过并继续本次活动。跳过只跳过当前阶段 ECG，不丢弃已采集片段。
- DEV：75 项 JVM 测试通过，其中 10 项 ECG 专项测试覆盖三星批次共用时间戳、连续 30 秒、断触、批次缺口、无限重试和两次后跳过；102 个构建任务全部重跑，双端完整 lint、DEX、入口、签名和哈希检查通过。DEVICE：r13 未运行，需用户真机复测。
- [手表 APK](dist/capture-20261015-r13/RawWave-wear-capture-20261015-r13.apk)，SHA256：A1166BC88B525F769290235D3DCD57ADC11F8F3449FFC662E190C24BB1E3E473。
- [手机 APK](dist/capture-20261015-r13/RawWave-mobile-capture-20261015-r13.apk)，SHA256：9BD79210589A7FA62F2D7921F1036EA6084856E0D67FD2C6C535E275882D6374。
- [简明测试步骤](USER_TEST_CAPTURE.md)、[报告分析与修复证据](evidence/capture/20261015-ecg-retry.md)、[构建证据](dist/capture-20261015-r13/build.json)。双端覆盖安装，不卸载、不清数据。
- 当时尚未实现活动原始数据同步与手机导出；现已由顶部同步候选实现。

## 已被取代的 r11 接续记录

- 当前交付 **capture-20261015-r11 / 0.3.2-continuous-ecg / versionCode 10**。r10 已被本候选取代。
- “原始数据”按本项目规则定义为：**三星 Health Sensor SDK 向第三方开放并回调的最原始值**。原样保存 SDK 数值、SDK 时间戳和回调批次位置，不滤波、不插值、不补点、不换算；质量判断另记，绝不改写原始行。
- ECG 只有在接触有效、数值存在、时间戳连续、未超量程，并同时达到至少 15000 个样本及约 30 秒 SDK 时间覆盖时才成功。中途断触、数据间断、时间倒退或超量程会立即作废当前连续段并从头开始；此前原始片段仍保留并标明原因。
- ECG 按三星回调结构读取：每点保留 ECG 值；接触状态、序号和量程等批次信息只从官方规定的批次位置读取，不把缺少这些字段的普通点误判为断触。
- 心率保存 SDK 给出的心率、状态和 IBI；这是三星开放的心率结果，**不是原始 PPG 波形**。加速度保存 SDK 开放的原始整数轴值，不自行换算。
- DEV：72 项 JVM 测试通过；102 个构建任务全部重新执行；双端完整 lint、最终 APK 内容、入口、签名与新增质量模块检查通过。DEVICE：本候选未运行，连续 ECG 仍需用户真机验证。
- [手表 APK](dist/capture-20261015-r11/RawWave-wear-capture-20261015-r11.apk)，SHA256：70A611A1A3E16CBAFAB9DA9D4A191C3AAB96A7EC8FD5B08A4B8756D3612494B7。
- [手机 APK](dist/capture-20261015-r11/RawWave-mobile-capture-20261015-r11.apk)，SHA256：6A2B389F9369112BE98ACADC4EC2AAFD608CD0F35F04E25E4D895CEBDE3E5AC6。
- [简明测试步骤](USER_TEST_CAPTURE.md)、[本轮证据](evidence/capture/20261015-continuous-ecg-quality.md)、[构建证据](dist/capture-20261015-r11/build.json)。双端覆盖安装，不卸载、不清数据。
- 当时尚未实现活动原始数据同步与手机导出；现已由顶部同步候选实现。

## 已被取代的 r10 接续记录

- 当前交付 **capture-20261015-r10 / 0.3.1-contact-timer / versionCode 9**。旧 capture 候选及下方 power/lifecycle 均为历史，不再作为当前安装包。
- 用户报告证明两次活动均在手表完成并封存：前后 ECG、活动中心率及加速度都有实际计数。报告只含计数与封存证据，不含原始样本，不能代替原始数据独立核验。
- 已修正 ECG 计时：启动传感器后先等待正确接触；只有收到 `LEAD_OFF=0` 且带 ECG 数值的真实样本时才开始30秒。最长等待80秒；未取得有效接触会记录失败，已收到的无效接触样本也原样保留。
- 手表入口已由「诊断报告与传输」改成「问题报告（仅用于排查）」，避免误认为它是活动数据同步。真正的活动原始数据自动同步仍未接入，下一阶段应在活动封存后自动执行，不设单独的日常二级菜单。
- **这是本地采集测试，不是完整第一版：活动原始数据同步手机和手机导出尚未接入。** 现有传输只带诊断与最近3次活动元数据摘要，不含完整健康数据。
- DEV：64项JVM测试通过；102任务全部重新执行；完整lint及最终APK权限/组件/DEX/入口/双端同证书检查通过。43个源码输入与构建清单逐项复核，0项变化；没有同步占位模块混入。DEVICE：ECG新计时逻辑未运行，需用户复测。
- 冻结确认：DeadlineRuntime、RunDeadlines、WorkBudget、SyncGuardService、PhoneReceiveSession、PhoneWakeService 与power-20261012-r01逐文件哈希相同。没有继续修改旧退出规则。新增采集服务只在活动中运行，不受诊断空闲计时控制。
- 手机/手表诊断存储取消累计20份测试限制，改100MiB总额+可用空间保护，无自动删除，防止采集测试报告再次被次数上限阻断。
- [手表 APK](dist/capture-20261015-r10/RawWave-wear-capture-20261015-r10.apk)，SHA256：56FAECDC430CD028519A853165D222D52809C5F3C8C3230936BBB4ACF32FC2B1。
- [手机 APK](dist/capture-20261015-r10/RawWave-mobile-capture-20261015-r10.apk)，SHA256：565B51C5FB432568F81E56C2FBEF29F292D03B9C9A7501C3FD51D3F65B2635FF。
- [简明测试步骤与流程图](USER_TEST_CAPTURE.md)、[本轮证据](evidence/capture/20261015-contact-timer.md)、[构建证据](dist/capture-20261015-r10/build.json)。双端覆盖安装，不卸载、不清数据。
- 下一步：用户验证 ECG 在未接触时不倒计时、正确接触后才从30秒开始。通过后，独立实现并验证活动原始数据自动同步、手机持久校验及导出。
- 当前未实现：模板编辑、完整意外中断恢复封存工具、原始健康数据同步/导出、最近30次清理策略、官方健康历史/逻辑日导出。未同步记录不删除；已有块保留，SDK/内存尾部不能保证零损失。

## 以下为历史记录

## 最新接续点：功耗优先（2026-10-12）

- 当前唯一交付 **power-20261012-r01 / 0.2.5-power / versionCode 7**，覆盖安装保留数据，双端一起更新。旧 lifecycle 候选保留为历史。
- 52项测试通过，102个任务重新执行；双端完整 lint 各0错误/12警告。源码输入与安装包哈希复核一致。真机 NOT_RUN，实际耗电尚未确认。
- 新规则：同步业务75秒、执行保护77秒、锁加清理余量最多80秒；成功或失败立即收尾，不自动重试。前台空闲80秒收尾，空闲退后台撤销闲置执行器与回调，无保活，不强杀缓存进程。活动采集不算空闲；活动功能仍未实现。
- [手机 APK](dist/power-20261012-r01/RawWave-mobile-power-20261012-r01.apk)，SHA-256：57F715DA4B07548A633146A5AE734FC877B9BDD90D3AE89125216ACEBE20C79E。
- [手表 APK](dist/power-20261012-r01/RawWave-wear-power-20261012-r01.apk)，SHA-256：7071CB9CFD26F1F699F5F23985CA03C8A1B0CE2B815BF1D861D0114CDF013C59。
- [测试步骤](USER_TEST_POWER.md)、[本轮实现和上轮真机分析](evidence/runtime/20261012-power.md)、[构建证据](dist/power-20261012-r01/build.json)。下一步等待新包使用反馈；不重复纠结缓存PID/最近任务卡片。模板与传感器另行实现。
- 31530d99报告证明上轮3份新文件的清单与持久回执校验通过，存在锁释放/闲置执行器终止证据；没有系统功耗测量，也没有手机后台自动唤醒事件，不判这两项通过。

## 上轮接续点：lifecycle（已被上方 power 候选取代）

- 唯一当前候选：**lifecycle-20261011-r03 / 0.2.4-lifecycle / versionCode 6**。2026-10-12 重新核对全部源码输入与构建记录一致，双端 APK 哈希一致。
- 开发验证：50 项 JVM 测试通过、102 个构建任务重新执行、双端完整 lint 各 0 错误/11 警告，最终 APK 权限/后台入口白名单、真实 DEX 定义及同证书核验通过。真机 NOT_RUN，不能宣称后台时限或耗电已通过。
- 已修改：成功不再等5秒；正常退出不强杀缓存进程；空闲退后台撤销闲置任务/计时回调；同步使用一次性限时服务与有超时的防休眠锁；只有手机有手表 HELLO 唤醒入口。接收会话独立于临时监听服务，避免入口解绑取消传输。系统拒绝后台服务会留下明确证据，不循环重试。
- 用户已确认允许不运行的缓存进程；正在同步时的息屏截止仍受系统限制，需要实测。界面提供电池限制状态及设置入口，不自动改变系统设置。
- [手机 APK](dist/lifecycle-20261011-r03/RawWave-mobile-lifecycle-20261011-r03.apk)，SHA-256：6914048977F89E8448F6FA3926656DDCAE696CB064D967EC3FA1323509EF8EC3。
- [手表 APK](dist/lifecycle-20261011-r03/RawWave-wear-lifecycle-20261011-r03.apk)，SHA-256：6A743748D687A65A39942D104462235A9D5CB751E3BF15F48D92778D30A74A72。
- [简单测试单](USER_TEST_LIFECYCLE.md)、[构建记录](dist/lifecycle-20261011-r03/build.json)、[原因分析](evidence/runtime/20261011-root-cause.md)、[实施与限制](evidence/runtime/20261011-lifecycle.md)。必须双端一起覆盖安装，不卸载、不清数据。
- 下一步：用户验证短时切页返回、手表发起手机后台接收、息屏空闲与同步收尾，导出新报告。收到后按同一版本窗口/文件/资源事件核对。活动模板、ECG/HR/ACC 仍未实现，本轮不冒充完整第一版。
- r01 候选因清单解析器问题标 FAIL；r02 开发检查通过但被最终诊断措辞修正取代，不交付。旧候选及原证据保留，不覆盖。

## 历史进展（截至 2026-10-09）
- 最新交付 runtime-20261009-r01 / 0.2.3-runtime：优先修正跨休眠绝对截止及恢复时过期工作拦截，独立截止模块（M4 部分提前实施）。43 项测试通过，双端完整 lint 各 0 错误、10 警告；真机未运行。**系统暂停期间 180 秒内物理退出仍无保证，功耗未验证。**见 [记录](evidence/runtime/20261009.md)、[测试单](USER_TEST_RUNTIME.md)。未加入活动创建，下一功能切片是手表模板的创建/保存，非采集。
- 2026-10-09 用户报告 4749acb3 已验证：M1 三份实际附件/回执/内层清单通过，正常传输和手机导出已有真机证据；另发现后台约 77 分钟晚到退出，不能把 M1 退出判为通过。
- 当前优先模块化：M0 已完成；M1 报告生成/导出分离的开发与自动检查已完成，真机回归待用户运行；M2–M5 未开始。见 [实施记录](evidence/M1/IMPLEMENTATION.md) 和 [本轮简单测试单](USER_TEST_M1.md)。本轮 31 项测试通过，两端完整 lint 各 0 错误、10 警告；没有宣称新包真机通过。
- 追加验证：r04 新增三份实际文件独立校验通过，累计五份；另一个等待窗口被 USER_EXIT 结束，单列、不算成功。见 [重复测试结果](evidence/G2/20261008-r04-repeat-results.md)。小文件基线已建立，下一步针对更大文件、断线和后台边界；不再重复要求相同三次小文件测试。
- 阶段：r04 DEV 通过，DEVICE 已有两次小文件/诊断传输成功证据，实际导出字节与回执及内层清单独立校验通过（一次旧包补传、一次新包）。见 [真机分析](evidence/G2/20261008-r04-device-success.md)。r03 初始化失败仍保留历史，不宣称唯一根因已确证；G1/G2 整体未放行。
- 当时的完整第一版目标尚未实现；现由顶部同步候选进入真机验收。
- G0 DEV：本机 JBR 25.0.3、Gradle 9.3.1、AGP 9.1.1、SDK 36 / Build Tools 36.0.0 已能运行构建；新 appId com.rawwave.rebuild，两端使用同一本机测试证书，候选脚本核对指纹。
- G0 DEVICE：用户负责型号/权限/三星测试模式/连接与功耗基线；未取得证据，不阻止开发侧编写代码。
- G1 DEVICE：2026-10-08 收到三份手机报告，内部哈希通过；手机导出、人工错误和退出请求链有部分真机证据。外部退出/功耗、空闲超时、手表取证仍未验证；不判定整体通过。见 [首轮报告分析](evidence/G1/20261008-user-phone-reports.md)。
- G2：仅最小前台切片已实现，后台/锁屏/进程不存在接收、1 MiB/10 MiB 测试未实现或未运行；G3–G8 未实现/未运行。不得用旧项目结果替代。
- Git：新目录尚未初始化仓库；候选将记录代码输入逐文件 SHA-256。
- 应用：原波 / RawWave。用户已卸载旧版；电脑旧工程/签名没有改动。

## 中断恢复事实
- r01：中间构建有 Kotlin NO-SOURCE，不能交付；只证明 shared 测试运行。
- r02：错误的 Kotlin sourceSets DSL 导致配置失败。
- r03：实际代码已编译，9 个 shared 测试通过，但完整静态检查发现旧返回手势接口错误；已改 AndroidX 返回分发器，旧结果作废。
- r04：最新编译/9 测试/静态检查通过；候选 APK 后台入口检查发现 AndroidX 附带初始化/安装配置入口，拒绝交付，见 dist/g1-20261007-r04/FAILED.json。
- r05：移除不必要的附带入口后，lint 把两个清单移除指令误报为缺少类，失败未交付。仅对移除指令局部说明 MissingClass，仍强制核对最终 APK 不含对应入口，没有关闭全局检查。
- r06：2026-10-07 20:32 完成新构建，9 测试通过；两端完整 lint 均 0 错误、9 警告；实际 DEX 类定义、清单无 Service/Receiver/Provider/uses-permission、non-debuggable、两端同证书、源输入及 APK SHA-256 核验通过。DEVICE NOT_RUN。

## 历史交付候选（已被顶部 lifecycle 候选取代）
- [手机 APK](dist/runtime-20261009-r01/RawWave-mobile-runtime-20261009-r01.apk)，SHA-256：7ECDC61CEED0365D584E1E6BDEF7ACC9CEAC8CA0EA75F73F5C9F1EF8E90D54F5。
- [手表 APK](dist/runtime-20261009-r01/RawWave-wear-runtime-20261009-r01.apk)，SHA-256：E5E22E888ADBF962C3FE1F0C5593D980EAC1287DA04824D36BADAC5D765090EE。
- [构建证据](dist/runtime-20261009-r01/build.json)、[实施记录](evidence/runtime/20261009.md)、[用户测试单](USER_TEST_RUNTIME.md)、[协议与未实现项](G2A_PROTOCOL.md)。M1、r04 安装包及证据保留作为历史基线，不再作为当前交付包。
- 证书 SHA-256：3078614c42158d1608e1ba7f97e4e0a4e4a6ad8f9e89daa91e073cdf34844b61（本机 Android 测试证书，非商店发行证书）。
- versionCode 5，versionName 0.2.3-runtime；同包名同证书，可原地覆盖保留数据。以后改应用源码需新运行编号/重新检查，不能沿用本次 PASS。旧包仅保留为历史对照，不与本轮混装。
- G2a r01 编译错误（未公开的 O_DIRECTORY 常量）未交付；r02 DEV 通过后审查补充 senderWindow 隔离、当前手表故障小摘要、严格前台取消，因此已标 SUPERSEDED；r03 才是交付候选，17 测试通过，两端 lint 0 错误，102 个构建任务执行。没有真机成功宣称。

## 历史接续记录（不覆盖顶部最新接续点）
2026-10-08 中断恢复核对：r06 的 18 个源码输入与构建记录哈希一致，两端 APK 哈希一致；12 份项目/证据 Markdown 的本地链接均有效。本次未重新构建、未运行真机测试，沿用的仅是同一源码和同一 APK 对应的开发侧检查结果。

1. 等待 runtime 候选短时切页/超过截止恢复检查的真机报告；不宣称后台强制退出已解决。下一功能可独立做手表活动模板创建/保存，用户已授权考虑；退出/采集问题不得被模板界面掩盖。M2 通信包装及后续模块化仍未完成，协议顺序继续冻结。
2. 若有失败先读取同一版本证据，定位到阶段后修复/重新构建，不要求卸载来“修好”。
3. 模块边界实施及回归之后，才恢复断线/更大文件和有限后台接收；G1 未验证项随新候选继续复测，不能冒称通过。第一版目标没有缩减，ECG/HR/ACC 在 G4/G5 接入。用户要求后续回复简单准确、非程序员可读，工程细节写入文件。

用户决定以 USER_CONFIRMATION.md 为准；README/GATES 已随第一版范围更新。没有电脑关机、旧目录删除、真机安装或自动外发操作。














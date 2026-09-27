# 第二批交付与验收记录

日期：2026-09-28，Asia/Shanghai。

## 已完成

- 新增 production_event 表及 V6 事务迁移。
- 复用现有 MQTT 客户端，增加 production 主题 QoS 1 订阅及回调。
- 校验主题/报文身份、真实 runId、源墙钟运行区间、周期车辆、服务阶段目标、载荷和可选任务指令。
- 事件 ID 与周期内事件类型双重幂等；相同证据重复接收不增加记录，冲突证据拒绝。
- 拒收证据复用 message_rejections；无后台 REST 写入口供 HMI 伪造现场事件。
- 作业事件合并统一事件查询，暴露 cycleId 与原记录证据。
- 指令生命周期按原决策创建时间归属运行，避免上一运行回执单凭接收时间进入下一运行。
- 新增调度员专用生产事件和统计 REST 查询。
- 八事件齐全且时间有效的周期才参与生产统计；未知指标保留 null。
- 提供模型副本准确入口、变量、函数体与待发送缓存的手动步骤。

## 已执行测试

最终执行 ./bin/build.sh，BUILD SUCCESS。
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0。
其中 ProductionEventIntegrationTest 两项测试覆盖：

1. 无事件/缺少事件保持统计未知；重复事件幂等、内容冲突拒绝、身份不匹配拒绝、retained 拒绝、未知运行拒绝。
2. 乱序到达可接入，按仿真时间评价；时间反序周期不计产量；完整周期吨数、品位和阶段时长计算。
3. 统一事件投影携带 cycleId，证据接口可查询原记录。
4. 原 MQTT 回调可路由作业事件；错误主题身份记录拒收；重复不增加事件。

测试仅使用隔离 SQLite 和合成测试报文，不是真实 AnyLogic 生产证据。
证据位置：target/surefire-reports/com.mine.dispatch.ProductionEventIntegrationTest.txt/XML，及其他测试报告。
最终 JAR SHA-256：eecf52bc6340980eaabfcb0969784fa71b68ea801b50f669c9eaa681ab93b520。

## 待验收与剩余边界

- AnyLogic 副本手工接入、Build、八事件全周期真实发布：待验收。
- 真实 Broker 订阅重连、断线缓存重发、重复发布及跨运行晚到事件：待验收。
- 生产查询 REST 的现场权限与新迁移在已有数据库中的升级：待验收。
- 原决策归属回执修复的跨结束边界专项测试：待验收。
- taskId 仍未采集；模型手动步骤中 commandId 保持 null，完整任务与周期强关联未完成。
- 本批统计为进入铲端流程到卸载完成，不包含此前空载行驶，不称完整运输循环时间。
- 设备利用率、ETA、完整派单至派单周期、版本和决策依据快照：未实现。
- 发送缓存为实验内存，PUBACK 不代表后台入库；应用确认和跨实验可靠缓存未实现。
- 旧运行样本仍没有现场 runId，状态精确归属和运行前地图继承尚未完成。
- 未重启/替换当前现场服务；使用新 JAR 后才会生效。

## 改动文件

修改：MqttIngestionClient.java、RunTrackingRepository.java。
新增：ProductionEventService.java、ProductionTrackingController.java、V6__production_events.sql、
ProductionEventIntegrationTest.java、ANYLOGIC_PRODUCTION_EVENTS.md、ANYLOGIC_PRODUCTION_EVENT_CODE.md、本记录。
未修改 .alp、前端或账号配置。

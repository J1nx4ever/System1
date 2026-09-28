# 双向终端消息 MQTT 协议与验收

这条链路为：调度台 → 后台持久化 → MQTT QoS 1 → AnyLogic → MQTT 自动接收回执 → 后台持久化 → 调度台；车/铲 HMI 还可针对调度消息人工回复，回复也必须经 MQTT 的 response 订阅回流才入库。AnyLogic 端按 [手工修改指南](ANYLOGIC_TERMINAL_MQTT_MANUAL.md) 在当前工程中操作；`fusion_openpit_mine.original.alp` 只读，不修改。

## 主题与内容

- 下发：`mine/sim/{simulationId}/terminal/{VEHICLE|SHOVEL}/{id}/message`，QoS 1、非 retained。
- 回流：`mine/sim/{simulationId}/terminal/{VEHICLE|SHOVEL}/{id}/response`，QoS 1、非 retained。

下发 JSON 例子：

```json
{"schemaVersion":1,"messageId":"uuid","simulationId":"SIM_001","targetType":"VEHICLE","targetId":0,"senderRole":"DISPATCHER","senderName":"dispatcher","text":"请核查制动温度","sentAtMs":1730000000000,"replyToMessageId":null}
```

回流 JSON 例子：

```json
{"schemaVersion":1,"messageId":"ack-uuid","simulationId":"SIM_001","terminalType":"VEHICLE","terminalId":0,"senderName":"AnyLogic AUTO_ACK","text":"AnyLogic 已接收调度消息","sentAtMs":1730000000001,"replyToMessageId":"uuid"}
```

按指南修改后的 AnyLogic 工程复用现有现场 MQTT 连接和仿真线程队列：只在仿真线程检查主题/载荷并发布确定性 `ack-{messageId}`，QoS 1 重投不会产生重复数据库记录。自动回执表示模型收到消息，不代表司机确认或现场处理完成；人工回复来自车/铲 HMI，显示独立消息。后台只接受与已发送消息的 simulation、对象类型和编号一致的 response；不接受 retained response。

## 页面与权限

调度员在运行总览的“调度 ↔ 现场终端”选择对象、编号并发送。仅当前未结束的 Run 可以发送；列表以所选 Run 的墙钟边界过滤。车端、铲端只看到自己的消息，点击“回复”后由后台代理发布 MQTT response；接口返回 `PUBLISHED` 只表示 Broker 已确认，列表出现 `RECEIVED` 才表示后台订阅收到并持久化。页面每 3 秒同步一次，服务端同时向有权会话发送 `terminalMessage` WebSocket 事件。

后台状态：`PENDING`（已入库、等待发布）、`SENT`（Broker 已接受）、`DELIVERED`（收到终端回执或回复）、`FAILED`（MQTT 发布失败）；入站消息为 `RECEIVED`。不要把 `SENT` 视为终端已收到。

REST 接口：`GET/POST /api/v1/simulations/{simulationId}/terminal-messages`；车/铲 HMI 使用 `POST .../terminal-messages/{messageId}/reply`。数据库迁移为 `V7__terminal_messages.sql`。

## 现场验收

1. 启动 TLS Broker 与后台（`MQTT_ENABLED=true`，设置 Broker URI、用户名、CA 和密码），等待后台的会话 MQTT 客户端完成订阅。
2. 按 [AnyLogic 手工修改指南](ANYLOGIC_TERMINAL_MQTT_MANUAL.md) 在当前工程中接入、编译并运行；保持 `fieldSimId` 与调度台 Simulation 一致，并配置 `FIELD_MQTT_PASSWORD`。只读基线 `fusion_openpit_mine.original.alp` 不会被修改。
3. 用调度员账号选择活动 Run，向车辆 T0 或铲 S0 发消息。先看到 `SENT`，AnyLogic 收到后看到 `AnyLogic AUTO_ACK` 与 `DELIVERED`；AnyLogic 控制台会输出 `[TerminalMessage] response queued for ...`。
4. 用对应车/铲账号打开 HMI，点击该消息的“回复”输入文字；调度台应新增该终端用户名的入站消息。其他车/铲账号不能读取或回复此对象消息。

构建/单测已覆盖角色隔离、发布状态、MQTT 回流后入库与 QoS 1 去重；真实 TLS Broker 和 AnyLogic IDE 的现场联测仍需按上面步骤运行，不能以单测代替。若 `SENT` 长时间未转 `DELIVERED`，优先核对手工修改后的工程是否运行、`fieldSimId`、Broker TLS/凭据，以及响应主题订阅是否成功。

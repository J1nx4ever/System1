# 露天矿无人运输调度中心：第一条真实联调链路

模块化单体后台，实现当前过渡阶段的：

`AnyLogic → Mosquitto TLS → 状态接入/存储 → REST/WebSocket → Web 二维地图`

在既有状态链路和已验收人工改派上，增加统一决策追踪、真实 DRL 自动派单网关、
真实速度推理网关和三个现场终端。AnyLogic 模型需先按
`ANYLOGIC_MANUAL_DISPATCH.md` 接入人工改派，再按
`ANYLOGIC_INFERENCE_GATEWAY.md` 手工接入推理触发、指令执行和回执；只读 `.alp`
不会由后台工程修改。

调度中心与车/铲端的双向文字消息走 MQTT，见
[`TERMINAL_MQTT_CONVERSATION.md`](TERMINAL_MQTT_CONVERSATION.md)。
AnyLogic 端请按 [`ANYLOGIC_TERMINAL_MQTT_MANUAL.md`](ANYLOGIC_TERMINAL_MQTT_MANUAL.md)
在当前工程中手工修改；`fusion_openpit_mine.original.alp` 仅作只读参考。

## 已核对的真实发布契约

来源为工作区 `fusion_openpit_mine.original.alp` 和
`field-uplink-1.0.0.jar`：

- `mine/sim/{simulationId}/state/map`：`MapState`，QoS 1，retained；
- `mine/sim/{simulationId}/state/vehicle/{vehicleId}`：`VehicleState`，QoS 0；
- `mine/sim/{simulationId}/state/shovel/{shovelId}`：`ShovelState`，QoS 0；
- 状态与速度事件显式周期为 0.5 仿真秒；模型基础时间单位为 Minute，但所有现场
  消息使用 `time(SECOND)`，协议时间统一为秒；
- `sequence` 是仿真实例全局序号。后台只对同一实体拒绝倒退/重复序号，
  不要求单辆车或单个铲装点的序号连续。

## 能力组织

- `capability.production`：运行配置、TLS 信任、进程生命周期；
- `capability.storage`：SQLite WAL、迁移、异步历史写入；
- `capability.monitoring`：健康、接入计数、实体/实例失联判断；
- `capability.vehicledata`：MQTT、主题解析、真实 Protobuf 解码与校验、最新状态；
- `capability.decision`：27 维派单/速度观测、真实 gRPC 推理、后台校验、指令与回执追踪；
- `web`：快照/历史 REST、同源原生 WebSocket；
- `static`：保留调度地图和人工改派，并增加调度决策详情、车端 HMI、铲端 HMI。

上传的两个协议 JAR 已原样放入项目本地 Maven 仓库。派单响应仅含
`action/value/logits/latency`，速度响应仅含各车 `acceleration`，均无解释字段。
页面只展示真实输入、模型原始输出、可核验的后台校验和回执。

调度中心的“决策证据”分为“任务指派”和“速度控制”，分别读取各类型最近
500 条记录；点击左侧记录查看右侧完整证据链。任务指派逐条记录。速度控制
仍逐周期推理并发送 MQTT 指令，但证据按车辆自适应采样：首条、每 10 秒
保底一条、前车/冲突风险出现时立即一条，以及相隔至少 2 秒的风险变化或
加速度变化达到 0.5 m/s² 时记录。未采样命令的候选证据在内存中最多
保留 60 秒、最多 10000 条；期间若发布失败或现场回执为 REJECTED/EXPIRED，
会补录为完整决策证据。正常 EXECUTED 的未采样命令不落库。既有历史记录
不删除，采样仅对重启后新接收的速度推理生效。

## 构建与测试

项目使用 Java 17。上传的现场协议 JAR 已逐字节复制到项目内 Maven 仓库
`vendor-repository/`，构建产物使用的 `field-uplink-1.0.0.jar` 与上传件
SHA-256 一致；Paho 1.2.5 与 Protobuf Java 3.25.3 使用固定版本。

```bash
cd /home/jinx/桌面/System1/dispatch-center
./bin/build.sh
```

测试覆盖真实消息类的序列化/解码、字段校验、非连续全局序号、SQLite、REST、
WebSocket 首帧/增量以及失联判断。

## 启动

先启动 TLS Broker：

```bash
mosquitto -c /home/jinx/桌面/System1/mqtt-tls/mosquitto.conf -v
```

新终端中设置后台凭据并启动：

```bash
cd /home/jinx/桌面/System1/dispatch-center
read -rsp "MQTT password: " MQTT_PASSWORD
echo
export MQTT_PASSWORD
export MQTT_USERNAME=field_uplink
export MQTT_CA_FILE=/home/jinx/桌面/System1/mqtt-tls/certs/ca.crt
./bin/start.sh
```

密码只来自环境变量，不会写入源码或默认配置。其他变量见
`src/main/resources/application-example.yml`。主机部署环境变量和 systemd 单元
示例分别见 `deploy/dispatch-center.env.example` 与
`deploy/dispatch-center.service.example`；示例不会被自动安装。

打开：<http://localhost:8080/>

## 进程内 ONNX 推理

后台默认使用 Java ONNX Runtime 直接加载 `models/dispatch_ppo.onnx` 和
`models/speed_actor.onnx`，不再要求启动 50051/50060 两个 Python 推理进程。
派单模型输出 4 个 logits 和 value，由 Java 取 argmax；速度模型对一帧全部车辆
批量运行 actor，并在模型内完成 `[-1,1]` 到 `[-3,2] m/s²` 的分段映射。

重新导出和执行 Python/ONNX 数值校验：

```bash
cd /home/jinx/System1/DispatchPPO
conda run -n anylogic-env python -m python.export_onnx --model dispatch_ppo.zip --output ../dispatch-center/models/dispatch_ppo.onnx

cd /home/jinx/System1/speedcontrol
conda run -n anylogic-env python export_onnx.py --checkpoint checkpoint_000016/policies/default_policy --output ../dispatch-center/models/speed_actor.onnx
```

生产部署时，JAR 和 `models/` 目录需一起复制。可用
`DISPATCH_ONNX_MODEL`、`SPEED_ONNX_MODEL` 指定绝对路径。灰度回退到旧 Python gRPC
服务时设置 `INFERENCE_MODE=grpc`；默认值为 `onnx`。

## REST 与 WebSocket

- `GET /api/v1/health`
- `GET /api/v1/simulations`
- `GET /api/v1/simulations/{id}/snapshot`
- `GET /api/v1/simulations/{id}/history/maps`
- `GET /api/v1/simulations/{id}/history/vehicles/{vehicleId}`
- `GET /api/v1/simulations/{id}/history/shovels/{shovelId}`
- `POST /api/v1/simulations/{id}/manual-dispatch`
- `GET /api/v1/simulations/{id}/manual-dispatch?vehicleId={vehicleId}`
- `GET /api/v1/simulations/{id}/decisions`
- `GET /api/v1/simulations/{id}/vehicles/{vehicleId}/decisions`
- `GET /api/v1/simulations/{id}/shovels/{shovelId}/decisions`
- `WS /ws/state?simulationId=SIM_001`
- `POST /api/v1/auth/login`, `GET /api/v1/auth/me`, `POST /api/v1/auth/logout`
- `GET/POST /api/v1/simulations/{id}/terminal-actions`
- `GET/POST /api/v1/simulations/{id}/terminal-messages`
- `POST /api/v1/simulations/{id}/terminal-messages/{messageId}/reply`
- `GET /api/v1/simulations/{id}/alerts` and `POST .../alerts/{alertId}/{acknowledge|process|close}`
- `GET /api/v1/simulations/{id}/audit`

WebSocket 建连后服务端立即发送 `snapshot`，之后发送 `map`、`vehicle`、
`shovel`、`status`、`decisionTrace` 增量。客户端断线重连前重新调用 REST 快照，重连成功后
服务端还会再发送一次当前快照，避免状态窗口丢失。

## 数据库

默认数据库：`data/dispatch-center.db`。启动时自动执行：

`src/main/resources/db/migration/V1__initial_schema.sql`、
`V2__manual_dispatch.sql`、`V3__decision_trace.sql` 和 `V4__access_audit_alerts.sql`

数据库使用 WAL，保存地图版本、车辆历史、铲装点历史和拒收报文摘要；最新
运行状态保存在并发内存状态仓中，以低延迟服务 REST 与 WebSocket。

## 本地真实 MQTT 验收消息

该脚本只用于联调，不会成为页面的固定演示数据。它通过 `field_uplink.proto`
编码真实 Protobuf 二进制，并按 AnyLogic 当前 QoS 发布：

```bash
export MQTT_PASSWORD='实际密码'
export MQTT_USERNAME=field_uplink
conda run -n DRL ./scripts/publish-live-fixtures.sh
```

正常结果可在 `/api/v1/health` 中看到 1 条地图、2 条车辆历史、4 条铲装点历史，
且 `rejected=0`、`persistenceDropped=0`。

## 三个终端与现场验收

- 调度中心：`/`；
- 车端 HMI：`/vehicle.html?simulationId=SIM_001&vehicleId=0`；
- 铲端 HMI：`/shovel.html?simulationId=SIM_001&shovelId=0`。

车端和铲端分别读取按车辆、按铲装点过滤的决策投影；调度中心读取全量决策。
AnyLogic 手动代码见 `ANYLOGIC_INFERENCE_GATEWAY.md`，现场脚本见
`DEMO_ACCEPTANCE.md`。

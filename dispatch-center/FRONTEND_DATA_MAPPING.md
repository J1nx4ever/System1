# 前端页面与数据映射 V1

日期：2026-09-28，Asia/Shanghai  
状态：`FROZEN_MAPPING_V1`  
上位规范：`FRONTEND_STORY_CHAIN.md` 最终冻结版。

本文只定义页面如何表达已有事实，不修改接口、现场模型或统计口径。若本文与上位规范冲突，以上位规范为准。

## 1. 映射等级

每个页面字段必须属于以下一种来源等级：

| 等级 | 定义 | 页面规则 |
|---|---|---|
| `DIRECT` | API 或 WebSocket 直接返回的现场事实/系统记录 | 可直接展示，保留单位和时间 |
| `DERIVED` | 可由已保存事实确定性计算 | 标注计算口径，禁止使用猜测值 |
| `DECLARED` | 操作者提供的场景、版本或配置声明 | 明确标注“声明值” |
| `MISSING` | 当前未采集或无法可靠关联 | 显示“未知/暂无证据”，不得补零 |

页面统一输出数据质量：

| `data_quality` | 判定 |
|---|---|
| `COMPLETE` | 本组件所需字段全部存在、有效且满足关联/时间规则 |
| `PARTIAL` | 可展示部分事实，但存在非关键字段缺失 |
| `INSUFFICIENT` | 缺少关键证据，不能形成该组件的业务结论 |
| `UNKNOWN` | 尚未查询、接口失败或来源身份未知 |

当前后端未统一返回 `data_quality`。V1 前端可按本文规则派生，但必须保留产生该等级的原因；后续可由后端统一提供。

## 2. 全局标识与时间映射

### 2.1 标识

| 前端概念 | 当前字段 | 来源 | 等级 | 规则 |
|---|---|---|---|---|
| 仿真实例 | `simulationId` / `simulation_id` | 快照、Run、事件 | `DIRECT` | 页面内部统一为 `simulationId` |
| 运行 | `run_id` | Run API | `DIRECT` | 未选择 run 时，不展示运行级统计 |
| 场景 | `scenario_id` | Run API | `DECLARED` | 标注为操作者声明 |
| 车辆 | `vehicleId` / `vehicle_id` | 状态、决策、事件 | `DIRECT` | 展示为 `T{vehicleId}` |
| 铲装点 | `shovelId` / `siteId` | 状态、事件 | `DIRECT` | 名称优先取 `shovelName` |
| 指令 | `commandId` / `command_id` | 决策、事件 | `DIRECT` | 当前无 Task 时作为任务指令引用 |
| 周期 | `cycleId` / `cycle_id` | 生产事件 | `DIRECT` | 不允许按时间邻近生成 |
| Task | `taskId` | 当前缺失 | `MISSING` | 显示“未采集”，不得用 commandId 冒充 |

### 2.2 双时间轴和三类时间

| 规范概念 | 当前字段 | 来源 | 用途 |
|---|---|---|---|
| `sim_time` | `simTimeSeconds` / `sim_time_seconds` | 状态、决策输入、生产事件、运行事件 | 生产过程排序与业务展示 |
| `event_timestamp` | `sentWallTimeMs` / `sent_wall_time_ms` / `createdAtMs` | 状态、生产事件、决策 | 事件产生墙钟时间；字段语义因来源不同 |
| `recv_wall_time` | `receivedAtMs` / `received_at_ms` / `wall_time_ms` | 状态历史、生产事件、运行投影 | 接收、持久化或投影时间 |

规则：

- 周期故事与生产统计只按 `sim_time` 排序；
- 网络延迟仅在同一来源同时具有事件产生时间和接收时间时计算；
- `latencyMs = recv_wall_time - event_timestamp`，负数或缺字段时显示未知；
- 决策的 `createdAtMs` 不能冒充现场生产事件时间；
- 页面刷新时间只用于“页面更新于”，不能作为业务时间。

## 3. 登录页

### 页面问题

“我以什么角色进入哪个终端？”

| 组件/字段 | API 字段 | 来源 | 等级 | 失败表达 |
|---|---|---|---|---|
| 用户名、密码 | 请求体 | `POST /api/v1/auth/login` | 用户输入 | 不回显密码 |
| 身份 | `user.username`、`user.role` | 登录响应 / `GET /auth/me` | `DIRECT` | 认证失败 |
| 绑定对象 | `simulationId`、`subjectId` | 用户身份 | `DIRECT` | 身份配置缺失 |
| 跳转目标 | `role` | 前端确定性映射 | `DERIVED` | 未知角色拒绝进入 |

角色映射：`DISPATCHER → 调度中心`、`VEHICLE → 车端 HMI`、`SHOVEL → 铲端 HMI`。

## 4. 调度中心：运行总览

### 页面问题

“当前运行什么，整体是否正常，已经产生什么可核验结果？”

| 组件/字段 | API 字段或算法 | 来源 | 等级 | 数据质量/降级 |
|---|---|---|---|---|
| 当前 Run | 最近一条 `ended_at_ms == null` | `GET /runs?simulationId=` | `DERIVED` | 无活动 run：`INSUFFICIENT`，明确显示“未开始运行” |
| 场景 | `scenario_id` | Run | `DECLARED` | null 显示“未声明” |
| 运行起止 | `started_at_ms`、`ended_at_ms` | Run | `DIRECT` | 活动运行显示“进行中” |
| 仿真时间 | 车辆/铲状态的最新 `simTimeSeconds` | Snapshot | `DERIVED` | 无状态显示未知；不可用服务器时间替代 |
| 通信状态 | `online`、`lastReceivedAtMs`、`mqttConnected` | Snapshot + Health | `DIRECT` | 区分 MQTT 已连与现场数据在线 |
| 车辆/铲数量 | `vehicles.length`、`shovels.length` | Snapshot | `DERIVED` | 无快照为 `UNKNOWN` |
| 在线实体 | `stale == false` 的数量 | Snapshot | `DERIVED` | 显示分母和最后状态时间 |
| 载重车辆 | `loaded == true && stale == false` | Snapshot | `DERIVED` | stale 车辆不计当前载重 |
| 活动告警 | `status != CLOSED` | Alerts API | `DERIVED` | 接口失败为未知，不显示 0 |
| 生产概况 | Production summary | `/runs/{runId}/production/summary` | `DIRECT` | 受第 9 节口径限制 |

禁止使用：

- `ingestion.received` 作为生产量；
- `decisionTraces` 作为模型调用次数；
- `COMMAND_ACK` 作为完成运输次数；
- 历史最后状态作为当前在线状态。

## 5. 调度中心：实时监控

### 5.1 地图

| 字段 | API 字段 | 来源 | 等级 | Stale 规则 |
|---|---|---|---|---|
| 地图底图/范围 | `map` | Snapshot / WS | `DIRECT` | 无 MapState 显示等待现场地图 |
| 车辆坐标 | `x`、`y` | VehicleState | `DIRECT` | 可保留最后位置但置灰并标注最后更新时间 |
| 车辆速度 | `speedMps` | VehicleState | `DIRECT` | stale 时显示 `--` |
| 载荷 | `loaded`、`payloadTons` | VehicleState | `DIRECT` | stale 时标为“最后值”，不作为实时汇总 |
| 当前状态 | `statechartState` | VehicleState | `DIRECT` | stale 时显示“过期” |
| 目的地 | `destinationNode` | VehicleState | `DIRECT` | stale 时标为“最后目标” |
| 铲状态 | `available`、`busy` | ShovelState | `DIRECT` | stale 时置灰，状态显示“过期” |
| 铲队列 | `waitingTrucks`、`trucksInService` | ShovelState | `DIRECT` | stale 时显示 `--` |

### 5.2 车辆详情抽屉

展示顺序冻结为：身份 → 实时状态 → 当前目标 → 最近任务指令 → 最近控制参考 → 人工改派 → 相关周期/事件。

实时字段来自 Snapshot；任务/控制来自 Decisions API；人工改派来自 Manual Dispatch API；周期入口来自生产事件中的 `vehicleId + cycleId`。

当前不存在可靠 ETA，必须显示“未采集”，不得用距离/速度临时估算。

## 6. 决策证据页

### 页面问题

“什么触发了决策，系统看到了什么，输出了什么，是否真正执行？”

| 证据段 | 字段 | 来源 | 等级 | 规则 |
|---|---|---|---|---|
| 身份 | `decisionId`、`decisionType`、`decisionSource` | DecisionTrace | `DIRECT` | 始终展示来源 |
| 触发 | `triggerEvent` | DecisionTrace | `DIRECT` | 不补充未保存原因 |
| 对象 | `vehicleId`、`shovelId` | DecisionTrace | `DIRECT` | null 显示未知 |
| 模型输入 | `inputSummaryJson` | DecisionTrace | `DIRECT` | 解析失败为 `PARTIAL` |
| 27 维输入 | `input.values` / `evidenceSnapshot.normalizedObservation` | 输入 JSON | `DIRECT` | 值与名称均为 27 才完整 |
| 候选状态 | `evidenceSnapshot.candidateStatesAtGateway` | 输入 JSON | `DIRECT` | 四候选齐全才完整 |
| 车辆快照 | `vehicleStateAtGateway` | 输入 JSON | `DIRECT` | 标注“网关采集”，不称现场同步快照 |
| 模型输出 | `modelOutputJson` | DecisionTrace | `DIRECT` | 展示原始动作、目标、延迟 |
| 模型版本 | `evidenceSnapshot.modelVersion` | 输入 JSON | `DECLARED` | null 显示未声明 |
| 后台校验 | `validationStatus`、`validationDetail` | DecisionTrace | `DIRECT` | PASSED 仅表示校验通过 |
| 指令发送 | `commandId`、`commandTopic`、`publishedAtMs` | DecisionTrace | `DIRECT` | publishedAtMs 缺失则未证明发送 |
| 执行回执 | `ackStatus`、`ackAtMs`、`ackDetail` | DecisionTrace | `DIRECT` | 只有 EXECUTED 描述为已执行 |
| 模型原因 | `explanation` / `causalExplanation` | 当前通常为空 | `MISSING` | 标注“模型未提供因果解释” |

页面名称固定为“决策证据”，不得使用“AI 原因分析”。

## 7. 人工干预

### 页面问题

“谁在何时把哪辆车从什么目标改到哪里，现场是否执行？”

| 字段 | 来源 | 等级 | 规则 |
|---|---|---|---|
| 车辆 | `vehicleId` | ManualDispatchCommand | `DIRECT` | 与登录权限共同校验 |
| 原目标 | `previousDestinationNode` | ManualDispatchCommand | `DIRECT` | null 显示未知 |
| 新目标 | `targetNode` | ManualDispatchCommand | `DIRECT` | 不从当前状态反推 |
| 指令 | `commandId` | ManualDispatchCommand | `DIRECT` | 作为干预证据主键 |
| 请求/发送/完成时间 | `requestedAtMs`、`publishedAtMs`、`completedAtMs` | ManualDispatchCommand | `DIRECT` | 缺失阶段明确显示等待 |
| 状态 | `status`、`statusMessage` | ManualDispatchCommand | `DIRECT` | 只有 EXECUTED 为已执行 |
| 操作者 | `actorUsername` | Audit | `DIRECT` | 与 commandId/对象关联不足时标 `PARTIAL` |
| 周期沿革 | `previousCommandId`、`commandId` | Production event / Cycle story | `DIRECT` | 仅表达源端显式引用 |

多次改派只能展示当前数据能解析的最近前序关系，不补造完整链。

现有人工改派链路已验收：服务端创建并校验 Command，经 MQTT 下发至 AnyLogic，在安全节点换路后
由现场结果主题返回 `EXECUTED / REJECTED`，随后更新 ManualDispatchCommand 与 DecisionTrace，
并保留操作审计。前端不得把 `SENT` 表述为改派成功。

## 8. 周期故事页

### 页面问题

“一次完整运输循环如何由任务产生、经过运输和装卸并形成结果？”

冻结版 Cycle 定义为“上次卸载完成触发下一运输过程，到下一次卸载完成”。其完整事件要求为：

```text
UNLOAD_FINISHED
DISPATCH_TRIGGERED
HAUL_EMPTY_START
ARRIVE_SHOVEL
LOAD_START
LOAD_FINISHED
HAUL_FULL_START
UNLOAD_START
（以本循环下一次卸载完成作为闭合边界）
```

### 当前实现映射

当前 `ProductionEventService` 只保存：

```text
ARRIVE_SHOVEL
QUEUE_LOAD
LOADING_START
LOADING_FINISH
ARRIVE_DUMP
QUEUE_DUMP
DUMP_START
DUMP_FINISH
```

因此当前接口只能证明“到达铲点至卸载完成的作业阶段周期”，不能满足冻结版完整运输循环定义。

| 展示项 | 当前来源 | 等级 | V1 处理 |
|---|---|---|---|
| 周期身份/车辆 | `cycleId`、`vehicleId` | `DIRECT` | 可展示 |
| 作业阶段八事件 | Production events | `DIRECT` | 可展示并按 simTime 排序 |
| 任务指令引用 | `commandId`、`previousCommandId` | `DIRECT` | 可展示 |
| 决策证据 | Cycle story `taskDecisions` | `DIRECT` | 可展示 |
| 上一卸载完成 | 未形成当前周期起点关联 | `MISSING` | 显示缺失 |
| 调度触发 | 决策事件存在，但未作为生产周期源事件关联 | `MISSING` | 不按最近时间拼接 |
| 空载运输开始 | 未采集 | `MISSING` | 显示缺失 |
| 满载运输开始 | 未采集 | `MISSING` | 显示缺失 |
| 完整运输周期结论 | 关键事件缺失 | `INSUFFICIENT` | 不标记为冻结版 COMPLETE |

在后台口径调整前，页面必须将现有 `completeCycles` 标为“作业阶段完整周期（现行口径）”，不能直接显示为“完整运输周期”。

### 事件排序

生产事件按 `simTimeSeconds` 排序；同一 simTime 的事件按冻结语义顺序稳定排序，不能依赖 eventId 字典序。

## 9. 生产结果与运行效果

| 指标 | 当前算法/字段 | 来源 | 等级 | 页面口径 |
|---|---|---|---|---|
| 观察周期 | `observedCycles` | Production summary | `DIRECT` | 现行作业阶段周期 |
| 完整周期 | `completeCycles` | Production summary | `DIRECT` | 标注“作业阶段完整周期” |
| 不完整/倒序 | `incompleteCycles`、`invalidOrderCycles` | Production summary | `DIRECT` | 必须同时展示 |
| 可计产周期 | `eligibleCompleteCycles` | Production summary | `DIRECT` | 现行口径 |
| 矿石/废石吨数 | `oreTons`、`wasteTons` | DUMP_FINISH 原始载荷 | `DIRECT` | null 为未知；真实 0 才显示 0 |
| 加权矿石品位 | `weightedOreGrade` | Production summary | `DERIVED` | oreTons 为 0 时未知 |
| 装载/卸载等待 | average wait fields | Production summary | `DERIVED` | 基于现行事件差值 |
| 装载/卸载时长 | average duration fields | Production summary | `DERIVED` | 基于现行事件差值 |
| 到铲至卸载完成 | `averageArrivalToDumpFinishSeconds` | Production summary | `DERIVED` | 不称完整运输循环时间 |
| 设备利用率 | 未采集完整工作/可用时长 | `MISSING` | 显示未采集 |
| ETA、能耗、油耗 | 未采集 | `MISSING` | 不展示数值 |

吨数、品位或资格字段缺失时，不得将 null 转成 0。`DUMP_FINISH` 必须在载荷清零前采集；前端不修正源端错误值。

## 10. 历史回放页

### 页面问题

“在一次运行的某个时刻，系统已经记录了哪些事实？”

| 字段/组件 | 当前来源 | 等级 | 规则 |
|---|---|---|---|
| Run 选择 | Run list | `DIRECT` | 只能选择有明确边界的 run |
| 查询游标 | `atWallTimeMs` | Replay API | `DIRECT` | 当前按接收墙钟时间查询 |
| 业务时间 | 各样本 `sim_time_seconds` | Replay source rows | `DIRECT` | 页面主时间显示 |
| 车辆/铲/地图 | Replay `vehicle/shovel/map` | `DIRECT` | 只显示已持久化最新样本 |
| 插值状态 | `interpolated=false` | Replay API | `DIRECT` | 明确“不插值” |
| 样本年龄 | 查询时间减 `received_at_ms` | `DERIVED` | 用于完整性/过期判断 |
| 事件时间轴 | Run events | `DIRECT` | 业务事件优先显示 sim time |

当前限制：选择器使用墙钟时间，而业务主轴要求 sim time。V1 前端必须同时显示两者；在后端提供 simTime 查询前，不得声称按业务时间精确定位。

## 11. 车端 HMI

展示顺序冻结为：

```text
绑定车辆 → 当前任务 → 下一目标 → 执行状态 → 控制状态 → 告警 → 终端操作
```

| 组件 | 来源 | 等级 | 降级 |
|---|---|---|---|
| 身份 | Auth `simulationId + subjectId` | `DIRECT` | 绑定缺失拒绝进入 |
| 本车状态 | Snapshot 中绑定车辆 | `DIRECT` | stale 时速度 `--`、状态“过期” |
| 当前任务 | 最近 TASK Decision + 当前状态 | `PARTIAL` | 当前无独立 Task，标题写“任务指令” |
| 下一目标 | `destinationNode` / TASK 输出 | `DIRECT` | 两者不一致时并列展示，不自行选择真值 |
| 控制参考 | 最近 SPEED Decision | `DIRECT` | 过期/无回执明确标注 |
| 告警 | stale + 决策失败 + Alerts API | `DERIVED/DIRECT` | 来源分别标注 |
| 操作 | Terminal Actions API | `DIRECT` | 服务端校验结果原样展示 |

车端禁止其他车辆选择、全局统计和全局调度操作。

## 12. 铲端 HMI

展示顺序冻结为：

```text
绑定铲装点 → 可用/作业状态 → 当前服务车辆 → 等待车辆 → 来车 → 相关任务 → 异常反馈
```

| 组件 | 来源 | 等级 | 降级 |
|---|---|---|---|
| 身份 | Auth `simulationId + subjectId` | `DIRECT` | 绑定缺失拒绝进入 |
| 本铲状态 | Snapshot 中绑定铲 | `DIRECT` | stale 时实时字段显示 `--` |
| 当前服务车辆 | 当前仅有 `trucksInService` 数量 | `MISSING` | 不得凭位置猜具体车辆 |
| 等待数量 | `waitingTrucks` | `DIRECT` | stale 时 `--` |
| 等待车辆清单 | 未采集队列车辆 ID | `MISSING` | 仅显示数量，不伪造名单 |
| 来车 | `vehicle.destinationNode == shovel node` | `DERIVED` | 标注“目的地匹配”，不是 ETA |
| 相关任务 | Shovel decisions API | `DIRECT` | 按真实 shovelId 投影 |
| 异常反馈 | Alerts + Terminal Actions | `DIRECT` | 保留状态与处理结果 |

## 13. API 调用与刷新策略

| 数据 | 首次加载 | 实时更新 | 失败策略 |
|---|---|---|---|
| 身份 | `/auth/me` | 不轮询 | 401 转登录页 |
| 当前快照 | Snapshot REST | WebSocket | 断线前重新拉快照，显示重连状态 |
| 健康 | Health REST | 5–10 秒轮询 | 与现场 stale 分开显示 |
| Run/生产摘要 | Run + Summary REST | 10–30 秒或事件触发刷新 | 保留最后成功值并标注更新时间 |
| 决策 | Decisions REST | `decisionTrace` WS | 分页/限制数量 |
| 人工改派 | Manual Dispatch REST | 提交后刷新 | 状态不能乐观改为 EXECUTED |
| 告警/审计 | Operations REST | 操作后刷新，可低频轮询 | 错误明确显示 |
| 回放 | Replay + Events REST | 用户主动查询 | 只读，禁止触发推理或控制 |

## 14. 当前缺口登记

| ID | 冻结需求 | 当前状态 | 前端临时表达 | 后续责任 |
|---|---|---|---|---|
| `GAP-01` | 卸载到卸载完整 Cycle | 仅到铲至卸载八事件 | 作业阶段周期，`INSUFFICIENT` 于完整 Cycle | AnyLogic + 后端事件契约 |
| `GAP-02` | `DISPATCH_TRIGGERED` 与周期强关联 | 决策存在但无周期源引用 | 不拼接 | AnyLogic/后台关联字段 |
| `GAP-03` | 空载/满载运输开始事件 | 未采集 | 未知 | AnyLogic 事件采集 |
| `GAP-04` | 统一 `data_quality` | API 未提供 | 前端按规则派生并说明原因 | 后端 DTO 可选增强 |
| `GAP-05` | sim time 精确回放查询 | 当前按接收墙钟查询 | 双时间显示，标明查询口径 | 后端回放索引/API |
| `GAP-06` | 铲端当前/排队车辆 ID | 仅有数量 | 不展示车辆名单 | ShovelState 协议扩展 |
| `GAP-07` | 设备利用率 | 缺完整可用/作业区间 | 未采集 | 后续运行指标事件 |
| `GAP-08` | ETA/能耗/油耗 | 未采集 | 不显示 | 明确需求后再采集 |

## 15. 数据映射验收

进入 UI 设计前必须通过以下检查：

1. 每个可见业务字段能定位到本文的来源和等级；
2. `MISSING` 字段不会出现演示假值或默认 0；
3. stale 实体不会继续显示旧速度和旧实时状态；
4. 只有 `EXECUTED` 使用“已执行”；
5. Command 不被命名为独立 Task；
6. 当前 `completeCycles` 被标为“作业阶段完整周期”；
7. 周期事件按 sim time 和语义顺序展示；
8. 历史回放同时显示业务时间与查询墙钟时间；
9. 操作者声明与现场事实有清晰标签；
10. 所有缺口均按 `UNKNOWN/INSUFFICIENT` 降级，而不是由前端猜测补齐。

完成上述映射后，前端才能进入交互流程和视觉设计阶段。

# 生产运行追踪：第一批

本批提供调度员专用 REST 数据接口，不改变调度、控制、MQTT 或终端页面。
新增数据库迁移由 `RunTrackingRepository` 在原迁移完成后事务执行，并登记版本 5。

## 运行边界

登录后，启动实验前调用 `POST /api/v1/runs`：

```json
{
  "simulationId": "SIM_001",
  "scenarioId": "实际场景标识",
  "simulationStartSeconds": 0,
  "metadata": {
    "dataSource": "ANYLOGIC",
    "vehicleCount": 20,
    "shovelCount": 4,
    "mapVersion": null,
    "configurationVersion": null,
    "taskModelVersion": null,
    "controlModelVersion": null,
    "productionTarget": null
  }
}
```

返回 `run_id`。元数据由操作者填写，是声明快照，尚非现场自动验证信息。
不知道的字段用 null 或省略，禁止填入示例版本冒充实测。
同一实例仅允许一个未结束运行，重复开始返回 409。后台重启不产生新运行。
运行结束时调用 `POST /api/v1/runs/{runId}/finish`，请求体：

```json
{"simulationEndSeconds": 3600}
```

未采集真实仿真结束时间则发送 `{}`。结束操作可重复，不修改首次结束墙钟时间。
同一 simulationId 的实验重置必须先结束，再开始新运行。第一批不能自动检测实验重置。

## 查询

- `GET /api/v1/runs?simulationId=SIM_001`：最近 100 次运行。
- `GET /api/v1/runs/{runId}`：上下文。
- `GET /api/v1/runs/{runId}/events?limit=100`：事件，按墙钟时间和稳定 ID 升序。
- `GET .../events?vehicleId=0&commandId=实际指令ID`：单车/指令过滤。
- `GET .../events?afterMs=上一页末项wall_time_ms&afterId=上一页末项event_id`：下一页；limit 1..500。
- `GET .../evidence?eventId=实际事件ID`：原始证据，包括决策输入输出和校验/回执。
- `GET .../summary`：运行统计，非生产统计。
- `GET .../replay?atWallTimeMs=实际墙钟毫秒`：当时已持久化的最新地图、车辆、铲端状态。

新增接口均只允许 DISPATCHER；车端/铲端返回 403，未登录返回 401。
事件没有独立可写生命周期；稳定 event_id 来自来源表主键及事件类型。
事件不因查询/重新初始化而增加，回放不调用推理、不发布指令、不插值。

## 已实现边界与待验收项

事件投影覆盖决策、指令发送/回执、地图/车辆/铲端样本、终端操作和告警流转。
缺少 taskId/cycleId 的关联保持 null；不会将 EXECUTED 认作运输任务完成。
只有决策输入含真实 simTimeSeconds 或现场状态有仿真时间时才提供 sim_time_seconds。
指令/告警等只有墙钟时间的事件，其仿真时间为 null。

运行归属暂按持久化记录的墙钟时间区间 `[started_at_ms, ended_at_ms)` 划分。
这不是现场 runId 强关联：延迟消息和跨边界回执可能落入后一次运行。
正式现场关联需第二批由 AnyLogic 上报 runId/cycleId 和作业事件，不猜测归属。
回放仅包含本运行收到的样本；地图仅在运行前发布时，回放地图可为空。
缺失区间不补造，返回样本 received_at_ms，调用方据此展示数据年龄。
同一次查询的运行统计只计数据库现有证据；不是消息接收总量或推理调用总量。
未归属实例的 audit_log 不混入事件；人工改派通过原决策记录查询。
证据接口返回来源记录的当前内容，不冒充当时完整记录快照。

第二批待完成：装卸/排队/到达事件、真实 taskId/cycleId、完整审计关联、
生产量与完整周期统计、现场版本快照、决策时原始特征与候选快照。
模型原因仍为未知，现有输入输出是追溯依据，不是因果解释。

验证：RunTrackingIntegrationTest 使用隔离 SQLite 和测试证据，不代表真实生产验收。
真实 AnyLogic 单车全过程、跨重启回放及运行边界精确关联均须现场验收。

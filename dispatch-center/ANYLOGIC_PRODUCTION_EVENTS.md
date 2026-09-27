# 第二批作业事件接入（后台已接线，现场待验收）

原 `.alp` 只读。后台已复用 MqttIngestionClient 订阅作业事件，拒收写入 message_rejections。
部署新 JAR 后才会生效，尚未替换当前现场进程。
具体模型变量、函数体及调用位置见 `ANYLOGIC_PRODUCTION_EVENT_CODE.md`。

## 现场消息契约

主题 `mine/sim/{simulationId}/event/production/{vehicleId}`，JSON UTF-8，QoS 1，非 retained。

```json
{
  "schemaVersion":1,
  "eventId":"一次事件的稳定UUID",
  "runId":"后台创建并返回的真实run_id",
  "simulationId":"SIM_001",
  "cycleId":"本次装载到卸载的稳定UUID",
  "vehicleId":0,
  "eventType":"DUMP_FINISH",
  "siteId":0,
  "simTimeSeconds":1200,
  "sentWallTimeMs":1790000000000,
  "commandId":null,
  "payloadTons":100,
  "payloadGrade":0.2,
  "producesOre":true,
  "productionEligible":true
}
```

数字仅说明格式，禁止用作演示数据。payloadGrade 为 0..1 比例。
除 DUMP_FINISH 外，载荷字段可为 null；commandId 不知道就保持 null，不能编造 taskId。
重发必须保持全部原字段（包括 eventId、sentWallTimeMs），不要重新生成事件。
同一周期同一事件类型只能有一条；不同内容的重复事件会拒绝，不能覆盖历史。
晚到事件按现场 runId 归属，验证 sentWallTimeMs 在该运行起止区间内。

## AnyLogic 副本手动步骤

1. 新建 Main 参数 `productionRunId`（String），设置为本次后台返回的 run_id。
   每次新实验先创建新运行；结束运行后不得沿用旧 ID。
2. Truck 新增 String 变量 `productionCycleId`，初始 null。
3. Main 新增 void 函数 `emitProductionEvent`，参数 `Truck t, String type, int siteId`。
   使用 ANYLOGIC_PRODUCTION_EVENT_CODE.md 的函数体写入 JSON，无需额外 JSON 库；
   时间显式用 time(SECOND)，墙钟用 System.currentTimeMillis()。
   fieldClient.publish(topic, UTF-8 bytes, 1, false)，与当前真实上报复用客户端。
   异常只记录，不改变调度、状态转换、载荷或速度。
4. 在 LoadSite.seize 的 On enter，现有 `if (!t.isWaitingForLoadResource)` 内：
   若 cycleId 为 null，创建 UUID；记录 ARRIVE_SHOVEL 和 QUEUE_LOAD。
   此处入流程时间作为“进入铲端作业流程”，不是精确到达地理节点的时间。
5. LoadSite.delay 的 On enter，原 loadStartTime 赋值后记录 LOADING_START。
6. Truck.onLoadingFinished(LoadSite ls) 完成 payloadTons/payloadGrade 赋值后，
   记录 LOADING_FINISH；不要接到被 ExcludeFromBuild 的 Loading 状态。
7. DumpSite.seize 的 On enter，已有 `dumpQueueEnterTime` 赋值后，
   记录 ARRIVE_DUMP 和 QUEUE_DUMP。周期为空（中途才开始采集）的车辆不伪造前序事件，跳过。
8. DumpSite.delay 的 On enter，原 dumpServiceStartTime 赋值后记录 DUMP_START。
9. Truck.onDumpingFinished(DumpSite ds)，在 `isLoaded = false` 与载荷清零之前，
   用原 payloadTons/payloadGrade/payloadIsOre 记录 DUMP_FINISH。
   productionEligible 必须复用实际业务条件：
   `time(SECOND) < main.rlShiftSeconds` 且目标接受对应物料
   （矿石要求 `ds.acceptsOre && ds.siteId == 0`，废石要求 `ds.acceptsWaste`）。
   不额外引入当前业务没有的预热剔除规则。
   记录后清空 productionCycleId，下次进入 LoadSite.seize 再生成新周期。

发布时先构造一个事件并保留原始字节；若断线，需在独立待发送集合保留原字节并重试。
不得在重试时改时间、eventId 或载荷。当前后台不提供应用级接收确认，MQTT PUBACK
仅代表 Broker 接收；发送缓存及应用确认仍待现场集成，未承诺断线零丢失。

## 查询与统计口径

仅调度员可读取：

- GET `/api/v1/runs/{runId}/production/events?vehicleId=0&limit=100`
- GET `/api/v1/runs/{runId}/production/summary`

统计只包括八事件齐全、仿真时间非递减且 productionEligible=true 的周期。
不完整、时间反序及不满足生产计入条件的周期分别排除；没有有效完整周期时吨数/时长为 null。
时长定义为“进入铲端流程→卸载完成”，不是完整派单到派单循环时间。
利用率、到达 ETA、卸载后下一任务关联尚未实现。
作业事件已合并到现有 /runs/{id}/events 和 evidence，提供 related_cycle_id 和原始事件证据。
决策类事件按原 decision_trace 创建时间归属运行，回执不再单凭回执时间混入后一次运行。
未带现场 runId 的状态样本仍按时间归属，不能据此宣称所有数据精确关联。
已有 /runs/{id}/summary 仍为运行统计，不自动替换成生产统计。



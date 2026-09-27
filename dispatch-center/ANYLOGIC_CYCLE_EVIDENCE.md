# 完整运输证据接入与 AnyLogic 修改说明

## 本次范围

只增加观测采集，保留选点、路径、装卸、生产计量和人工改派逻辑。已修改仓库的 fusion_openpit_mine.original.alp；不要在 AnyLogic 打开未保存版本后覆盖这些修改。

## AnyLogic 修改位置

1. Truck 新增 String（允许 null） 变量 productionPreviousCycleId，初值 null，保存快照。productionCycleId、productionTaskCommandId 等原变量保留。
2. Truck → TravelToLoadSite → MoveSegment → Entry action：在有效道路节点的 moveTo(node) 之后，若 productionCycleId 为空则创建 UUID，然后调用 main.emitProductionEvent(this, "HAUL_EMPTY_START", targetLoadSiteId)。每段都会经过此位置，原有 eventId 去重只记录本周期首次运动调用，不将路径重绑定当作再次出发。
3. Truck → TravelToDumpSite → MoveSegment1 → Entry action：在 moveTo(node) 后调用 main.emitProductionEvent(this, "HAUL_FULL_START", targetDumpSiteId)。保持原有运动逻辑。
4. Truck → onDumpingFinished：先发送 DUMP_FINISH，再把 productionCycleId 保存到 productionPreviousCycleId，最后清空 productionCycleId。保留原有生产计量及载荷清理顺序。
5. Main → emitProductionEvent：JSON 增加 previousCycleId，值取 Truck.productionPreviousCycleId。空值发 null，沿用 MQTT topic、发送队列与 QoS 1。LoadSite onEnter 的 Cycle ID 创建仍作缺少出发事件时的回退，不补造出发时间。

出发事件表示成功调用运动 API 的时刻，不宣称 GPS 速度首次大于零。siteId 表示此运输阶段的目标站点；前端源 JSON 显示完整证据。

## 后端和前端

- 接受 HAUL_EMPTY_START、HAUL_FULL_START，previousCycleId 保存在现有 payload_json 中，不改变表结构及八事件统计口径。
- 新增事件仍按本周期类型幂等；调度指令不进入生产事件表，不受该唯一约束影响。
- 查询前周期仅使用同 Run、同车辆、明确 previousCycleId 的 DUMP_FINISH，校验时间顺序。无引用、引用未到达、冲突和倒序分开报告，不用临近时间匹配。
- 周期故事返回 previousUnload、previousCycleStatus、stageComplete、transportComplete、fullCycleComplete。完整运输十事件和八事件作业阶段分开判断。
- 调度节点展示关联指令及生成/发送/回执墙钟时间，不能把墙钟时间当仿真时间。当前沿用源事件 commandId / previousCommandId 引用，不声称保留任意次数改派的全部历史。
- 前端保留十二节点；新增两个出发事件和前周期卸载引用由实际证据点亮，源事件可展开 JSON。旧数据不补造。
- fullCycleComplete 还要求前周期卸载证据存在、所有源引用调度决策已解析并有 EXECUTED 回执。无前周期引用的首轮或历史记录保持部分证据，不推断为首次运行。

## 操作与验收

1. 先正常停止旧后台，使用原环境配置重新启动打包后的后台；前端 Ctrl+Shift+R。
2. 在 AnyLogic 关闭旧模型并重新打开仓库中的 fusion_openpit_mine.original.alp，执行 Check model。此环境未执行 AnyLogic 编译，若报错请保留错误原文。
3. 启动新的 Run，确保模型使用该真实 runId，再运行仿真。不要继续旧 Run 来验证新协议。
4. 首轮检查两个出发事件、八个装卸事件与调度证据；第二轮检查 previousCycleId 指向同车前轮，并能展示前轮卸载完成。
5. 做一次人工改派，核对已有 Command 前后引用和 EXECUTED 回执；改派不得重建当前 Cycle。
6. 检查无排队同刻事件、消息重复、重连、仿真结束。旧 Run 应仍显示未采集而非完整。

## 验证边界

后端集成测试使用临时数据库，不写现场数据库。仓库模型 XML 校验不能替代 AnyLogic 编译与真实 MQTT 联调；新事件是否到达现场库须由新 Run 验收确认。

## 手动添加：可直接粘贴代码

以下以已有八事件和 Command 关联采集为前提。仓库模型已经包含这些修改；如果你打开的模型已经有同样内容，不要重复添加。先另存一份模型备份，再按 1–5 操作。

### 1. Truck 新建变量

在 Truck 中拖入 Variable，属性分别设置（不是往初始值栏粘贴 Java 声明）：

- Name：`productionPreviousCycleId`
- Type：`String`
- Initial value：`null`（不加引号）
- Access：`public`，非 static、非 constant；如有快照保存选项，保持启用。

`nullable String` 是说明文字，不是 Java 类型；类型栏只填 `String`。现有 `productionCycleId`、`productionTaskCommandId`、`productionPreviousCommandId` 保留，不要重复创建。

### 2. 替换空载运动 Entry action

位置：Truck → TravelToLoadSite → MoveSegment → Entry action。将该动作原内容整体替换为下面代码，不要追加到原动作后面。

```java
if (targetNodeName == null){
	//traceln("targetNode is null");
}
PointNode node = main.getRoadPointNode(targetNodeName);

if (node == null) {
    //traceln("Cannot move, node not found: " + targetNodeName);
    return;
}
moveTo(node);
if (productionCycleId == null) productionCycleId = java.util.UUID.randomUUID().toString();
main.emitProductionEvent(this, "HAUL_EMPTY_START", targetLoadSiteId);
debugLastAction = "ArriveLoad_" + targetNodeName;
```

### 3. 替换满载运动 Entry action

位置：Truck → TravelToDumpSite → MoveSegment1 → Entry action。整体替换该动作内容。满载出发沿用已有 Cycle，不创建新 Cycle。

```java
if (targetNodeName == null){
	traceln("targetNode is null");
}
PointNode node = main.getRoadPointNode(targetNodeName);

if (node == null) {
    traceln("Cannot move, node not found: " + targetNodeName);
    return;
}

moveTo(node);
main.emitProductionEvent(this, "HAUL_FULL_START", targetDumpSiteId);
debugLastAction = "ArriveLoad_" + targetNodeName;
```

### 4. 替换 onDumpingFinished 函数体

位置：Truck → Functions → onDumpingFinished。保留原函数名、返回类型 void 和参数 `ds`（类型 `DumpSite`）。只替换函数体，不要粘贴方法声明或最外层大括号。下面是当前仓库完整函数体，包含原有生产计量；如果你的模型在这段函数内还有额外业务修改，仅把 `productionPreviousCycleId = productionCycleId;` 插入已有 DUMP_FINISH 发送之后、清空 productionCycleId 之前，不覆盖额外逻辑。

```java
targetDump = ds;

dumpFinishedFlag = true;
hasEnteredCurrentProcess = false;

DumpSite d =
    main.getDumpSiteById(currentDumpSiteId);

double nowSec = time(SECOND);

if (d == null) {
    throw new IllegalStateException(
        "Unknown dump site: "
        + currentDumpSiteId
    );
}

// 28800 秒时完成的卸载不计入本班
if (nowSec < main.rlShiftSeconds) {

    if (
        payloadIsOre
        && d.acceptsOre
        && d.siteId == 0
    ) {
        main.totalProduction += payloadTons;

        main.updateCrusherGrade(
            payloadTons,
            payloadGrade
        );

        d.receivedTons += payloadTons;

    } else if (
        !payloadIsOre
        && d.acceptsWaste
    ) {
        main.totalWasteMoved += payloadTons;
        d.receivedTons += payloadTons;
    }
}

d.busy = false;

isLoaded = false;

if (productionCycleId != null) {
    main.emitProductionEvent(this, "DUMP_FINISH", ds.siteId);
    productionPreviousCycleId = productionCycleId;
    productionCycleId = null;
}

productionTaskCommandId = null;
productionPreviousCommandId = null;

payloadIsOre = false;
payloadGrade = 0.0;
payloadTons = 0.0;

isWaitingForDumpResource = false;
executionPhase = "COMPLETED";

productionTaskCommandId = null;
productionPreviousCommandId = null;
debugLastAction = "FinishDump";
currentNode = destinationNode;

```

### 5. 替换 Main.emitProductionEvent 函数体

位置：Main → Functions → emitProductionEvent。返回类型 void；参数依次为 `t: Truck`、`type: String`、`siteId: int`。保留函数属性，只把下面代码粘贴到函数体。它依赖已有八事件采集中的 `productionRunId`、`fieldSimId`、`productionEmitted`、`productionOutbox`、`manualJsonEscape` 等成员，不需要新建重复函数或 MQTT 客户端。

```java
if (productionRunId == null || !productionRunId.matches("[A-Za-z0-9_-]{1,80}")
        || fieldSimId == null || !fieldSimId.matches("[A-Za-z0-9_-]{1,64}")
        || t.productionCycleId == null) {
    traceln("[Production] missing real run/cycle identity");
    return;
}
String eventId = t.productionCycleId + "-" + type;
if (!productionEmitted.add(eventId)) return;
boolean finish = "DUMP_FINISH".equals(type);
String tons = "null", grade = "null", ore = "null", eligible = "null";
if (finish) {
    if (!Double.isFinite(t.payloadTons) || t.payloadTons < 0
            || !Double.isFinite(t.payloadGrade) || t.payloadGrade < 0 || t.payloadGrade > 1) {
        productionEmitted.remove(eventId);
        traceln("[Production] invalid original payload");
        return;
    }
    DumpSite ds = getDumpSiteById(siteId);
    boolean counted = ds != null && time(SECOND) < rlShiftSeconds
        && ((t.payloadIsOre && ds.acceptsOre && ds.siteId == 0)
            || (!t.payloadIsOre && ds.acceptsWaste));
    tons = Double.toString(t.payloadTons);
    grade = Double.toString(t.payloadGrade);
    ore = Boolean.toString(t.payloadIsOre);
    eligible = Boolean.toString(counted);
}
String commandJson = t.productionTaskCommandId == null ? "null"
    : "\"" + manualJsonEscape(t.productionTaskCommandId) + "\"";
String previousJson = t.productionPreviousCommandId == null ? "null"
    : "\"" + manualJsonEscape(t.productionPreviousCommandId) + "\"";
String previousCycleJson = t.productionPreviousCycleId == null ? "null"
    : "\"" + manualJsonEscape(t.productionPreviousCycleId) + "\"";
String payload = String.format(java.util.Locale.ROOT,
    "{\"schemaVersion\":1,\"eventId\":\"%s\",\"runId\":\"%s\","
    + "\"simulationId\":\"%s\",\"cycleId\":\"%s\",\"vehicleId\":%d,"
    + "\"eventType\":\"%s\",\"siteId\":%d,\"simTimeSeconds\":%.9f,"
    + "\"sentWallTimeMs\":%d,\"commandId\":%s,\"previousCommandId\":%s,\"previousCycleId\":%s,\"payloadTons\":%s,"
    + "\"payloadGrade\":%s,\"producesOre\":%s,\"productionEligible\":%s}",
    eventId, productionRunId, fieldSimId, t.productionCycleId, t.truckId,
    type, siteId, time(SECOND), System.currentTimeMillis(), commandJson, previousJson, previousCycleJson,
	tons, grade, ore, eligible);
String topic = "mine/sim/" + fieldSimId + "/event/production/" + t.truckId;
productionOutbox.putIfAbsent(eventId, new String[]{topic, payload});
// 由独立 cyclic event 发送，不在控制/装卸回调中等待 MQTT。
```

### 6. 保留的代码与检查顺序

- LoadSite 到达回调的 Cycle ID 判空创建保留，正常情况下会沿用空载出发时创建的 ID。
- 自动派单和人工改派已有的 Command 引用更新保持不变；改派时不要清空或重建 Cycle ID。
- `flushProductionEvents`、MQTT Topic、QoS、八事件采集回调不变。每段运动都会调用采集函数，但 eventId 去重只保留每周期第一次对应出发事件。
- 保存后执行 Check model；后台必须使用本次已打包的新版本。创建新 Run，再启动模型，至少运行两个周期并做一次人工改派。
- 首轮没有前周期引用时不构造上次卸载事件；第二轮 `previousCycleId` 应对应前轮实际卸载完成。
- 不要往 AnyLogic 粘贴 Markdown 的三反引号；仅复制代码框内容。不要直接编辑现场 SQLite 数据补齐事件。

# AnyLogic 手工接入：自动派单追踪与 500 ms 控制下发

> `fusion_openpit_mine.original.alp` 保持只读。以下修改只在 AnyLogic IDE
> 中对模型副本执行。代码基于当前模型已有的 `buildObservation(t)`、
> `get_all_observations()`、`applyInferenceAccelerations(...)`、现场 MQTT 和人工改派。

后台使用上传的 `DRL-Dispatching-1.2.0.jar` 与 `SpeedControl-1.0.0.jar`
调用真实 gRPC 服务。AnyLogic 不再直接调用两个推理服务：它上送原始 protobuf
观测，接收经过后台校验且带指令编号的 MQTT 指令并回执。

## 1. Main 新增变量

在 `Main > Variables` 新增：

```java
// MQTT 回调线程只写这个 Map；仿真线程在派单点读取。
java.util.concurrent.ConcurrentHashMap<String, String> autoDispatchResponses =
    new java.util.concurrent.ConcurrentHashMap<String, String>();
```

在 `Truck > Variables` 新增：

```java
public String pendingAutoCommandId = "";
```

已有的 `manualDispatchQueue` 继续复用；速度指令进入该队列并在仿真线程执行。

## 2. 修改 `startFieldMqtt()` 中的订阅与回调

把 `connectComplete` 中原单主题 `subscribe(...)` 替换为：

```java
fieldClient.subscribe(
    new String[] {
        "mine/sim/" + fieldSimId + "/command/manual-dispatch/+",
        "mine/sim/" + fieldSimId + "/command/auto-dispatch/+",
        "mine/sim/" + fieldSimId + "/command/speed/+"
    },
    new int[] { 1, 1, 1 }
).waitForCompletion(5000);
traceln("[DecisionGateway] command subscriptions ready");
```

把 `messageArrived` 替换为：

```java
final String commandTopic = topic;
final String json = new String(
    message.getPayload(), java.nio.charset.StandardCharsets.UTF_8
);

if (topic.contains("/command/auto-dispatch/")) {
    // 这里只写线程安全 Map，不访问 Truck 或其他仿真对象。
    String requestId = manualJsonString(json, "requestId");
    autoDispatchResponses.put(requestId, json);
} else {
    // 人工改派和速度控制都交给仿真线程事件处理。
    manualDispatchQueue.offer(new String[] { commandTopic, json });
}
```

## 3. Main 新增 JSON 与通用回执函数

### `decisionJsonDouble(json:String, key:String) : double`

```java
java.util.regex.Matcher m = java.util.regex.Pattern.compile(
    "\\\"" + java.util.regex.Pattern.quote(key)
    + "\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)"
).matcher(json);
if (!m.find()) throw new IllegalArgumentException("missing " + key);
double value = Double.parseDouble(m.group(1));
if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite " + key);
return value;
```

### `decisionJsonLong(json:String, key:String) : long`

```java
java.util.regex.Matcher m = java.util.regex.Pattern.compile(
    "\\\"" + java.util.regex.Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)"
).matcher(json);
if (!m.find()) throw new IllegalArgumentException("missing " + key);
return Long.parseLong(m.group(1));
```

### `publishDecisionResult(commandId:String, status:String, message:String) : void`

```java
String json = "{"
    + "\"commandId\":\"" + manualJsonEscape(commandId) + "\","
    + "\"simulationId\":\"" + manualJsonEscape(fieldSimId) + "\","
    + "\"status\":\"" + manualJsonEscape(status) + "\","
    + "\"message\":\"" + manualJsonEscape(message) + "\","
    + "\"executedAtMs\":" + System.currentTimeMillis()
    + "}";
try {
    fieldClient.publish(
        "mine/sim/" + fieldSimId + "/result/decision/" + commandId,
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        1, false
    ).waitForCompletion(3000);
} catch (Exception e) {
    traceln("[DecisionGateway] result publish failed: " + e.getMessage());
}
```

## 4. 自动派单：上送真实 27 维请求并等待后台指令

新增 `requestDispatchThroughBackend(request:DispatchRequest, t:Truck) : int`：

```java
if (fieldClient == null || !fieldClient.isConnected()) {
    throw new IllegalStateException("field MQTT is not connected");
}
String requestId = request.getRequestId();
try {
    fieldClient.publish(
        "mine/sim/" + fieldSimId + "/trigger/dispatch",
        request.toByteArray(), 1, false
    ).waitForCompletion(3000);
} catch (org.eclipse.paho.client.mqttv3.MqttException e) {
    // MqttPersistenceException 是 MqttException 的子类，也由这里覆盖。
    throw new IllegalStateException(
        "dispatch trigger publish failed: " + e.getMessage(), e
    );
}

long deadline = System.currentTimeMillis() + dispatchInferenceTimeoutMs;
String json = null;
while (System.currentTimeMillis() < deadline) {
    json = autoDispatchResponses.remove(requestId);
    if (json != null) break;
    try { Thread.sleep(5); }
    catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("dispatch wait interrupted");
    }
}
if (json == null) throw new IllegalStateException("dispatch backend timeout");

String commandId = manualJsonString(json, "commandId");
int responseTruckId = manualJsonInt(json, "vehicleId");
int action = manualJsonInt(json, "action");
String targetNode = manualJsonString(json, "targetNode");
String expectedTargetNode =
    action >= 0 && action < collectionLoadSites.size()
    ? collectionLoadSites.get(action).getName()
    : "";
if (responseTruckId != t.truckId || action < 0 || action > 3
        || getLoadSiteById(action) == null
        || !expectedTargetNode.equals(targetNode)) {
    publishDecisionResult(commandId, "REJECTED",
        "command mismatch: action=" + action
            + ", expected=" + expectedTargetNode
            + ", actual=" + targetNode);
    throw new IllegalStateException("invalid dispatch command");
}
t.pendingAutoCommandId = commandId;
return action;
```

在 `selectNextLoadSiteAI(t)` 中：

1. 把开头对 `rlClient` 的整段检查替换为下面代码（注意是 `== null` 和
   `!isConnected()`，不要保留 `rlClient.isReady()`）：

```java
if (!enableOnnxDispatch
        || fieldClient == null
        || !fieldClient.isConnected()) {
    throw new IllegalStateException(
        "Dispatch backend MQTT is unavailable"
    );
}
```

2. 保留现有 27 维 `Observation`、reward、episode、sequence 和
   `DispatchRequest request` 构造；
3. 用下面一行替换 `rlClient.getDispatchResult(request)` 到 `int action = ...` 的代码：

```java
int action = requestDispatchThroughBackend(request, t);
```

其后的 action 校验、reward 消费和 `return action` 原样保留。

在 `Truck > statechart > NeedDispatchToLoadSite` Entry Action 中，必须等
`targetLoadSiteId`、`targetShovel`、`destinationNode`、`intDestination` 和
`targetSiteBusinessId` 全部设置完成后再插入；不要放在 `destinationNode = ...`
之前：

```java
if (pendingAutoCommandId != null && !pendingAutoCommandId.isEmpty()) {
    main.publishDecisionResult(
        pendingAutoCommandId, "EXECUTED",
        "task applied: targetLoadSiteId=" + targetLoadSiteId
            + ", destinationNode=" + destinationNode
    );
    pendingAutoCommandId = "";
}
```

这使“卸载完成 → 真实 27 维推理 → 后台校验/编号 → 车端收到 → 应用任务 → 回执”
成为一条可核对链路。

## 5. 速度控制：500 ms 上送与执行

将现有 0.5 秒循环速度事件中直接调用 `speedInferenceClient` 的代码替换为：

```java
for (Truck t : trucks) {
    if (t != null) t.updateBasicSpeedState();
}
updateFrontRearVehicles();
updateConflictVehicles();

try {
    Fusion.grpc.ObservationDict snapshot = get_all_observations();
    if (fieldClient == null || !fieldClient.isConnected()) {
        throw new IllegalStateException("field MQTT is not connected");
    }
    fieldClient.publish(
        "mine/sim/" + fieldSimId + "/trigger/speed",
        snapshot.toByteArray(), 1, false
    );
} catch (Exception e) {
    traceln("[SpeedGateway] trigger failed: " + e.getMessage());
}
```

新增 `handleSpeedCommand(topic:String, json:String) : void`：

```java
String commandId = topic.substring(topic.lastIndexOf('/') + 1);
try {
    if (!commandId.equals(manualJsonString(json, "commandId"))
            || !fieldSimId.equals(manualJsonString(json, "simulationId"))) {
        throw new IllegalArgumentException("topic/payload identity mismatch");
    }
    int truckId = manualJsonInt(json, "vehicleId");
    String agentId = manualJsonString(json, "agentId");
    double acceleration = decisionJsonDouble(json, "accelerationMps2");
    long issuedAtMs = decisionJsonLong(json, "issuedAtMs");
    long validForMs = decisionJsonLong(json, "validForMs");
    if (!agentId.equals("agent_" + truckId)) {
        throw new IllegalArgumentException("agent/vehicle mismatch");
    }
    if (System.currentTimeMillis() - issuedAtMs > validForMs) {
        publishDecisionResult(commandId, "EXPIRED", "speed command expired");
        return;
    }
    Fusion.grpc.ActionDict actions = Fusion.grpc.ActionDict.newBuilder()
        .addActions(Fusion.grpc.Action.newBuilder()
            .setAgentId(agentId).addAction((float) acceleration))
        .build();
    applyInferenceAccelerations(actions, 0.5);
    publishDecisionResult(commandId, "EXECUTED",
        "acceleration applied: " + acceleration + " m/s2");
} catch (Exception e) {
    publishDecisionResult(commandId, "REJECTED", e.getMessage());
}
```

把 `processManualDispatchQueue()` 的处理分支改为：

```java
if (command[0].contains("/command/speed/")) {
    handleSpeedCommand(command[0], command[1]);
} else {
    handleManualDispatchCommand(command[0], command[1]);
}
```

事件每次最多处理 500 条的现有上限可保留。不要在 MQTT 回调中直接改车辆速度。

### 仿真速度与控制频率

模型的基础 `ModelTimeUnit` 是 `Minute`，但速度事件、状态发布事件都显式配置为
`0.5 SECOND`，消息时间也使用 `time(SECOND)`，因此协议时间统一是秒，不需要把
模型基础单位改为秒。需要注意：0.5 秒是**仿真时间**。如果实验以快于 1× 的速度
运行，墙钟每秒会产生多于两帧。后台会自动覆盖尚未处理的旧速度观测，只推理最新
完整帧，避免积压；现场演示仍建议使用 real time / 1×。

## 6. AnyLogic 编译与现场检查

1. 确认模型副本仍包含上传的两个 JAR 与 `field-uplink` JAR；Build 无错误。
2. 运行后 Console 出现 `[DecisionGateway] command subscriptions ready`。
3. 订阅 `mine/sim/SIM_001/trigger/#`，应看到派单事件和每 0.5 秒速度观测。
4. 订阅 `mine/sim/SIM_001/command/#` 与 `result/decision/#`，每条后台指令都应有唯一
   UUID；成功执行应有 `EXECUTED` 回执。
5. 不要保留原速度事件中的直接 gRPC 调用，否则会发生两套控制同时写速度。

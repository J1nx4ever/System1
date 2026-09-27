# AnyLogic 人工改派手工修改（基于 fusion_openpit_mine.original.alp）

基线：AnyLogic 8.9.0；只在 AnyLogic IDE 中修改模型副本，不编辑
`fusion_openpit_mine.original.alp`。以下名称均与该基线一致。

## 1. Main：新增指令队列与仿真事件

### 新增 Variable

位置：`Main > Variables`。

- `manualDispatchQueue`：类型 `java.util.concurrent.ConcurrentLinkedQueue<String[]>`；初始值 `new java.util.concurrent.ConcurrentLinkedQueue<String[]>()`。

队列元素为长度为 2 的数组：`[topic, json]`。MQTT 回调运行在 Paho 工作线程，只负责入队；不要从 MQTT 回调中直接读写车辆或其他仿真对象。

### 新增 Event

位置：`Main > Events`。

- 名称：`evProcessManualDispatchQueue`
- Trigger：`Timeout`
- Mode：`Cyclic`
- 首次触发：`0.1` 秒
- 循环间隔：`0.1` 秒
- Action：调用 `processManualDispatchQueue();`

### 新增 Function：`processManualDispatchQueue`

返回类型 `void`；无参数。

```java
int handled = 0;
while (handled < 100) {
    String[] command = manualDispatchQueue.poll();
    if (command == null) break;

    try {
        handleManualDispatchCommand(command[0], command[1]);
    } catch (Exception e) {
        traceln("[ManualDispatch] command processing failed: " + e.getMessage());
    }
    handled++;
}
```

每次最多处理 100 条，避免一次事件因队列积压而长时间占用仿真线程。

## 2. Main：替换 `startFieldMqtt()` 函数 Body

位置：`Main > Functions > startFieldMqtt`。

```java
try {
    fieldClient = new org.eclipse.paho.client.mqttv3.MqttAsyncClient(
        fieldBrokerUri,
        "anylogic-" + fieldSimId,
        new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence()
    );

    org.eclipse.paho.client.mqttv3.MqttConnectOptions options =
        new org.eclipse.paho.client.mqttv3.MqttConnectOptions();
    options.setAutomaticReconnect(true);
    options.setCleanSession(true);
    options.setMaxInflight(1000);
    options.setUserName("field_uplink");

    String mqttPassword = System.getenv("FIELD_MQTT_PASSWORD");
    if (mqttPassword == null || mqttPassword.isEmpty()) {
        throw new IllegalStateException("FIELD_MQTT_PASSWORD environment variable is not set");
    }
    options.setPassword(mqttPassword.toCharArray());

    fieldClient.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallbackExtended() {
        public void connectComplete(boolean reconnect, String serverURI) {
            try {
                fieldClient.subscribe(
                    "mine/sim/" + fieldSimId + "/command/manual-dispatch/+", 1
                ).setActionCallback(new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                    public void onSuccess(
                            org.eclipse.paho.client.mqttv3.IMqttToken token) {
                        traceln("[ManualDispatch] command subscription ready");
                    }

                    public void onFailure(
                            org.eclipse.paho.client.mqttv3.IMqttToken token,
                            Throwable cause) {
                        traceln("[ManualDispatch] subscribe failed: " +
                            (cause == null ? "unknown" : cause.getMessage()));
                    }
                });
            } catch (Exception e) {
                traceln("[ManualDispatch] subscribe failed: " + e.getMessage());
            }
        }

        public void connectionLost(Throwable cause) {
            traceln("[FieldMQTT] connection lost: " +
                (cause == null ? "unknown" : cause.getMessage()));
        }

        public void messageArrived(String topic,
                org.eclipse.paho.client.mqttv3.MqttMessage message) {
            final String commandTopic = topic;
            final String json = new String(
                message.getPayload(), java.nio.charset.StandardCharsets.UTF_8
            );
            Main.this.manualDispatchQueue.offer(
                new String[] { commandTopic, json }
            );
        }

        public void deliveryComplete(
                org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) { }
    });

    fieldClient.connect(options).waitForCompletion(5000);
    traceln("[FieldMQTT] connected");
} catch (Exception e) {
    traceln("[FieldMQTT] connect failed: " + e.getMessage());
}
```

## 3. Main：新增 5 个 Function

### `manualJsonString`

返回类型 `String`；参数 `json:String`、`key:String`。

```java
java.util.regex.Matcher m = java.util.regex.Pattern.compile(
    "\\\"" + java.util.regex.Pattern.quote(key) +
    "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\""
).matcher(json);
if (!m.find()) throw new IllegalArgumentException("missing " + key);
return m.group(1);
```

### `manualJsonInt`

返回类型 `int`；参数 `json:String`、`key:String`。

```java
java.util.regex.Matcher m = java.util.regex.Pattern.compile(
    "\\\"" + java.util.regex.Pattern.quote(key) +
    "\\\"\\s*:\\s*(-?\\d+)"
).matcher(json);
if (!m.find()) throw new IllegalArgumentException("missing " + key);
return Integer.parseInt(m.group(1));
```

### `manualJsonEscape`

返回类型 `String`；参数 `value:String`。

```java
if (value == null) return "";
return value.replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "\\r")
    .replace("\n", "\\n");
```

### `publishManualDispatchResult`

返回类型 `void`；参数依次为 `commandId:String`、`truckId:int`、
`targetNode:String`、`status:String`、`message:String`。

```java
String json = "{"
    + "\"commandId\":\"" + manualJsonEscape(commandId) + "\","
    + "\"simulationId\":\"" + manualJsonEscape(fieldSimId) + "\","
    + "\"vehicleId\":" + truckId + ","
    + "\"targetNode\":\"" + manualJsonEscape(targetNode) + "\","
    + "\"status\":\"" + manualJsonEscape(status) + "\","
    + "\"message\":\"" + manualJsonEscape(message) + "\","
    + "\"executedAtMs\":" + System.currentTimeMillis()
    + "}";
try {
    if (fieldClient == null || !fieldClient.isConnected()) {
        throw new IllegalStateException("MQTT not connected");
    }
    fieldClient.publish(
        "mine/sim/" + fieldSimId + "/result/manual-dispatch/" + commandId,
        json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        1, false
    );
} catch (Exception e) {
    traceln("[ManualDispatch] result publish failed: " + e.getMessage());
}
```

### `handleManualDispatchCommand`

返回类型 `void`；参数 `topic:String`、`json:String`。

```java
String commandId = topic.substring(topic.lastIndexOf('/') + 1);
String targetNode = "";
int truckId = -1;
try {
    String payloadCommandId = manualJsonString(json, "commandId");
    String simulationId = manualJsonString(json, "simulationId");
    targetNode = manualJsonString(json, "targetNode");
    truckId = manualJsonInt(json, "vehicleId");

    if (!commandId.equals(payloadCommandId) || !fieldSimId.equals(simulationId)) {
        throw new IllegalArgumentException("topic/payload identity mismatch");
    }

    Truck truck = null;
    for (Truck candidate : trucks) {
        if (candidate.truckId == truckId) { truck = candidate; break; }
    }
    if (truck == null) throw new IllegalArgumentException("vehicle not found");
    if (truck.pendingManualCommandId != null &&
            !truck.pendingManualCommandId.isEmpty()) {
        throw new IllegalStateException("vehicle already has a pending command");
    }
    String phase = truck.statechart.getState() == null
        ? "" : truck.statechart.getState().name();
    boolean travelling = phase.startsWith("PrepareRoute")
        || phase.startsWith("MoveSegment")
        || phase.startsWith("ArriveSegmentEnd")
        || phase.startsWith("CheckDestination")
        || phase.startsWith("TravelToLoadSite")
        || phase.startsWith("TravelToDumpSite");
    if (!travelling) {
        throw new IllegalStateException("vehicle is not travelling: " + phase);
    }

    boolean loadTarget = targetNode.equals("ShovelOreHigh1")
        || targetNode.equals("ShovelOreHigh2")
        || targetNode.equals("ShovelOreLow")
        || targetNode.equals("ShovelWaste");
    boolean dumpTarget = targetNode.equals("Crusher")
        || targetNode.equals("dumpNode");
    if ((!truck.isLoaded && !loadTarget) || (truck.isLoaded && !dumpTarget)) {
        throw new IllegalArgumentException("target incompatible with payload state");
    }
    if (getRoadPointNode(targetNode) == null) {
        throw new IllegalArgumentException("target node not found");
    }

    truck.pendingManualCommandId = commandId;
    truck.pendingManualTargetNode = targetNode;
    traceln("[ManualDispatch] queued command=" + commandId
        + " truck=" + truckId + " target=" + targetNode);
} catch (Exception e) {
    publishManualDispatchResult(commandId, truckId, targetNode,
        "REJECTED", e.getMessage());
}
```

## 4. Truck：新增 2 个 Variable

位置：`Truck > Variables`。

- `pendingManualCommandId`：类型 `String`，初始值 `""`，Access 设为 `public`。
- `pendingManualTargetNode`：类型 `String`，初始值 `""`，Access 设为 `public`。

## 5. Truck：新增 `applyPendingManualDispatchAtNode()` Function

返回类型 `boolean`，无参数。

```java
if (pendingManualCommandId == null || pendingManualCommandId.isEmpty()) return false;

String commandId = pendingManualCommandId;
String target = pendingManualTargetNode;
try {
    ArrayList<PathSegment> replacement = main.getRouteSegments(currentNode, target);
    if (!currentNode.equals(target) &&
            (replacement == null || replacement.isEmpty())) {
        throw new IllegalStateException("route not found from " + currentNode);
    }

    if (!isLoaded) {
        int newSiteId = -1;
        for (int i = 0; i < main.collectionLoadSites.size(); i++) {
            if (main.collectionLoadSites.get(i).getName().equals(target)) {
                newSiteId = i; break;
            }
        }
        if (newSiteId < 0) throw new IllegalArgumentException("load target not found");
        if (targetShovel != null && targetShovel.incomingTrucks > 0) {
            targetShovel.incomingTrucks--;
        }
        targetLoadSiteId = newSiteId;
        targetShovel = main.getLoadSiteById(newSiteId);
        if (targetShovel != null) targetShovel.incomingTrucks++;
    } else {
        int newSiteId = target.equals("Crusher") ? 0 :
            target.equals("dumpNode") ? 1 : -1;
        if (newSiteId < 0) throw new IllegalArgumentException("dump target not found");
        targetDumpSiteId = newSiteId;
        targetDump = main.getDumpSiteById(newSiteId);
    }

    destinationNode = target;
    intDestination = main.roadNodeNameToInt(target);
    prepareRoute(currentNode, target);
    // CheckDestination 随后的 NEXTSEGMENT 会先 ++，故改派后从 -1 开始。
    if (!currentNode.equals(target)) currentSegmentIndex = -1;
    debugLastAction = "ManualDispatch_" + target;

    pendingManualCommandId = "";
    pendingManualTargetNode = "";
    main.publishManualDispatchResult(commandId, truckId, target,
        "EXECUTED", "route replaced at " + currentNode);
    return true;
} catch (Exception e) {
    pendingManualCommandId = "";
    pendingManualTargetNode = "";
    main.publishManualDispatchResult(commandId, truckId, target,
        "REJECTED", e.getMessage());
    return false;
}
```

## 6. Truck：在两个检查状态入口应用改派

位置一：`Truck > statechart > TravelToLoadSite > CheckDestination`，在现有 Entry
Action 第一行插入：

```java
applyPendingManualDispatchAtNode();
```

位置二：`Truck > statechart > TravelToDumpSite > CheckDestination1`，在现有 Entry
Action 第一行插入同一行。不要改 `MoveSegment`/`MoveSegment1` 的速度代码。

改派在车辆到达当前物理路段终点后生效，避免车辆从道路中间瞬移或沿不存在的
直线驶向目标。`EXECUTED` 只在路线、目标对象和目的地字段都完成替换后回传。

## 7. AnyLogic 手工验收

1. Build 模型，确认无 Java 编译错误；设置 `FIELD_MQTT_PASSWORD` 后运行。
2. Console 必须出现 `[FieldMQTT] connected` 和
   `[ManualDispatch] command subscription ready`。
3. 选一辆空载且处于 `TravelToLoadSite` 的车，在页面选择另一个铲装点并确认；
   Console 应先出现 `queued`，车辆到当前路段终点后页面指令变为 `EXECUTED`。
4. 对照 AnyLogic 检查该车 `destinationNode`、`targetLoadSiteId`、`targetShovel`
   已变化，后续确实进入新铲装点队列。
5. 再选一辆载重且处于 `TravelToDumpSite` 的车，在 `Crusher` 与 `dumpNode` 间改派；
   检查 `destinationNode`、`targetDumpSiteId`、`targetDump` 及最终卸载点。
6. 在 Mosquitto 日志或订阅器中确认 command/result 均为 QoS 1、非 retained；重复
   发布同一 result 不得生成第二条终态变更。

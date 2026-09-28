# AnyLogic 手工接入调度中心双向消息

这份指南针对你**当前运行的 AnyLogic 工程**，在 AnyLogic IDE 中手动修改 `Main`。工作区里的 `fusion_openpit_mine.original.alp` 只用作核对现有函数与字段的只读基线；不要直接改这个文件，也不需要使用另一个自动生成的 `.alp` 副本。

现有模型已包含 `fieldClient`、`fieldSimId`、`manualDispatchQueue`、`manualJsonString`、`manualJsonInt`、`trucks`、`loadSites` 和循环调用 `processManualDispatchQueue()` 的事件。以下只增加终端消息分支，保留人工改派、自动派单及速度控制原有分支。

## 1. 在 `Main > Functions > startFieldMqtt` 增加订阅

在 `connectComplete` 中把原 `fieldClient.subscribe(...)` 调用整体替换为：

```java
fieldClient.subscribe(
    new String[] {
        "mine/sim/" + fieldSimId + "/command/manual-dispatch/+",
        "mine/sim/" + fieldSimId + "/command/auto-dispatch/+",
        "mine/sim/" + fieldSimId + "/command/speed/+",
        "mine/sim/" + fieldSimId + "/terminal/+/+/message"
    },
    new int[] { 1, 1, 1, 1 }
).waitForCompletion(5000);
```

前三个原主题保持不变。四个主题均使用 QoS 1，并在重连后重新订阅。

在同一函数的 `messageArrived` 中，构造 `json` 后、判断 `/command/auto-dispatch/` 前插入：

```java
if (topic.contains("/terminal/") && message.isRetained()) return;
```

不要在 MQTT 回调线程访问车辆、铲或其他仿真对象。终端消息继续进入现有的 `manualDispatchQueue`；因此原 `else { manualDispatchQueue.offer(...) }` 保持不变。

## 2. 在 `Main > Functions` 新建函数

名称：`handleTerminalMessage`；返回值：`void`；参数：`topic : String`、`json : String`。函数 Body 完整粘贴：

```java
String[] parts = topic.split("/");
if (parts.length != 7 || !"mine".equals(parts[0])
        || !"sim".equals(parts[1]) || !fieldSimId.equals(parts[2])
        || !"terminal".equals(parts[3]) || !"message".equals(parts[6])) {
    throw new IllegalArgumentException("invalid terminal message topic");
}

String terminalType = parts[4];
int terminalId = Integer.parseInt(parts[5]);
String sourceId = manualJsonString(json, "messageId");
if (!("VEHICLE".equals(terminalType) || "SHOVEL".equals(terminalType))
        || !sourceId.matches("[A-Za-z0-9_-]{1,60}")
        || manualJsonInt(json, "schemaVersion") != 1
        || !fieldSimId.equals(manualJsonString(json, "simulationId"))
        || !terminalType.equals(manualJsonString(json, "targetType"))
        || terminalId != manualJsonInt(json, "targetId")) {
    throw new IllegalArgumentException("terminal topic/payload mismatch");
}

boolean targetExists = false;
if ("VEHICLE".equals(terminalType)) {
    for (Truck t : trucks) {
        if (t != null && t.truckId == terminalId) targetExists = true;
    }
} else {
    for (LoadSite s : loadSites) {
        if (s != null && s.siteId == terminalId) targetExists = true;
    }
}
if (!targetExists) throw new IllegalArgumentException("terminal target does not exist");
if (fieldClient == null || !fieldClient.isConnected()) {
    throw new IllegalStateException("field MQTT is not connected");
}

String responseTopic = "mine/sim/" + fieldSimId + "/terminal/"
        + terminalType + "/" + terminalId + "/response";
String response = String.format(java.util.Locale.ROOT,
        "{\"schemaVersion\":1,\"messageId\":\"ack-%s\","
        + "\"simulationId\":\"%s\",\"terminalType\":\"%s\","
        + "\"terminalId\":%d,\"senderName\":\"AnyLogic AUTO_ACK\","
        + "\"text\":\"AnyLogic 已接收调度消息\",\"sentAtMs\":%d,"
        + "\"replyToMessageId\":\"%s\"}",
        sourceId, fieldSimId, terminalType, terminalId,
        System.currentTimeMillis(), sourceId);
try {
    fieldClient.publish(responseTopic,
            response.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            1, false, null, null);
} catch (org.eclipse.paho.client.mqttv3.MqttException e) {
    throw new IllegalStateException(
            "terminal response publish failed: " + e.getMessage(), e);
}
traceln("[TerminalMessage] response queued for " + sourceId
        + " -> " + terminalType + terminalId);
```

`MqttPersistenceException` 继承 `MqttException`，所以上面的一个 `catch` 就覆盖 IDE 报出的两类受检异常；异常会交给现有队列循环的外层 `catch` 记录。`response queued` 仅表示发布调用已排队，后台收到 MQTT 回流后才会显示 `DELIVERED`。`ack-{messageId}` 是确定性回执 ID，避免 QoS 1 重投产生重复记录。自动回执不等于司机或铲端人员确认；人工文字回复仍由车/铲 HMI 发起。

## 3. 在 `Main > Functions > processManualDispatchQueue` 增加分支

保留现有循环、异常捕获及人工改派分支，只把原来的 `if/else` 改成：

```java
if (command[0].contains("/command/speed/")) {
    handleSpeedCommand(command[0], command[1]);
} else if (command[0].contains("/terminal/")
        && command[0].endsWith("/message")) {
    handleTerminalMessage(command[0], command[1]);
} else {
    handleManualDispatchCommand(command[0], command[1]);
}
```

现有循环事件已经调用 `processManualDispatchQueue()`，无需新建事件。建议在 AnyLogic IDE 中先执行模型检查/编译，再启动模型。不要把自动回执逻辑放进 MQTT 回调线程。

## 4. 联调验收

确认 `fieldSimId` 与调度台 Simulation 一致、`FIELD_MQTT_PASSWORD` 已设置，并按 [双向消息协议](TERMINAL_MQTT_CONVERSATION.md) 启动后台和 TLS Broker。调度员向存在的 T0 或 S0 发消息后，应先看到 `SENT`，随后在 AnyLogic 控制台看到 `[TerminalMessage] response queued for ...`，调度台看到 `AnyLogic AUTO_ACK` 和 `DELIVERED`。若一直停留在 `SENT`，检查订阅主题、模拟实例 ID、现场 MQTT 连接以及 `processManualDispatchQueue()` 是否继续运行。

本仓库已验证后台和临时本机 MQTT Broker 的真实收发；**你在 IDE 中手动修改后的模型仍需自行编译并进行 TLS 现场联调**。

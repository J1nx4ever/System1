# AnyLogic 手动代码（模型副本，尚未现场 Build 验证）

配合 ANYLOGIC_PRODUCTION_EVENTS.md 的八个插入位置使用。本文件给出不依赖额外 JSON 库的函数体。
Main 的 `productionRunId` 必须是本次后台实际返回的 run_id，不能使用示例 ID。

## 新增变量

Main：

```java
// String 参数 productionRunId：初始为空，启动实验前设为真实 run_id。
// 以下各项分别创建 AnyLogic 变量，类型和初始值如下。
java.util.concurrent.ConcurrentMap<String,String[]> productionOutbox
    = new java.util.concurrent.ConcurrentHashMap<>();
java.util.Set<String> productionInFlight
    = java.util.concurrent.ConcurrentHashMap.newKeySet();
java.util.Set<String> productionEmitted
    = java.util.concurrent.ConcurrentHashMap.newKeySet();
```

Truck：String 变量 `productionCycleId`，初始 null。

## Main.emitProductionEvent

创建 void 函数，参数 `Truck t, String type, int siteId`，函数体：

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
String payload = String.format(java.util.Locale.ROOT,
    "{\"schemaVersion\":1,\"eventId\":\"%s\",\"runId\":\"%s\","
    + "\"simulationId\":\"%s\",\"cycleId\":\"%s\",\"vehicleId\":%d,"
    + "\"eventType\":\"%s\",\"siteId\":%d,\"simTimeSeconds\":%.9f,"
    + "\"sentWallTimeMs\":%d,\"commandId\":null,\"payloadTons\":%s,"
    + "\"payloadGrade\":%s,\"producesOre\":%s,\"productionEligible\":%s}",
    eventId, productionRunId, fieldSimId, t.productionCycleId, t.truckId,
    type, siteId, time(SECOND), System.currentTimeMillis(), tons, grade, ore, eligible);
String topic = "mine/sim/" + fieldSimId + "/event/production/" + t.truckId;
productionOutbox.putIfAbsent(eventId, new String[]{topic, payload});
// 由独立 cyclic event 发送，不在控制/装卸回调中等待 MQTT。
```

## Main.flushProductionEvents

创建 void 无参函数，并新增循环 Event，每 1 SECOND 调用该函数：

```java
if (fieldClient == null || !fieldClient.isConnected()) return;
for (java.util.Map.Entry<String,String[]> entry : productionOutbox.entrySet()) {
    final String id = entry.getKey();
    final String[] data = entry.getValue();
    if (!productionInFlight.add(id)) continue;
    try {
        fieldClient.publish(data[0], data[1].getBytes(java.nio.charset.StandardCharsets.UTF_8),
            1, false, null, new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
                public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken token) {
                    productionOutbox.remove(id, data);
                    productionInFlight.remove(id);
                }
                public void onFailure(org.eclipse.paho.client.mqttv3.IMqttToken token, Throwable error) {
                    productionInFlight.remove(id);
                }
            });
    } catch (Exception e) {
        productionInFlight.remove(id);
        traceln("[Production] publish pending: " + e.getMessage());
    }
}
```

队列保留原事件内容，断线后重试不改事件 ID 或时间。队列仅存在于内存，实验停止后不保留。
PUBACK 不是后台入库确认；拒收和数据库失败仍须后台证据核查，不能宣称零丢失。
若某些客户端异常不触发回调，则 inFlight 的超时清理仍需补测。

## 八个插入点的调用

LoadSite.seize On enter，放在原 `if (!t.isWaitingForLoadResource)` 内，首次入队时：

```java
if (t.productionCycleId == null)
    t.productionCycleId = java.util.UUID.randomUUID().toString();
main.emitProductionEvent(t, "ARRIVE_SHOVEL", siteId);
main.emitProductionEvent(t, "QUEUE_LOAD", siteId);
```

LoadSite.delay On enter，loadStartTime 赋值后：

```java
main.emitProductionEvent(t, "LOADING_START", siteId);
```

Truck.onLoadingFinished(LoadSite ls)，载荷属性赋值后：

```java
main.emitProductionEvent(this, "LOADING_FINISH", ls.siteId);
```

DumpSite.seize On enter，已有 dumpQueueEnterTime 赋值后：

```java
if (t.productionCycleId != null) {
    main.emitProductionEvent(t, "ARRIVE_DUMP", siteId);
    main.emitProductionEvent(t, "QUEUE_DUMP", siteId);
}
```

DumpSite.delay On enter，dumpServiceStartTime 赋值后：

```java
if (t.productionCycleId != null) main.emitProductionEvent(t, "DUMP_START", siteId);
```

Truck.onDumpingFinished(DumpSite ds)，放在 `isLoaded = false;` 之前：

```java
if (productionCycleId != null) {
    main.emitProductionEvent(this, "DUMP_FINISH", ds.siteId);
    productionCycleId = null;
}
```

本版 cycle 从进入铲端流程开始，不覆盖此前卸载到铲端的空载行驶时间。
未加入实际 commandId 捕获；保持 null，不能据此宣称已有任务与周期强关联。
作业事件位置取自当前只读模型，须由用户在模型副本 Build 后验证八事件完整性。

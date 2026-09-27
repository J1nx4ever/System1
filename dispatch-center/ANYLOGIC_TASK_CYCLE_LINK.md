# 任务与作业周期关联：模型副本手动步骤

后台已支持 previousCommandId 可选字段、周期故事查询及派单证据快照。
旧报文只含 commandId 或 commandId=null 继续兼容，不自动猜测旧周期关联。
本文件是静态接入步骤，尚未在 AnyLogic Build 或真实周期中验收。

## 1. Truck 新增两个变量

- String `productionTaskCommandId`，初始 `null`。
- String `productionPreviousCommandId`，初始 `null`。

这些仅用于采集，不参与目标选择、路线或状态转换。

## 2. 自动指令真正应用后捕获 ID

Truck → NeedDispatchToLoadSite 的 Entry action，当前有以下代码：

```java
if (pendingAutoCommandId != null && !pendingAutoCommandId.isEmpty()) {
    main.publishDecisionResult(pendingAutoCommandId, "EXECUTED", ...);
    pendingAutoCommandId = "";
}
```

在这个 if 内、publishDecisionResult 前追加：

```java
productionTaskCommandId = pendingAutoCommandId;
productionPreviousCommandId = null;
```

不要在 handleAutoDispatchCommand 收到指令时就赋值，那时仅排队，尚未执行。

## 3. 人工改派真正换路成功后捕获 ID

Truck → applyPendingManualDispatchAtNode 函数，找到成功分支的：

```java
debugLastAction = "ManualDispatch_" + target;
pendingManualCommandId = "";
```

在这两行之前追加：

```java
productionPreviousCommandId = productionTaskCommandId;
productionTaskCommandId = commandId;
```

commandId 是该函数已有局部变量。拒绝/异常分支不追加，避免把未执行改派记为当前任务。
若有多次改派，此处保存最近一个前序指令；未发布过事件的更早多次改派链仍无法完整还原。

## 4. Main.emitProductionEvent 增加两个字段

在 `String payload = String.format(...)` 前追加：

```java
String commandJson = t.productionTaskCommandId == null ? "null"
    : "\"" + manualJsonEscape(t.productionTaskCommandId) + "\"";
String previousJson = t.productionPreviousCommandId == null ? "null"
    : "\"" + manualJsonEscape(t.productionPreviousCommandId) + "\"";
```

将原格式字符串这部分：

```java
+ "\"sentWallTimeMs\":%d,\"commandId\":null,\"payloadTons\":%s,"
```

替换为：

```java
+ "\"sentWallTimeMs\":%d,\"commandId\":%s,\"previousCommandId\":%s,\"payloadTons\":%s,"
```

将 format 最后参数行：

```java
type, siteId, time(SECOND), System.currentTimeMillis(), tons, grade, ore, eligible);
```

替换为：

```java
type, siteId, time(SECOND), System.currentTimeMillis(), commandJson, previousJson,
tons, grade, ore, eligible);
```

Main 已有 manualJsonEscape 函数，复用它，不增加库。
Truck.onDumpingFinished 中，DUMP_FINISH 采集之后追加：

```java
productionTaskCommandId = null;
productionPreviousCommandId = null;
```

不回写历史事件；发送缓存中的旧字节必须保持原样。
本次 runId 应在自动派单之前创建，否则该指令属于运行开始前，后台会拒绝跨运行关联。

## 5. 验收查询

调度员登录后：

`GET /api/v1/runs/{runId}/cycles/{cycleId}/story`

返回真实作业 events、其明确引用的 taskDecisions、原输入输出、校验结果和回执。
association=EXPLICIT_COMMAND_REFERENCES 表示显式引用都能解析，不代表运输已完成。
UNKNOWN 表示旧数据未报告指令 ID，不允许按时间最近的决策补齐。
同一周期人工改派时，检查原自动指令和实际人工指令都能查询，历史事件仍引用原指令。

## 决策依据快照

新自动派单记录的 input_summary_json.evidenceSnapshot 保存：

- 实际请求中的27维归一化输入、字段名称与 schemaId。
- 推理调用前的后台车辆/四铲端状态、采集墙钟时间及状态自带时间/序号。
- 若配置 DISPATCH_MODEL_VERSION，保存操作者声明的实际版本，否则 null。
- 原始生产状态、裁剪前输入、地图版本和因果原因仍为 null，未采集。

后台收到的状态不是现场同一时刻原始生产快照，不能将其描述为完全同步输入。
模型版本环境变量是声明，不是推理服务自报或权重哈希验证。
速度控制尚无现场 cycleId，故事接口不会将时间窗口内速度指令冒充强关联。

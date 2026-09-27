# 任务周期关联与派单依据快照

日期：2026-09-28，Asia/Shanghai。

## 交付内容

修改 ProductionEventService：增加 previousCommandId 可选字段；保留旧构造器和旧报文兼容。
重复证据按反序列化后的字段比较，避免新增可选 null 字段让旧重复报文被误判冲突。
commandId 和 previousCommandId 均需属于同一 runId、同一车辆的任务决策。
修改 InferenceDecisionService：推理调用前采集网关状态，保存在原 input_summary_json.evidenceSnapshot，
不修改观测、模型调用、动作选择、控制参考和执行逻辑。
新增 DispatchEvidence、CycleStoryRepository、CycleStoryController、CycleStoryIntegrationTest。
模型手动接入步骤：ANYLOGIC_TASK_CYCLE_LINK.md。

## 已执行验证

执行 ./bin/build.sh，BUILD SUCCESS。
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0。
证据：target/surefire-reports/com.mine.dispatch.CycleStoryIntegrationTest.txt/XML 及其他测试报告。
新增两项测试使用隔离数据库的合成证据，覆盖：

- 周期同时引用自动与人工任务指令，能查询两条原决策证据。
- 无现场 commandId 的旧周期显示 UNKNOWN，不按时间最近决策推断。
- 跨车辆任务、运行开始前任务关联被拒绝。
- 未知周期/运行查询返回不存在。
- 27维观测、27个字段名称、四候选网关状态快照结构。
- 未采集模型版本、原始生产值和因果原因保持 null；显式版本标为操作者声明。

## 现场验收步骤（待执行）

1. 按 ANYLOGIC_TASK_CYCLE_LINK.md，在模型副本增加两个采集变量及指令应用后的赋值。
2. 使用新 JAR，先创建新 runId，再配置模型 productionRunId，Build 后启动。
3. 同一车辆自动接单后完成装卸周期，查询生产事件获得实际 cycleId。
4. 调度员查询：GET /api/v1/runs/{runId}/cycles/{cycleId}/story。
5. 核对每条作业事件 commandId、taskDecisions 的 decisionId/commandId、目标及 EXECUTED 回执。
6. 再验人工改派：原自动指令、人工指令均保留；改变后续事件引用，不覆盖先前事件。
7. 查看 taskDecisions.input.evidenceSnapshot：检查真实观测、网关采集时间和候选状态时间/序号。

## 现场结果与边界

第二批装卸事件及生产统计由用户确认验收成功；该确认不等于本批关联已经验收。
本批自动派单→周期、人工改派前序关联、模型副本 Build、真实快照持久化：待验收。
未替换正在运行的服务或修改任何 .alp。target 中生成新 JAR，重启新版本后才生效。
没有新任务系统：taskId 保持 null，现有 commandId 是任务指令引用，不冒充独立业务 taskId。
多次人工改派中未产生作业事件的更早引用可能缺失，完整多次替代链尚待补采。
速度控制尚无源 cycleId，不按时间窗口伪造强关联。
原始生产目标/窗口吨数/品位、裁剪前特征、服务自报模型版本/权重哈希、配置和地图版本仍待采集。
网关候选状态不是现场同步快照，模型版本配置为操作者声明；因果解释保持未知。

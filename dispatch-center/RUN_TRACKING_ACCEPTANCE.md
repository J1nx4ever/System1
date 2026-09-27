# 运行追踪第一批验收记录

日期：2026-09-28，Asia/Shanghai。

## 已执行证据

执行 `./bin/build.sh`，Java 17.0.9，Maven clean verify。
结果：BUILD SUCCESS；Tests run: 8, Failures: 0, Errors: 0, Skipped: 0。
证据：`target/surefire-reports/` 下各测试 TXT/XML。

新增 `RunTrackingIntegrationTest` 两项集成测试，使用隔离临时 SQLite、随机 HTTP 端口，
禁用 MQTT 与推理，仅插入明确标记的测试证据，不写真实运行数据库。

| 项目 | 结果 | 证据 |
|---|---|---|
| 新数据库迁移、原有功能回归 | 通过 | 全部 8 项测试及启动日志 |
| 同一实例重复开始拒绝 | 通过，409 | endpointsRequireDispatcherAndDoNotExposeEvidenceToTerminals |
| 未登录查询拒绝 | 通过，401 | 同上 |
| 车辆读取运行、回放和证据拒绝 | 通过，403 | 同上 |
| 非法分页参数拒绝 | 通过，400 | 同上 |
| 同毫秒时间戳稳定 ID 游标 | 通过 | boundariesProjectionReplayAndRestartAreReadOnlyAndRepeatable |
| 重复投影无新增事件、实体/指令筛选 | 通过 | 同上 |
| 已执行指令不被当作任务完成 | 通过 | 同上 |
| 无仿真时间的回执保留 null | 通过 | 同上 |
| 重复结束保持首次边界、迁移重入保持记录 | 通过 | 同上；仅存储初始化验证，不等同整进程重启 |
| 新运行不混入上一运行测试证据 | 通过 | 同上 |
| 回放读取历史样本、时间越界拒绝 | 通过 | 同上 |
| 证据必须属于指定运行 | 通过 | 同上 |
| 未采集生产指标返回未知 | 通过 | 同上 |

## 待验收 / 尚未交付

- 真实 AnyLogic 单车全流程及与已有 MQTT 链路联调：待验收。
- 整进程重启后的运行恢复、回放：待验收。
- 铲端账号访问拒绝：代码与车辆同用 dispatcher 校验，尚未单独实测，待验收。
- 现场 runId 标记、延迟消息/跨边界回执精确归属：第二批待实现。
- taskId/cycleId、装卸事件、生产吨数及周期统计：第二批待实现。
- 输入原始值与四候选决策时快照、现场模型/配置版本：待采集。
- 人工操作 audit_log 精确运行关联、无决策记录的失败调用：待实现。
- 演示页面与 WebSocket 运行事件订阅未纳入本批；当前仅 REST。

## 文件与部署状态

新增 RunTrackingRepository、RunTrackingController、两份 V5 SQL 资源、
RunTrackingIntegrationTest、RUN_TRACKING.md 和本记录。
未修改既有业务代码、前端、环境配置或 .alp。
新可执行 JAR 已构建到 `target/dispatch-center-0.1.0.jar`；未替换 /opt 部署件或重启现场服务。

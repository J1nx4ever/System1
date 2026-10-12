# 现场演示验收脚本

目标链路：**卸载 → 自动派单 → 车端收到 → 行驶控制 → 人工改派**。

演示结束后在工作区根目录运行 `./stop-demo.sh`。它只停止 `demo.sh` 启动的
后台和 MQTT Broker；如果启动时临时停止了旧版 systemd 后台，还会将其恢复。
它保留 Run 数据、AnyLogic GUI 与浏览器。启动时需要在交互终端确认临时切换，
并输入 sudo 密码；旧版服务缺少 Run 接口，恢复后仍不能展示 Run 页面。

## 演示前准备

1. 按 `ANYLOGIC_INFERENCE_GATEWAY.md` 和 `ANYLOGIC_TERMINAL_MQTT_MANUAL.md`
   在 AnyLogic 工程中手工配置并 Build；`fusion_openpit_mine.original.alp` 只读。
2. 从工作区根目录执行 `./demo.sh`。脚本启动/复用 TLS Broker 与 Java 17
   ONNX 后台，不需要 50051/50060 Python gRPC 服务。
3. 如无活动 Run，在调度页点击「开始演示 Run」，再在 AnyLogic 模型中点击 Run。打开：
   - 调度中心：`http://localhost:8080/`
   - 车端：`http://localhost:8080/vehicle.html?vehicleId=0`
   - 铲端：`http://localhost:8080/shovel.html?shovelId=0`
4. 三端均应显示“实时链路”，且页面车辆/铲装点来自当前快照；无消息时显示等待，
   不出现预置车辆、任务或模型结果。

## 1. 卸载

选择一辆即将在 `Crusher` 或 `dumpNode` 完成卸载的车辆。确认车端载荷从载重变为空载，
状态序号和仿真时间持续增长。

**通过标准：**状态与 AnyLogic 同车一致；断开状态发布后页面变为失联，不继续伪造更新。

## 2. 自动派单

车辆进入 `NeedDispatchToLoadSite` 后，在调度中心展开最新 `TASK / DRL_DISPATCH`：

- 触发事件为 `UNLOAD_COMPLETE`；
- 输入为 `dispatch.v1-27` 且含 27 个真实值；
- 模型输出展示 action、target、value、logits、latency；
- 模型解释一栏不存在（协议无解释字段）；
- 后台校验为 `PASSED`，指令编号为 UUID。

**通过标准：**action 0/1/2/3 分别映射 HG1/HG2/LG/WASTE；目标存在且可用；非法输出不下发。

## 3. 车端收到

在对应车端检查“当前任务与目标”和本车决策链路。

**通过标准：**车端只能通过车辆投影接口读取本车决策；指令状态先为 `PENDING`，模型应用后
变为 `EXECUTED`；指令编号、目标和时间与调度中心一致。铲端的来车列表出现该车，相关任务
只出现在目标铲端。

## 4. 行驶控制

保持仿真运行至少 12 秒，在调度中心切换「速度控制」查看采样证据，在车端看控制参考。

**通过标准：**约每 500 ms 产生一批真实速度观测并下发指令；证据每车首条、至少每 10 秒一条，状态/动作变化和异常回执另行保留。每车输出只有一个有限加速度且在配置边界
`[-5.0, 3.0] m/s²` 内；指令含源状态序号、1 秒有效期；AnyLogic 应用后回执 `EXECUTED`，
过期指令回执 `EXPIRED`，不得应用。

## 5. 人工改派

在调度中心选择同一辆行驶中的空载车辆，选择另一个铲装点并确认改派。

**通过标准：**沿用已验收的人工改派：车辆到当前物理路段终点后换路，不瞬移；决策链路
显示 `TASK / MANUAL_DISPATCH`、`OPERATOR_MANUAL_REROUTE`、输入状态摘要、后台校验、原人工
指令编号与终端回执。模型输出只标记 `modelUsed=false`，不生成模型理由。车端目标和目标铲端
来车同步变化。

## 失败注入（建议现场演示一次）

在可控时段断开 MQTT：调度中心不应显示伪造控制值；已有状态按失联阈值变灰，失败指令
显示 `PUBLISH_FAILED`/无下发时间。恢复服务后使用新 UUID 继续，不复用旧指令。


# ONNX 实验结果复核

以下脚本直接调用 ONNX Runtime，不经过 Java 后台，也不启动 gRPC 服务。

## 1. 派单模型

脚本：`DispatchPPO/python/validate_onnx.py`

支持输入：

- `.npy`：形状 `[N, 27]`；
- `.npz`：`observations=[N,27]`，可选 `actions=[N]`；
- `.json` / `.jsonl`：每条包含 `values` 或 `observation`，可选 `action`。

JSON 示例：

```json
[
  {"values": [0.0, 0.1], "action": 1}
]
```

实际 `values` 必须有 27 个、均为有限值且位于 `[-1,1]`。

ONNX 与原 SB3 模型逐样本比较：

```bash
cd /home/jinx/System1/DispatchPPO
conda run -n anylogic-env python -m python.validate_onnx \
  --input python/examples/dispatch_observations.json \
  --onnx ../dispatch-center/models/dispatch_ppo.onnx \
  --source-model dispatch_ppo.zip \
  --output dispatch_results.jsonl
```

只运行 ONNX：

```bash
conda run -n anylogic-env python -m python.validate_onnx \
  --input /path/to/experiment.npz \
  --onnx ../dispatch-center/models/dispatch_ppo.onnx \
  --onnx-only \
  --output dispatch_results.jsonl
```

## 2. 速度模型

脚本：`speedcontrol/validate_onnx.py`

输入必须是一个或多个完整 `fusion.ObservationDict` 快照：

- 二进制 protobuf 文件；或
- `.textproto` / `.pbtxt` 文件。

ONNX 与原 RLlib checkpoint 逐车比较：

```bash
cd /home/jinx/System1/speedcontrol
conda run -n anylogic-env python validate_onnx.py \
  --input examples/speed_snapshot.textproto \
  --onnx ../dispatch-center/models/speed_actor.onnx \
  --checkpoint checkpoint_000016 \
  --expected examples/speed_expected.json \
  --output speed_results.jsonl
```

只运行 ONNX：

```bash
conda run -n anylogic-env python validate_onnx.py \
  --input /path/to/snapshot-*.pb \
  --onnx ../dispatch-center/models/speed_actor.onnx \
  --onnx-only \
  --output speed_results.jsonl
```

可选的历史结果 JSON 有两种形式：

```json
{"agent_0": -0.8559048}
```

或按快照文件名分组：

```json
{
  "snapshot-001.pb": {"agent_0": -0.8559048},
  "snapshot-002.pb": {"agent_0": -0.4123}
}
```

## 3. 判定规则

- 脚本输出 `PASS` 并以退出码 `0` 结束：一致；
- 输出 `FAILED` 并以退出码 `2` 结束：超过容差或离散动作不同；
- 派单默认误差容限为 `1e-5`；
- 速度默认误差容限为 `2e-4`；
- 可通过 `--atol`、`--rtol` 调整，但实验复核不建议随意放宽。

速度输出中的 `acceleration_mps2` 已是物理加速度，不要再次做 `×2/×3` 映射。

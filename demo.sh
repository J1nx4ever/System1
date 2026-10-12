#!/usr/bin/env bash
# One-command local demonstration launcher. It never edits the AnyLogic model.
set -Eeuo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$ROOT_DIR/dispatch-center"
STATE_DIR="$PROJECT_DIR/data/demo-launcher"
ENV_FILE="$PROJECT_DIR/dispatch-center.env"
JAR_FILE="$PROJECT_DIR/target/dispatch-center-0.1.0.jar"
BROKER_CONFIG="$ROOT_DIR/mqtt-tls/mosquitto.conf"
CA_FILE="$ROOT_DIR/mqtt-tls/certs/ca.crt"
TRUSTSTORE_FILE="$ROOT_DIR/mqtt-tls/mqtt-truststore.p12"
DISPATCH_MODEL="$PROJECT_DIR/models/dispatch_ppo.onnx"
SPEED_MODEL="$PROJECT_DIR/models/speed_actor.onnx"
DATABASE_FILE="$PROJECT_DIR/data/dispatch-center.db"
MODE=start
OPEN_BROWSER=true
LAUNCH_ANYLOGIC=true

say() { printf '[demo] %s\n' "$*"; }
fail() { printf '[demo] 错误：%s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'USAGE'
用法：./demo.sh [start|status|stop] [--no-browser] [--no-anylogic]

默认 start：检查/启动 TLS MQTT Broker 和 Java ONNX 后台，按需打开
AnyLogic GUI 与浏览器。已有进程会复用；不会自动运行或修改 .alp。
status：只查看状态。若旧版 systemd 后台占用 8080，start 会确认后临时切换。
stop：停止本脚本启动的后台/Broker，并恢复本次临时停止的 systemd 后台。
不会关闭 AnyLogic GUI、结束 Run 或停止系统管理的 Broker。
USAGE
}

for argument in "$@"; do
  case "$argument" in
    start|status|stop) MODE="$argument" ;;
    --no-browser) OPEN_BROWSER=false ;;
    --no-anylogic) LAUNCH_ANYLOGIC=false ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; fail "未知参数：$argument" ;;
  esac
done

# Keep the existing launcher focused on workspace processes. The wrapper handles
# an incompatible systemd deployment occupying the demo port and safe restoration.
if [[ "${DEMO_SWITCH_WRAPPED:-}" != 1 ]]; then
  exec "$ROOT_DIR/demo-systemd-switch.sh" "$@"
fi

if [[ -f "$ENV_FILE" ]]; then
  env_mode="$(stat -c %a "$ENV_FILE")"
  case "$env_mode" in *[1-7]) say "提示：dispatch-center.env 权限为 $env_mode，建议 chmod 600";; esac
  set +u
  set -a
  # Existing project credentials; never print their values.
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  set -u
fi

SERVER_PORT="${SERVER_PORT:-8080}"
[[ "$SERVER_PORT" =~ ^[0-9]+$ ]] && (( SERVER_PORT >= 1 && SERVER_PORT <= 65535 )) \
  || fail "SERVER_PORT 必须是 1..65535"
BASE_URL="http://127.0.0.1:$SERVER_PORT"

port_listening() {
  local port="$1"
  ss -H -ltn "( sport = :$port )" 2>/dev/null | grep -q .
}

health() {
  curl -fsS --max-time 3 "$BASE_URL/api/v1/health" 2>/dev/null || true
}

backend_pid() {
  ps -eo pid=,args= | awk -v jar="$JAR_FILE" '
    index($0, " -jar " jar) && $0 ~ /\/java / { print $1; exit }
  '
}

is_our_backend_pid() {
  local pid="$1" command
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  command="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  [[ "$command" == *" -jar $JAR_FILE" ]]
}

is_our_broker_pid() {
  local pid="$1" command
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  command="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  [[ "$command" == *"mosquitto -c $BROKER_CONFIG"* ]]
}

stop_pid_file() {
  local file="$1" name="$2" validator="$3" pid=""
  [[ -f "$file" ]] || return 0
  IFS= read -r pid < "$file" || true
  if "$validator" "$pid"; then
    say "停止本脚本启动的$name (PID $pid)"
    kill -TERM "$pid"
    for _ in {1..20}; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      fail "$name 未在 20 秒内退出；没有强制终止"
    fi
  else
    say "$name PID 文件已过期，未操作其他进程"
  fi
  rm -f -- "$file"
}

show_status() {
  if port_listening 8883; then say 'MQTT 8883：监听中'; else say 'MQTT 8883：未监听'; fi
  local response
  response="$(health)"
  if [[ "$response" == *'"databaseHealthy":true'* ]]; then
    if [[ "$response" == *'"mqttConnected":true'* ]]; then
      say "调度后台：正常，MQTT 已连接 ($BASE_URL)"
    else
      say "调度后台：已启动，但 MQTT 未连接 ($BASE_URL)"
    fi
  elif port_listening "$SERVER_PORT"; then
    say "端口 $SERVER_PORT 已被其他服务占用，未识别为调度后台"
  else
    say "调度后台：未启动 ($BASE_URL)"
  fi
  if pgrep -f 'org.eclipse.equinox.launcher_.*-name Anylogic' >/dev/null 2>&1; then
    say 'AnyLogic GUI：已运行'
  else
    say 'AnyLogic GUI：未运行'
  fi
  if [[ "${DEMO_FOREIGN_BACKEND:-}" == 1 ]]; then
    say '工作区 Run 与当前 systemd 后台使用不同数据库；不显示工作区 Run 状态'
    return
  fi
  if command -v sqlite3 >/dev/null 2>&1 && [[ -f "$DATABASE_FILE" ]]; then
    local current_run
    current_run="$(sqlite3 -readonly "$DATABASE_FILE" \
      "SELECT run_id FROM run_context WHERE simulation_id='SIM_001' AND ended_at_ms IS NULL ORDER BY started_at_ms DESC LIMIT 1;" 2>/dev/null || true)"
    if [[ -n "$current_run" ]]; then
      say "SIM_001：已有进行中的 Run $current_run"
    else
      say 'SIM_001：暂无进行中的 Run；登录调度页后点击「开始演示 Run」'
    fi
  fi
}

if [[ "$MODE" == status ]]; then
  show_status
  exit 0
fi

if [[ "$MODE" == stop ]]; then
  stop_pid_file "$STATE_DIR/backend.pid" '调度后台' is_our_backend_pid
  stop_pid_file "$STATE_DIR/broker.pid" 'MQTT Broker' is_our_broker_pid
  say 'AnyLogic GUI 和浏览器保留，Run 数据未改动'
  exit 0
fi

mkdir -p "$STATE_DIR"
for required in "$CA_FILE" "$TRUSTSTORE_FILE" "$DISPATCH_MODEL" "$SPEED_MODEL"; do
  [[ -f "$required" ]] || fail "缺少文件：$required"
done
command -v curl >/dev/null 2>&1 || fail '缺少 curl'
command -v ss >/dev/null 2>&1 || fail '缺少 ss'
[[ -x /usr/bin/java ]] || fail '缺少 /usr/bin/java (Java 17)'

if ! port_listening 8883; then
  [[ -f "$BROKER_CONFIG" ]] || fail "缺少 Broker 配置：$BROKER_CONFIG"
  command -v mosquitto >/dev/null 2>&1 || fail '缺少 mosquitto'
  say '启动本地 TLS MQTT Broker…'
  nohup setsid mosquitto -c "$BROKER_CONFIG" -v > "$STATE_DIR/broker.log" 2>&1 < /dev/null &
  broker_pid=$!
  printf '%s\n' "$broker_pid" > "$STATE_DIR/broker.pid"
  for _ in {1..10}; do
    port_listening 8883 && break
    kill -0 "$broker_pid" 2>/dev/null || break
    sleep 1
  done
  port_listening 8883 || { tail -20 "$STATE_DIR/broker.log" >&2; fail 'MQTT Broker 启动失败'; }
else
  say '复用已运行的 MQTT Broker'
fi

export DATABASE_URL="jdbc:sqlite:$DATABASE_FILE"
export MQTT_ENABLED=true
export MQTT_BROKER_URI=ssl://localhost:8883
export MQTT_CA_FILE="$CA_FILE"
export MQTT_USERNAME="${MQTT_USERNAME:-field_uplink}"
export INFERENCE_MODE=onnx
export DISPATCH_ONNX_MODEL="$DISPATCH_MODEL"
export SPEED_ONNX_MODEL="$SPEED_MODEL"
export DISPATCH_JAVA_BIN=/usr/bin/java

response="$(health)"
if [[ -n "$response" && "$response" != *'"databaseHealthy":'* ]]; then
  fail "端口 $SERVER_PORT 有响应，但不是调度后台"
fi
if [[ -z "$response" ]] && port_listening "$SERVER_PORT"; then
  fail "端口 $SERVER_PORT 被占用，且调度健康检查不可用"
fi

needs_build=false
if [[ ! -f "$JAR_FILE" || "$PROJECT_DIR/pom.xml" -nt "$JAR_FILE" ]]; then
  needs_build=true
elif find "$PROJECT_DIR/src/main" -type f -newer "$JAR_FILE" -print -quit | grep -q .; then
  needs_build=true
fi

restart_needed=false
if [[ -n "$response" && -f "$JAR_FILE" ]]; then
  running_pid="$(backend_pid)"
  if [[ -n "$running_pid" ]] && is_our_backend_pid "$running_pid"; then
    elapsed="$(ps -p "$running_pid" -o etimes= | tr -d ' ')"
    jar_modified="$(stat -c %Y "$JAR_FILE")"
    (( jar_modified > $(date +%s) - elapsed )) && restart_needed=true
    running_mode="$(tr '\0' '\n' < "/proc/$running_pid/environ" | sed -n 's/^INFERENCE_MODE=//p' | tail -1)"
    [[ -z "$running_mode" || "${running_mode,,}" == onnx ]] || restart_needed=true
  fi
fi
backup_created=false
if [[ ( "$needs_build" == true || "$restart_needed" == true ) && -n "$response" ]]; then
  [[ -f "$JAR_FILE" ]] || fail "运行中的后台 JAR 已被移除，未停止进程"
  if [[ -z "${MQTT_PASSWORD:-}" ]]; then
    [[ -t 0 ]] || fail "重启需要 MQTT_PASSWORD，请在交互终端运行"
    read -r -s -p 'MQTT 密码：' MQTT_PASSWORD
    printf '\n'
    export MQTT_PASSWORD
  fi
  current_pid="$(backend_pid)"
  [[ -n "$current_pid" ]] && is_our_backend_pid "$current_pid" \
    || fail '后台运行中但无法确认进程身份；为避免覆盖运行中的 JAR，未构建'
  if [[ ! -t 0 ]]; then
    fail '运行版本需要更新；请在交互终端运行本脚本并确认短暂停机'
  fi
  read -r -p '检测到新版源码/JAR 或非 ONNX 进程，需短暂停止后台（Run 保留）。继续？[y/N] ' answer
  [[ "$answer" == y || "$answer" == Y ]] || fail '已取消重建；后台保持运行'
  if [[ "$needs_build" == true ]]; then
    cp "$JAR_FILE" "$STATE_DIR/previous.jar"
    backup_created=true
  fi
  kill -TERM "$current_pid"
  for _ in {1..20}; do
    kill -0 "$current_pid" 2>/dev/null || break
    sleep 1
  done
  kill -0 "$current_pid" 2>/dev/null && fail '后台未在 20 秒内退出；没有强制终止'
  rm -f -- "$STATE_DIR/backend.pid"
  response=''
fi

if [[ "$needs_build" == true ]]; then
  say '编译并验证 Java 后台（日志：data/demo-launcher/build.log）…'
  if ! (cd "$PROJECT_DIR" && ./bin/build.sh) > "$STATE_DIR/build.log" 2>&1; then
    if [[ "$backup_created" == true ]]; then
      cp "$STATE_DIR/previous.jar" "$JAR_FILE"
      say '构建失败，已恢复之前的 JAR 并重启旧后台'
      nohup setsid "$PROJECT_DIR/bin/start.sh" > "$STATE_DIR/backend.log" 2>&1 < /dev/null &
      printf '%s\n' "$!" > "$STATE_DIR/backend.pid"
    fi
    tail -35 "$STATE_DIR/build.log" >&2
    fail '后台构建失败'
  fi
fi

if [[ -z "$response" ]]; then
  [[ -f "$JAR_FILE" ]] || fail "缺少后台 JAR：$JAR_FILE"
  if [[ -z "${MQTT_PASSWORD:-}" ]]; then
    [[ -t 0 ]] || fail '缺少 MQTT_PASSWORD；请写入 dispatch-center.env 或在终端交互输入'
    read -r -s -p 'MQTT 密码：' MQTT_PASSWORD
    printf '\n'
    export MQTT_PASSWORD
  fi
  say '启动 Java 17 + ONNX 调度后台…'
  nohup setsid "$PROJECT_DIR/bin/start.sh" > "$STATE_DIR/backend.log" 2>&1 < /dev/null &
  new_pid=$!
  printf '%s\n' "$new_pid" > "$STATE_DIR/backend.pid"
  for _ in {1..30}; do
    response="$(health)"
    [[ -n "$response" ]] && break
    kill -0 "$new_pid" 2>/dev/null || break
    sleep 1
  done
  [[ -n "$response" ]] || { tail -40 "$STATE_DIR/backend.log" >&2; fail '后台未就绪'; }
else
  say '复用已运行的调度后台'
fi

if [[ "$response" != *'"databaseHealthy":true'* ]]; then
  fail '后台响应但数据库不健康，请查看 backend.log'
fi
for _ in {1..15}; do
  response="$(health)"
  [[ "$response" == *'"mqttConnected":true'* ]] && break
  sleep 1
done
[[ "$response" == *'"mqttConnected":true'* ]] \
  || fail '后台已启动但 MQTT 未连接；请检查密码、证书和 Broker 日志'

if [[ "$LAUNCH_ANYLOGIC" == true ]]; then
  if pgrep -f 'org.eclipse.equinox.launcher_.*-name Anylogic' >/dev/null 2>&1; then
    say '复用已打开的 AnyLogic GUI（已有进程的 MQTT 环境变量无法由脚本补设）'
  elif [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]] && command -v anylogic >/dev/null 2>&1; then
    [[ -n "${MQTT_PASSWORD:-}" ]] || fail '启动 AnyLogic 需要 MQTT_PASSWORD'
    export FIELD_MQTT_PASSWORD="$MQTT_PASSWORD"
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djavax.net.ssl.trustStore=$TRUSTSTORE_FILE -Djavax.net.ssl.trustStorePassword=changeit"
    say '启动 AnyLogic GUI；模型需在 GUI 中手动点击 Run'
    # Avoid WebKitGTK GBM/EGL initialization failures; allow explicit override.
    nohup setsid env -u MQTT_PASSWORD -u SECURITY_BOOTSTRAP_USERS \
      WEBKIT_DISABLE_DMABUF_RENDERER="${WEBKIT_DISABLE_DMABUF_RENDERER:-1}" anylogic \
      > "$STATE_DIR/anylogic.log" 2>&1 < /dev/null &
    gui_pid=$!
    for _ in {1..15}; do
      pgrep -f 'org.eclipse.equinox.launcher_.*-name Anylogic' >/dev/null 2>&1 && break
      kill -0 "$gui_pid" 2>/dev/null || break
      sleep 1
    done
    if ! pgrep -f 'org.eclipse.equinox.launcher_.*-name Anylogic' >/dev/null 2>&1; then
      say 'AnyLogic GUI 未就绪，请查看 data/demo-launcher/anylogic.log'
    fi
  else
    say '未启动 AnyLogic GUI（无图形会话或未安装）；请在本机打开模型并 Run'
  fi
fi

if [[ "$OPEN_BROWSER" == true && -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]] \
    && command -v xdg-open >/dev/null 2>&1; then
  nohup xdg-open "$BASE_URL/" > "$STATE_DIR/browser.log" 2>&1 < /dev/null &
fi

show_status
say "调度中心：$BASE_URL/"
say "车端：$BASE_URL/vehicle.html?vehicleId=0"
say "铲端：$BASE_URL/shovel.html?shovelId=0"
say '下一步：如无活动 Run，在调度页点击「开始演示 Run」；然后在 AnyLogic 模型中点击 Run。'

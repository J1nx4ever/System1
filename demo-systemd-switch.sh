#!/usr/bin/env bash
# Internal wrapper for demo.sh: switch an incompatible systemd backend safely.
set -Eeuo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$ROOT_DIR/dispatch-center"
STATE_DIR="$PROJECT_DIR/data/demo-launcher"
JAR_FILE="$PROJECT_DIR/target/dispatch-center-0.1.0.jar"
MARKER="$STATE_DIR/suspended-systemd-service"
SERVICE=dispatch-center.service
MODE=start

for argument in "$@"; do
  case "$argument" in
    start|status|stop) MODE="$argument" ;;
  esac
done

say() { printf '[demo] %s\n' "$*"; }
fail() { printf '[demo] 错误：%s\n' "$*" >&2; exit 1; }
core() { DEMO_SWITCH_WRAPPED=1 "$ROOT_DIR/demo.sh" "$@"; }

legacy_systemd_pid() {
  command -v systemctl >/dev/null 2>&1 || return 1
  command -v unzip >/dev/null 2>&1 || return 1
  systemctl is-active --quiet "$SERVICE" 2>/dev/null || return 1
  local pid command jar
  pid="$(systemctl show "$SERVICE" -p MainPID --value 2>/dev/null)" || return 1
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  command="$(ps -p "$pid" -o args= 2>/dev/null)" || return 1
  [[ "$command" == *" -jar "* ]] || return 1
  jar="${command##* -jar }"
  jar="${jar%% *}"
  [[ -f "$jar" && "$jar" != "$JAR_FILE" ]] || return 1
  unzip -p "$jar" BOOT-INF/classes/com/mine/dispatch/web/RunTrackingController.class \
    >/dev/null 2>&1 && return 1
  printf '%s\n' "$pid"
}

restore_systemd() {
  [[ -f "$MARKER" ]] || return 0
  local service_name=""
  IFS= read -r service_name < "$MARKER" || true
  [[ "$service_name" == "$SERVICE" ]] || fail '服务恢复标记不符合预期，未操作 systemd'
  if ! systemctl is-active --quiet "$SERVICE"; then
    say '恢复演示前的 systemd 后台（其旧版页面仍需单独升级）'
    sudo systemctl start "$SERVICE" || fail "恢复失败，请运行 sudo systemctl start $SERVICE"
  fi
  rm -f -- "$MARKER"
}

switch_pending=false
rollback_on_exit() {
  local result=$?
  trap - EXIT
  if [[ "$result" -ne 0 && "$switch_pending" == true ]]; then
    say '启动中断，尝试恢复演示前的 systemd 后台…'
    core stop >/dev/null 2>&1 || true
    if sudo -n systemctl start "$SERVICE" >/dev/null 2>&1; then
      rm -f -- "$MARKER"
    else
      say "自动恢复失败；请运行 ./stop-demo.sh 或 sudo systemctl start $SERVICE"
    fi
  fi
}
trap rollback_on_exit EXIT


if [[ "$MODE" == status ]]; then
  if legacy_systemd_pid >/dev/null; then
    say '注意：8080 当前为旧版 systemd 后台，缺少 Run 接口；工作区 Run 未接入该服务'
    DEMO_FOREIGN_BACKEND=1 core "$@"
  else
    core "$@"
  fi
  exit 0
fi

if [[ "$MODE" == stop ]]; then
  core "$@"
  restore_systemd
  exit 0
fi

if ! legacy_systemd_pid >/dev/null; then
  core "$@"
  exit 0
fi

[[ -t 0 ]] || fail '8080 由旧版 systemd 后台占用；请在交互终端运行 ./demo.sh 确认切换'
[[ -f "$JAR_FILE" ]] || fail '工作区 JAR 不存在，请先构建后台'
if [[ "$PROJECT_DIR/pom.xml" -nt "$JAR_FILE" ]] ||
   find "$PROJECT_DIR/src/main" -type f -newer "$JAR_FILE" -print -quit | grep -q .; then
  mkdir -p "$STATE_DIR"
  say '先编译并验证工作区新版后台；systemd 服务暂不停止…'
  if ! (cd "$PROJECT_DIR" && ./bin/build.sh) > "$STATE_DIR/build.log" 2>&1; then
    tail -35 "$STATE_DIR/build.log" >&2
    fail '工作区构建失败，systemd 服务保持运行'
  fi
fi

read -r -p '旧版后台缺少 Run 接口。临时切换到工作区新版，结束演示时恢复旧服务？[y/N] ' answer
[[ "$answer" == y || "$answer" == Y ]] || fail '已取消；systemd 服务保持运行'
sudo -v || fail '切换需要 sudo 权限'
mkdir -p "$STATE_DIR"
printf '%s\n' "$SERVICE" > "$MARKER"
if ! sudo systemctl stop "$SERVICE"; then
  rm -f -- "$MARKER"
  fail '未能停止 systemd 后台'
fi
switch_pending=true

for _ in {1..20}; do
  if ! ss -H -ltn '( sport = :8080 )' 2>/dev/null | grep -q .; then break; fi
  sleep 1
done
if ss -H -ltn '( sport = :8080 )' 2>/dev/null | grep -q .; then
  restore_systemd
  switch_pending=false
  fail 'systemd 停止后 8080 仍被占用，未启动工作区后台'
fi

say '已临时停止旧版 systemd 后台；正在启动工作区新版…'
if core "$@"; then
  say '演示结束运行 ./stop-demo.sh；它会恢复原 systemd 后台'
  switch_pending=false
  exit 0
fi

say '新版启动失败，正在回滚到原 systemd 后台…'
core stop || true
restore_systemd
switch_pending=false
fail '工作区后台未启动；原 systemd 后台已恢复'

#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_FILE="${PROJECT_DIR}/target/dispatch-center-0.1.0.jar"
DISPATCH_JAVA_BIN="${DISPATCH_JAVA_BIN:-/home/jinx/apps/AnyLoigc/anylogic/jre/bin/java}"

export MQTT_BROKER_URI="${MQTT_BROKER_URI:-ssl://localhost:8883}"
export MQTT_USERNAME="${MQTT_USERNAME:-field_uplink}"
export MQTT_CA_FILE="${MQTT_CA_FILE:-/home/jinx/桌面/System1/mqtt-tls/certs/ca.crt}"
export DATABASE_URL="${DATABASE_URL:-jdbc:sqlite:${PROJECT_DIR}/data/dispatch-center.db}"

if [[ "${MQTT_ENABLED:-true}" == "true" && -z "${MQTT_PASSWORD:-}" ]]; then
  echo "ERROR: set MQTT_PASSWORD in the environment" >&2
  exit 2
fi
if [[ ! -f "${JAR_FILE}" ]]; then
  echo "ERROR: ${JAR_FILE} not found; run bin/build.sh first" >&2
  exit 2
fi

exec "${DISPATCH_JAVA_BIN}" -Ddebug=false -jar "${JAR_FILE}"

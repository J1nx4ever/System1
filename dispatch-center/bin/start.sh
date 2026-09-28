#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_FILE="${PROJECT_DIR}/target/dispatch-center-0.1.0.jar"
DISPATCH_JAVA_BIN="${DISPATCH_JAVA_BIN:-/home/jinx/apps/AnyLoigc/anylogic/jre/bin/java}"

export MQTT_BROKER_URI="${MQTT_BROKER_URI:-ssl://localhost:8883}"
export MQTT_USERNAME="${MQTT_USERNAME:-field_uplink}"
export MQTT_CA_FILE="${MQTT_CA_FILE:-/home/jinx/桌面/System1/mqtt-tls/certs/ca.crt}"
export DATABASE_URL="${DATABASE_URL:-jdbc:sqlite:${PROJECT_DIR}/data/dispatch-center.db}"
export INFERENCE_MODE="${INFERENCE_MODE:-onnx}"
export DISPATCH_ONNX_MODEL="${DISPATCH_ONNX_MODEL:-${PROJECT_DIR}/models/dispatch_ppo.onnx}"
export SPEED_ONNX_MODEL="${SPEED_ONNX_MODEL:-${PROJECT_DIR}/models/speed_actor.onnx}"

if [[ "${MQTT_ENABLED:-true}" == "true" && -z "${MQTT_PASSWORD:-}" ]]; then
  echo "ERROR: set MQTT_PASSWORD in the environment" >&2
  exit 2
fi
if [[ "${INFERENCE_MODE}" == "onnx" ]]; then
  for model_file in "${DISPATCH_ONNX_MODEL}" "${SPEED_ONNX_MODEL}"; do
    if [[ ! -f "${model_file}" ]]; then
      echo "ERROR: ONNX model not found: ${model_file}" >&2
      exit 2
    fi
  done
fi
if [[ ! -f "${JAR_FILE}" ]]; then
  echo "ERROR: ${JAR_FILE} not found; run bin/build.sh first" >&2
  exit 2
fi

exec "${DISPATCH_JAVA_BIN}" -Ddebug=false -jar "${JAR_FILE}"

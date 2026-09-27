#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE_DIR="$(cd "${PROJECT_DIR}/.." && pwd)"
PROTOC_BIN="${PROTOC_BIN:-protoc}"
PROTO_FILE="${WORKSPACE_DIR}/field-uplink/field_uplink.proto"
FIXTURE_DIR="${PROJECT_DIR}/src/test/resources/live"
MQTT_HOST="${MQTT_HOST:-localhost}"
MQTT_PORT="${MQTT_PORT:-8883}"
MQTT_USERNAME="${MQTT_USERNAME:-field_uplink}"
MQTT_CA_FILE="${MQTT_CA_FILE:-${WORKSPACE_DIR}/mqtt-tls/certs/ca.crt}"

: "${MQTT_PASSWORD:?set MQTT_PASSWORD in the environment}"

FIXTURE_TMP_DIR="$(mktemp -d)"
cleanup() {
  find "${FIXTURE_TMP_DIR}" -type f -delete
  rmdir "${FIXTURE_TMP_DIR}"
}
trap cleanup EXIT

"${PROTOC_BIN}" --encode=mine.field.v1.MapState \
  --proto_path="${WORKSPACE_DIR}/field-uplink" "${PROTO_FILE}" \
  < "${FIXTURE_DIR}/map.textproto" > "${FIXTURE_TMP_DIR}/map.pb"
for number in 1 2; do
  "${PROTOC_BIN}" --encode=mine.field.v1.VehicleState \
    --proto_path="${WORKSPACE_DIR}/field-uplink" "${PROTO_FILE}" \
    < "${FIXTURE_DIR}/vehicle-${number}.textproto" \
    > "${FIXTURE_TMP_DIR}/vehicle-${number}.pb"
done
for number in 0 1 2 3; do
  "${PROTOC_BIN}" --encode=mine.field.v1.ShovelState \
    --proto_path="${WORKSPACE_DIR}/field-uplink" "${PROTO_FILE}" \
    < "${FIXTURE_DIR}/shovel-${number}.textproto" \
    > "${FIXTURE_TMP_DIR}/shovel-${number}.pb"
done

MQTT_ARGS=(-h "${MQTT_HOST}" -p "${MQTT_PORT}" --cafile "${MQTT_CA_FILE}"
  -u "${MQTT_USERNAME}" -P "${MQTT_PASSWORD}")

mosquitto_pub "${MQTT_ARGS[@]}" -q 1 -r \
  -t mine/sim/SIM_001/state/map -f "${FIXTURE_TMP_DIR}/map.pb"
mosquitto_pub "${MQTT_ARGS[@]}" -q 0 \
  -t mine/sim/SIM_001/state/vehicle/0 -f "${FIXTURE_TMP_DIR}/vehicle-1.pb"
for number in 0 1 2 3; do
  mosquitto_pub "${MQTT_ARGS[@]}" -q 0 \
    -t "mine/sim/SIM_001/state/shovel/${number}" \
    -f "${FIXTURE_TMP_DIR}/shovel-${number}.pb"
done
sleep 1
mosquitto_pub "${MQTT_ARGS[@]}" -q 0 \
  -t mine/sim/SIM_001/state/vehicle/0 -f "${FIXTURE_TMP_DIR}/vehicle-2.pb"

echo "Published retained map, two moving vehicle samples, and four shovel states."

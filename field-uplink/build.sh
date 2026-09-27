#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${PROJECT_DIR}/build"
GENERATED_DIR="${BUILD_DIR}/generated"
CLASSES_DIR="${BUILD_DIR}/classes"
DIST_DIR="${PROJECT_DIR}/dist"
PROTOC="/home/jinx/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protoc/3.25.3/4460dca2066dafeeddd1d602a1edb2005768a6d4/protoc-3.25.3-linux-x86_64.exe"
ANYLOGIC_JAVA_HOME="/home/jinx/apps/AnyLoigc/anylogic/jre"
PROTOBUF_JAR="${PROJECT_DIR}/lib/protobuf-java-3.25.3.jar"
OUTPUT_JAR="${DIST_DIR}/field-uplink-1.0.0.jar"

test "${CONDA_DEFAULT_ENV:-}" = "DRL" || {
  echo "ERROR: run this build in the DRL conda environment" >&2
  exit 2
}
test -x "${PROTOC}"
test -x "${ANYLOGIC_JAVA_HOME}/bin/javac"
test -f "${PROTOBUF_JAR}"

rm -rf "${BUILD_DIR}"
mkdir -p "${GENERATED_DIR}" "${CLASSES_DIR}" "${DIST_DIR}"

"${PROTOC}" \
  --proto_path="${PROJECT_DIR}" \
  --java_out="${GENERATED_DIR}" \
  "${PROJECT_DIR}/field_uplink.proto"

mapfile -d '' SOURCES < <(find "${GENERATED_DIR}" -name '*.java' -print0 | sort -z)
"${ANYLOGIC_JAVA_HOME}/bin/javac" \
  --release 17 \
  -encoding UTF-8 \
  -classpath "${PROTOBUF_JAR}" \
  -d "${CLASSES_DIR}" \
  "${SOURCES[@]}"

"${ANYLOGIC_JAVA_HOME}/bin/jar" \
  --create \
  --file "${OUTPUT_JAR}" \
  -C "${CLASSES_DIR}" .

echo "Built ${OUTPUT_JAR}"

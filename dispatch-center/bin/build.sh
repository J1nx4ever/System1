#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISPATCH_JAVA_HOME="${DISPATCH_JAVA_HOME:-/home/jinx/apps/AnyLoigc/anylogic/jre}"

if [[ ! -x "${DISPATCH_JAVA_HOME}/bin/java" ]]; then
  echo "ERROR: AnyLogic-compatible Java not found: ${DISPATCH_JAVA_HOME}/bin/java" >&2
  echo "Set DISPATCH_JAVA_HOME to the JDK/JRE used by AnyLogic." >&2
  exit 2
fi

export JAVA_HOME="${DISPATCH_JAVA_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export DEBUG=false
cd "${PROJECT_DIR}"
mvn clean verify

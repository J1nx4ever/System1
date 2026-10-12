#!/usr/bin/env bash
# Stop workspace demo processes and restore a systemd backend suspended by demo.sh.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$ROOT_DIR/demo.sh" stop "$@"

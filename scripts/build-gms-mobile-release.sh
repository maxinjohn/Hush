#!/usr/bin/env bash
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/build-release.sh" gms mobile "${1:-universal}"

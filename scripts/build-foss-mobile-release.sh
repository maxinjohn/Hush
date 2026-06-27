#!/usr/bin/env bash
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/build-release.sh" foss mobile "${1:-arm64}"

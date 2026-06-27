#!/usr/bin/env bash
# TV release build wrapper (default: gms universal, same as GitHub CI).
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/build-release.sh" "${1:-gms}" tv "${2:-universal}"

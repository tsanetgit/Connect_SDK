#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-crash-password.sh
source "${SCRIPT_DIR}/load-crash-password.sh"
# shellcheck source=crash-ssh-common.sh
source "${SCRIPT_DIR}/crash-ssh-common.sh"

CRASH_HOST="${CRASH_HOST:-127.0.0.1}"
CRASH_SSH_PORT="${CRASH_SSH_PORT:-2000}"
CRASH_USER="${CRASH_USER:-crash}"

usage() {
  echo "Usage: $0 <crash-command> [args...]" >&2
  echo "Example: $0 tsa session" >&2
  echo "CRASH_PASSWORD overrides crash.auth-password from tsanet-demo-crash-ssh.properties if set." >&2
  exit 1
}

if [[ $# -lt 1 ]]; then
  usage
fi

crash_ssh_require_tool || exit 1

set +e
crash_ssh_exec "${CRASH_PASSWORD}" \
  "${CRASH_SSH_OPTS[@]}" \
  -p "${CRASH_SSH_PORT}" \
  "${CRASH_USER}@${CRASH_HOST}" \
  "$@"
ec=$?
set -e
exit "${ec}"

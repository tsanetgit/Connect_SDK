#!/usr/bin/env bash
set -euo pipefail

# Connect API credentials — edit these for your environment.
LOGIN_USERNAME="api@appko.com"
LOGIN_PASSWORD="T123456!"

# CRaSH SSH credentials (default for the running demo app).
CRASH_USER="crash"
CRASH_PASSWORD="crash"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=crash-ssh-common.sh
source "${SCRIPT_DIR}/crash-ssh-common.sh"

usage() {
  echo "Usage: $0 <host> <port>" >&2
  echo "  host  CRaSH SSH host (e.g. 127.0.0.1)" >&2
  echo "  port  CRaSH SSH port (e.g. 2000)" >&2
  exit 1
}

if [[ $# -ne 2 ]]; then
  usage
fi

CRASH_HOST="$1"
CRASH_SSH_PORT="$2"

crash_ssh_require_tool || exit 1
crash_ssh_check_port "${CRASH_HOST}" "${CRASH_SSH_PORT}" || exit 1

remote_login=$(printf 'tsa login %q %q' "${LOGIN_USERNAME}" "${LOGIN_PASSWORD}")

login_out="$(mktemp)"
trap 'rm -f "${login_out}"' EXIT

set +e
crash_ssh_exec "${CRASH_PASSWORD}" \
  "${CRASH_SSH_OPTS_BATCH[@]}" \
  -p "${CRASH_SSH_PORT}" \
  "${CRASH_USER}@${CRASH_HOST}" \
  "${remote_login}" >"${login_out}" 2>&1
login_ec=$?
set -e

if [[ ${login_ec} -ne 0 ]]; then
  echo "Login failed (remote command exit ${login_ec})." >&2
  cat "${login_out}" >&2
  exit 1
fi

session_out="$(crash_ssh_exec "${CRASH_PASSWORD}" \
  "${CRASH_SSH_OPTS_BATCH[@]}" \
  -p "${CRASH_SSH_PORT}" \
  "${CRASH_USER}@${CRASH_HOST}" \
  tsa session 2>&1)" || true

if ! printf '%s\n' "${session_out}" | grep -q 'authorized: true'; then
  echo "Login did not establish an authorized session." >&2
  printf '%s\n' "${session_out}" >&2
  exit 1
fi

printf 'Login successful: authenticated as %s at %s:%s\n' \
  "${LOGIN_USERNAME}" "${CRASH_HOST}" "${CRASH_SSH_PORT}"

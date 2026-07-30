#!/usr/bin/env bash

_CRASH_SH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRASH_SSH_EXPECT_SCRIPT="${_CRASH_SH_DIR}/crash-ssh-run.expect"

CRASH_SSH_BASE_OPTS=(
  -o BatchMode=no
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -o PreferredAuthentications=keyboard-interactive,password
  -o PubkeyAuthentication=no
  -o HostKeyAlgorithms=+ssh-rsa
  -o PubkeyAcceptedAlgorithms=+ssh-rsa
  -o KexAlgorithms=+diffie-hellman-group14-sha1
)

CRASH_SSH_OPTS_BATCH=(
  -T
  "${CRASH_SSH_BASE_OPTS[@]}"
)

CRASH_SSH_OPTS_INTERACTIVE=(
  -tt
  "${CRASH_SSH_BASE_OPTS[@]}"
)

CRASH_SSH_OPTS=(
  "${CRASH_SSH_OPTS_INTERACTIVE[@]}"
)

crash_ssh_check_port() {
  local host="$1"
  local port="$2"
  if command -v nc >/dev/null 2>&1; then
    if ! nc -z -w 2 "${host}" "${port}" 2>/dev/null; then
      echo "No TCP listener on ${host}:${port}. Start the TSANet client demo with crash.enabled=true." >&2
      return 1
    fi
  fi
  return 0
}

crash_ssh_require_tool() {
  if command -v expect >/dev/null 2>&1 && [[ -f "${CRASH_SSH_EXPECT_SCRIPT}" ]]; then
    return 0
  fi
  if command -v sshpass >/dev/null 2>&1; then
    return 0
  fi
  echo "Install expect (preinstalled on macOS as /usr/bin/expect) or sshpass." >&2
  return 1
}

crash_ssh_exec() {
  local password="$1"
  shift
  if command -v expect >/dev/null 2>&1 && [[ -f "${CRASH_SSH_EXPECT_SCRIPT}" ]]; then
    export CRASH_SSH_PASS="$password"
    expect -f "${CRASH_SSH_EXPECT_SCRIPT}" -- "$@"
    local ec=$?
    unset CRASH_SSH_PASS
    return "${ec}"
  fi
  if command -v sshpass >/dev/null 2>&1; then
    SSHPASS="${password}" sshpass -e ssh "$@"
    return $?
  fi
  echo "Neither expect nor sshpass is available." >&2
  return 127
}

unset _CRASH_SH_DIR

#!/usr/bin/env bash
_tsa_demo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
_tsa_crash_props="${_tsa_demo_root}/TSANet-integration-app/src/main/resources/tsanet-demo-crash-ssh.properties"
if [[ -f "${_tsa_crash_props}" ]]; then
  _uline="$(grep -E '^crash\.auth-username=' "${_tsa_crash_props}" | tail -1 || true)"
  if [[ -n "${_uline}" ]]; then
    _u="${_uline#crash.auth-username=}"
    _u="${_u//$'\r'/}"
    CRASH_USER="${CRASH_USER:-${_u}}"
  fi
  _line="$(grep -E '^crash\.auth-password=' "${_tsa_crash_props}" | tail -1 || true)"
  if [[ -n "${_line}" ]]; then
    _from_props="${_line#crash.auth-password=}"
    _from_props="${_from_props//$'\r'/}"
    CRASH_PASSWORD="${CRASH_PASSWORD:-${_from_props}}"
  fi
fi
CRASH_USER="${CRASH_USER:-crash}"
CRASH_PASSWORD="${CRASH_PASSWORD:-crash}"
unset _tsa_demo_root _tsa_crash_props _uline _u _line _from_props

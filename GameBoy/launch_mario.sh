#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  bash launch_mario.sh          # launch Super Mario Bros. in GTK
  bash launch_mario.sh --probe  # start runtime, verify bridge, then exit

From the ZU3 desktop terminal, DISPLAY is usually already set.
From remote SSH for the physical display, either set DISPLAY=:0 or let this
script default to :0 when /tmp/.X11-unix/X0 exists. The script re-runs itself
through sudo -E when needed so the runtime, bridge, and GUI share one UID.
EOF
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  ""|--probe)
    MODE="${1:-run}"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

if [[ -z "${DISPLAY:-}" && -S /tmp/.X11-unix/X0 ]]; then
  export DISPLAY=:0
fi

if [[ -z "${DISPLAY:-}" ]]; then
  echo "DISPLAY is not set. For the physical desktop, run: DISPLAY=:0 bash launch_mario.sh" >&2
  exit 1
fi

if [[ "$EUID" -ne 0 ]]; then
  echo "Re-running with sudo -E so the runtime, bridge, and GUI use the same privileged context."
  exec sudo -E bash "$0" "$@"
fi

BOARD_USER="${SUDO_USER:-mason}"
BOARD_UID="${SUDO_UID:-1001}"
USER_HOME="$(python3 - "$BOARD_USER" <<'PY'
import pwd, sys
try:
    print(pwd.getpwnam(sys.argv[1]).pw_dir)
except KeyError:
    print(f"/home/{sys.argv[1]}")
PY
)"

GB_ROOT="${GB_ROOT:-$USER_HOME/beethoven-gameboy-zu3/GameBoy}"
ROM="${ROM:-$GB_ROOT/target/roms/user/Super Mario Bros.gbc}"
RUNTIME="$GB_ROOT/target/synthesis/runtime/BeethovenRuntime"
BRIDGE="$GB_ROOT/sw/build/gameboy_beethoven_bridge"
LOG_DIR="$GB_ROOT/target/launch-mario"

export DISPLAY
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$BOARD_UID}"
export PYTHONPATH="$GB_ROOT/sw${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONPYCACHEPREFIX="${PYTHONPYCACHEPREFIX:-/tmp/gameboy_pycache}"
export BEETHOVEN_PROJECT_ROOT="$GB_ROOT"

find_xorg_auth() {
  python3 - "$DISPLAY" <<'PY'
import re, subprocess, sys

display = sys.argv[1]
match = re.match(r"^:([0-9]+)(?:\.[0-9]+)?$", display)
if not match:
    raise SystemExit(0)
needle = f":{match.group(1)}"
try:
    output = subprocess.check_output(["ps", "-eo", "args"], text=True)
except Exception:
    raise SystemExit(0)
for line in output.splitlines():
    if "Xorg" not in line or needle not in line or " -auth " not in line:
        continue
    auth_match = re.search(r"\s-auth\s+(\S+)", line)
    if auth_match:
        print(auth_match.group(1))
        break
PY
}

# Prefer the live Xorg -auth file for physical-display launches.  ~/.Xauthority
# can exist but still be wrong for the root-owned physical Xorg session.
if [[ -z "${XAUTHORITY:-}" ]]; then
  xorg_auth="$(find_xorg_auth)"
  if [[ -n "$xorg_auth" && -f "$xorg_auth" ]]; then
    export XAUTHORITY="$xorg_auth"
  fi
fi

if [[ -z "${XAUTHORITY:-}" ]]; then
  for candidate in "$USER_HOME/.Xauthority" "$XDG_RUNTIME_DIR/gdm/Xauthority" "$XDG_RUNTIME_DIR/Xauthority"; do
    if [[ -f "$candidate" ]]; then
      export XAUTHORITY="$candidate"
      break
    fi
  done
fi

mkdir -p "$LOG_DIR"

require_file() {
  if [[ ! -e "$1" ]]; then
    echo "missing required file: $1" >&2
    exit 1
  fi
}

bridge_ready() {
  printf 'status\nquit\n' | timeout 3 "$BRIDGE" >/dev/null 2>&1
}

print_last_lines() {
  python3 - "$1" "$2" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
limit = int(sys.argv[2])
try:
    lines = path.read_text(errors="replace").splitlines()
except OSError:
    lines = []
for line in lines[-limit:]:
    print(line)
PY
}

print_runtime_logs() {
  echo "Runtime stdout: $LOG_DIR/runtime.out" >&2
  if [[ -s "$LOG_DIR/runtime.out" ]]; then
    print_last_lines "$LOG_DIR/runtime.out" 80 >&2 || true
  fi
  echo "Runtime stderr: $LOG_DIR/runtime.err" >&2
  if [[ -s "$LOG_DIR/runtime.err" ]]; then
    print_last_lines "$LOG_DIR/runtime.err" 120 >&2 || true
  fi
}

require_file "$RUNTIME"
require_file "$BRIDGE"
require_file "$ROM"

cd "$GB_ROOT"

echo "GameBoy root: $GB_ROOT"
echo "ROM: $ROM"
echo "DISPLAY: $DISPLAY"
echo "XDG_RUNTIME_DIR: $XDG_RUNTIME_DIR"
if [[ -n "${XAUTHORITY:-}" ]]; then
  echo "XAUTHORITY: $XAUTHORITY"
fi

# Keep required hugepage pools provisioned for bridge-owned buffers.
echo 32 > /sys/kernel/mm/hugepages/hugepages-2048kB/nr_hugepages || true
echo 4 > /sys/kernel/mm/hugepages/hugepages-32768kB/nr_hugepages || true

started_runtime=0
runtime_pid=""
cleanup() {
  if [[ "$started_runtime" -eq 1 ]]; then
    echo "Stopping BeethovenRuntime..."
    pkill -TERM -f "$RUNTIME" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

if bridge_ready; then
  echo "Using existing BeethovenRuntime."
else
  if pgrep -f "$RUNTIME" >/dev/null 2>&1; then
    echo "Found a stale BeethovenRuntime that did not answer bridge probes; stopping it."
    pkill -TERM -f "$RUNTIME" >/dev/null 2>&1 || true
    sleep 1
  fi

  echo "Starting BeethovenRuntime..."
  : >"$LOG_DIR/runtime.out"
  : >"$LOG_DIR/runtime.err"
  "$RUNTIME" >"$LOG_DIR/runtime.out" 2>"$LOG_DIR/runtime.err" &
  runtime_pid=$!
  started_runtime=1

  echo "Waiting for bridge..."
  ready=0
  for _ in $(seq 1 40); do
    if bridge_ready; then
      ready=1
      break
    fi
    if ! ps -p "$runtime_pid" >/dev/null 2>&1; then
      echo "BeethovenRuntime exited before the bridge became ready." >&2
      print_runtime_logs
      exit 1
    fi
    sleep 0.5
  done
  if [[ "$ready" -ne 1 ]]; then
    echo "BeethovenRuntime did not become ready." >&2
    print_runtime_logs
    exit 1
  fi
fi

if [[ "$MODE" == "--probe" ]]; then
  echo "Bridge ready. Probe complete."
  exit 0
fi

echo "Launching Super Mario Bros. Controls: arrows=D-pad, z=A, x=B, a=Select, s=Start."
echo "Close the GTK window to stop."
python3 -m gameboy_host \
  --bridge-bin "$BRIDGE" \
  --rom "$ROM" \
  --run \
  --gtk \
  --no-audio

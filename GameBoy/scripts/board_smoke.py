#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys
import time

GAMEBOY_ROOT = Path(__file__).resolve().parents[1]
SW_ROOT = GAMEBOY_ROOT / "sw"
sys.path.insert(0, str(SW_ROOT))

from sim_smoke import (  # noqa: E402
    bridge_buffer_roundtrip,
    bridge_status_once,
    run_core_smoke,
    run_persistence_smoke,
    write_smoke_rom,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run a non-interactive smoke test against a programmed AUP-ZU3 "
            "GameBoy Beethoven design."
        )
    )
    parser.add_argument(
        "--bridge-bin",
        type=Path,
        default=GAMEBOY_ROOT / "sw" / "build" / "gameboy_beethoven_bridge",
        help="Board bridge binary built against libbeethoven-zynq.",
    )
    parser.add_argument(
        "--runtime-bin",
        type=Path,
        default=GAMEBOY_ROOT / "target" / "synthesis" / "runtime" / "BeethovenRuntime",
        help="Board synthesis runtime binary.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=GAMEBOY_ROOT / "target" / "board-smoke",
        help="Directory for generated smoke ROMs and runtime logs.",
    )
    parser.add_argument("--audio-samples", type=int, default=8192)
    parser.add_argument("--settle-seconds", type=float, default=0.25)
    parser.add_argument(
        "--start-runtime",
        action="store_true",
        help="Launch the synthesis BeethovenRuntime before probing the bridge.",
    )
    parser.add_argument(
        "--keep-runtime",
        action="store_true",
        help="Leave a runtime launched by this script running after the smoke test.",
    )
    parser.add_argument(
        "--startup-timeout",
        type=float,
        default=10.0,
        help="Seconds to wait for the bridge to answer after starting the runtime.",
    )
    return parser.parse_args()


def access_text(path: Path) -> str:
    exists = path.exists()
    read = os.access(path, os.R_OK)
    write = os.access(path, os.W_OK)
    return f"{path}: exists={int(exists)} read={int(read)} write={int(write)}"


def print_board_access_summary() -> None:
    for path in (
        Path("/dev/fpga0"),
        Path("/dev/mem"),
        Path("/sys/class/fpga_manager/fpga0/firmware"),
        Path("/sys/class/fpga_manager/fpga0/state"),
    ):
        print(access_text(path))
    state_path = Path("/sys/class/fpga_manager/fpga0/state")
    if state_path.exists():
        try:
            print(f"{state_path}={state_path.read_text(encoding='utf-8').strip()}")
        except OSError as ex:
            print(f"{state_path}=unreadable ({ex})")


def format_probe_failure(last: subprocess.CompletedProcess[str] | None, timed_out: bool) -> str:
    if timed_out:
        return "bridge status probe timed out"
    if last is None:
        return "bridge status probe did not run"
    return (
        f"bridge status probe failed; returncode={last.returncode} "
        f"stdout={last.stdout.strip()!r} stderr={last.stderr.strip()!r}"
    )


def probe_bridge_once(bridge_bin: Path) -> tuple[subprocess.CompletedProcess[str] | None, bool]:
    try:
        last = bridge_status_once(bridge_bin, timeout=2.0)
    except subprocess.TimeoutExpired:
        return None, True
    if last.returncode == 0 and "status " in last.stdout and "ok" in last.stdout:
        return last, False
    return last, False


def wait_for_bridge(bridge_bin: Path, deadline: float) -> subprocess.CompletedProcess[str]:
    last: subprocess.CompletedProcess[str] | None = None
    timed_out = False
    while time.monotonic() < deadline:
        current, current_timed_out = probe_bridge_once(bridge_bin)
        timed_out = current_timed_out
        if current is not None:
            last = current
            if current.returncode == 0 and "status " in current.stdout and "ok" in current.stdout:
                return current
        time.sleep(0.5)
    raise RuntimeError(format_probe_failure(last, timed_out))


def launch_runtime(args: argparse.Namespace) -> subprocess.Popen[str] | None:
    if not args.start_runtime:
        wait_for_bridge(args.bridge_bin, time.monotonic() + 1.0)
        return None

    if not args.runtime_bin.exists():
        raise FileNotFoundError(args.runtime_bin)

    args.work_dir.mkdir(parents=True, exist_ok=True)
    stdout = (args.work_dir / "runtime.out").open("w", encoding="utf-8")
    stderr = (args.work_dir / "runtime.err").open("w", encoding="utf-8")
    env = os.environ.copy()
    env["BEETHOVEN_PROJECT_ROOT"] = str(GAMEBOY_ROOT)
    proc = subprocess.Popen(
        [str(args.runtime_bin)],
        cwd=GAMEBOY_ROOT,
        env=env,
        text=True,
        stdout=stdout,
        stderr=stderr,
    )
    stdout.close()
    stderr.close()

    deadline = time.monotonic() + args.startup_timeout
    last: subprocess.CompletedProcess[str] | None = None
    timed_out = False
    try:
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                runtime_stdout = (args.work_dir / "runtime.out").read_text(encoding="utf-8", errors="replace").strip()
                runtime_stderr = (args.work_dir / "runtime.err").read_text(encoding="utf-8", errors="replace").strip()
                raise RuntimeError(
                    "runtime exited before bridge became ready "
                    f"(code={proc.returncode}); stdout={runtime_stdout!r} stderr={runtime_stderr!r}"
                )
            current, current_timed_out = probe_bridge_once(args.bridge_bin)
            timed_out = current_timed_out
            if current is not None:
                last = current
                if current.returncode == 0 and "status " in current.stdout and "ok" in current.stdout:
                    return proc
            time.sleep(0.5)

        raise RuntimeError(format_probe_failure(last, timed_out))
    except BaseException:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        raise


def main() -> None:
    args = parse_args()
    args.bridge_bin = args.bridge_bin.resolve()
    args.runtime_bin = args.runtime_bin.resolve()
    args.work_dir = args.work_dir.resolve()

    if not args.bridge_bin.exists():
        raise FileNotFoundError(args.bridge_bin)

    print_board_access_summary()

    args.work_dir.mkdir(parents=True, exist_ok=True)
    rom_only = args.work_dir / "board_smoke_rom_only.gb"
    rtc_rom = args.work_dir / "board_smoke_mbc3_rtc.gb"
    write_smoke_rom(rom_only, cart_type=0x00, ram_size_code=0)
    write_smoke_rom(rtc_rom, cart_type=0x10, ram_size_code=2)

    runtime = launch_runtime(args)
    try:
        bridge_buffer_roundtrip(args.bridge_bin)
        run_core_smoke(args, rom_only)
        run_persistence_smoke(args, rtc_rom)
        print("board_smoke ok")
    finally:
        if runtime is not None and not args.keep_runtime:
            runtime.terminate()
            try:
                runtime.wait(timeout=5)
            except subprocess.TimeoutExpired:
                runtime.kill()
                runtime.wait(timeout=5)


if __name__ == "__main__":
    main()

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

from gameboy_host.bridge import GameboyBridge  # noqa: E402
from gameboy_host.buffers import BridgeBuffer  # noqa: E402
from gameboy_host.device import FRAME_BYTES, GameboyDevice  # noqa: E402
from gameboy_host.registers import Joypad  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the GameBoy Beethoven simulation smoke test")
    parser.add_argument("--runtime-bin", type=Path, default=GAMEBOY_ROOT / "target/simulation/runtime/BeethovenRuntime")
    parser.add_argument("--bridge-bin", type=Path, default=GAMEBOY_ROOT / "sw/build/gameboy_beethoven_bridge_sim")
    parser.add_argument(
        "--dramconfig",
        type=Path,
        default=Path.home() / ".local/share/beethoven/runtime-src/DRAMsim3/configs/DDR4_8Gb_x16_3200.ini",
    )
    parser.add_argument("--work-dir", type=Path, default=GAMEBOY_ROOT / "target/sim-smoke")
    parser.add_argument("--startup-timeout", type=float, default=20.0)
    parser.add_argument("--settle-seconds", type=float, default=0.05)
    parser.add_argument("--audio-samples", type=int, default=1024)
    parser.add_argument("--reuse-runtime", action="store_true", help="Use an already running BeethovenRuntime")
    parser.add_argument("--keep-runtime", action="store_true", help="Leave a runtime launched by this script running")
    return parser.parse_args()


def write_smoke_rom(path: Path, *, cart_type: int, ram_size_code: int, cgb: bool = True) -> None:
    data = bytearray(32 * 1024)
    data[0x100] = 0x18  # jr -2: keep the synthetic program self-contained.
    data[0x101] = 0xFE
    title = b"BEETHOVENGB"
    data[0x134:0x134 + len(title)] = title
    data[0x143] = 0x80 if cgb else 0x00
    data[0x147] = cart_type
    data[0x148] = 0x00
    data[0x149] = ram_size_code
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def bridge_status_once(bridge_bin: Path, timeout: float = 3.0) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(bridge_bin)],
        input="status\nquit\n",
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        cwd=GAMEBOY_ROOT,
        check=False,
    )


def wait_for_bridge(bridge_bin: Path, deadline: float) -> subprocess.CompletedProcess[str]:
    last: subprocess.CompletedProcess[str] | None = None
    while time.monotonic() < deadline:
        try:
            last = bridge_status_once(bridge_bin)
            if last.returncode == 0 and "status " in last.stdout and "ok" in last.stdout:
                return last
        except subprocess.TimeoutExpired:
            last = None
        time.sleep(0.5)
    if last is None:
        raise RuntimeError("bridge status probe timed out")
    raise RuntimeError(
        "bridge status probe failed\n"
        f"returncode={last.returncode}\nstdout={last.stdout}\nstderr={last.stderr}"
    )


def launch_runtime(args: argparse.Namespace) -> tuple[subprocess.Popen[str] | None, bool]:
    if args.reuse_runtime:
        wait_for_bridge(args.bridge_bin, time.monotonic() + args.startup_timeout)
        return None, False

    if not args.runtime_bin.exists():
        raise FileNotFoundError(args.runtime_bin)
    if not args.dramconfig.exists():
        raise FileNotFoundError(args.dramconfig)

    args.work_dir.mkdir(parents=True, exist_ok=True)
    stdout_path = args.work_dir / "runtime.out"
    stderr_path = args.work_dir / "runtime.err"
    stdout = stdout_path.open("w")
    stderr = stderr_path.open("w")
    env = os.environ.copy()
    env["BEETHOVEN_PROJECT_ROOT"] = str(GAMEBOY_ROOT)
    proc = subprocess.Popen(
        [str(args.runtime_bin), "-dramconfig", str(args.dramconfig)],
        cwd=GAMEBOY_ROOT,
        env=env,
        text=True,
        stdout=stdout,
        stderr=stderr,
    )
    stdout.close()
    stderr.close()

    deadline = time.monotonic() + args.startup_timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            try:
                wait_for_bridge(args.bridge_bin, time.monotonic() + 1.0)
                print("runtime launch exited; using existing runtime detected by bridge probe")
                return None, False
            except Exception as ex:
                raise RuntimeError(
                    f"runtime exited with code {proc.returncode}; see {stdout_path} and {stderr_path}"
                ) from ex
        try:
            wait_for_bridge(args.bridge_bin, time.monotonic() + 1.0)
            return proc, True
        except Exception:
            time.sleep(0.5)

    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)
    raise RuntimeError(f"runtime did not become ready; see {stdout_path} and {stderr_path}")


def bridge_buffer_roundtrip(bridge_bin: Path) -> None:
    with GameboyBridge(bridge_bin) as bridge:
        phys, size = bridge.alloc_buffer("roundtrip", 4096)
        if size != 4096 or phys == 0:
            raise AssertionError(f"bad allocation phys={phys} size={size}")
        bridge.buffer_write("roundtrip", 64, b"hello")
        if bridge.buffer_read("roundtrip", 64, 5) != b"hello":
            raise AssertionError("bridge buffer readback mismatch")
        status = bridge.status()
        print(
            "bridge_roundtrip ok "
            f"phys=0x{phys:x} frameCounter={status.frame_counter} audioWriteIndex={status.audio_write_index}"
        )


def run_core_smoke(args: argparse.Namespace, rom_path: Path) -> None:
    with GameboyBridge(args.bridge_bin) as bridge:
        rom = BridgeBuffer(bridge, "rom", 32 * 1024)
        save = BridgeBuffer(bridge, "save", 8 * 1024)
        frame = BridgeBuffer(bridge, "frame", FRAME_BYTES * 3)
        audio = BridgeBuffer(bridge, "audio", args.audio_samples * 4)
        with GameboyDevice(
            bridge=bridge,
            rom_buffer=rom,
            save_buffer=save,
            frame_buffer=frame,
            audio_buffer=audio,
        ) as dev:
            dev.set_cgb(True)
            dev.configure_framebuffers()
            dev.configure_audio(args.audio_samples)
            header = dev.load_rom(rom_path)
            dev.reset()
            dev.set_running(True)
            time.sleep(args.settle_seconds)
            dev.set_joypad(Joypad.RIGHT | Joypad.A)
            status = dev.status()
            frame_prefix = bytes(dev.latest_frame_view()[:16]).hex()
            audio_prefix = audio.read(0, min(64, args.audio_samples * 4)).hex()
            dev.set_audio_read_index(status.audio_write_index)
            print(
                "core_smoke ok "
                f"cart=0x{header.cartridge_type:02x} rom={header.rom_size} ram={header.ram_size} "
                f"frameCounter={status.frame_counter} audioWriteIndex={status.audio_write_index} "
                f"frameCompletedIndex={status.frame_completed_index} vblank={int(status.vblank)} "
                f"audioOverrun={int(status.audio_overrun)} frame_prefix={frame_prefix} "
                f"audio_prefix={audio_prefix[:32]}"
            )


def run_persistence_smoke(args: argparse.Namespace, rom_path: Path) -> None:
    expected_save = bytes((i % 251 for i in range(8 * 1024)))
    expected_rtc = 0x12345
    sav_path = rom_path.with_suffix(".sav")
    rtc_path = rom_path.with_suffix(".rtc")
    sav_path.unlink(missing_ok=True)
    rtc_path.unlink(missing_ok=True)

    with GameboyBridge(args.bridge_bin) as bridge:
        rom = BridgeBuffer(bridge, "rtc_rom", 32 * 1024)
        save = BridgeBuffer(bridge, "rtc_save", 8 * 1024)
        frame = BridgeBuffer(bridge, "rtc_frame", FRAME_BYTES * 3)
        audio = BridgeBuffer(bridge, "rtc_audio", args.audio_samples * 4)
        dev = GameboyDevice(bridge=bridge, rom_buffer=rom, save_buffer=save, frame_buffer=frame, audio_buffer=audio)
        dev.set_cgb(True)
        dev.configure_framebuffers()
        dev.configure_audio(args.audio_samples)
        header = dev.load_rom(rom_path)
        save.write(expected_save)
        bridge.write_rtc(latched=False, state=expected_rtc)
        persisted_rtc = bridge.read_rtc(latched=False)
        dev.close()

    if sav_path.read_bytes() != expected_save:
        raise AssertionError("save RAM persistence mismatch")
    if int(rtc_path.read_text().strip(), 0) != persisted_rtc:
        raise AssertionError("RTC persistence mismatch")
    print(
        "persistence_smoke ok "
        f"cart=0x{header.cartridge_type:02x} save={sav_path.name} "
        f"rtc={rtc_path.name} rtc_state={persisted_rtc}"
    )


def main() -> None:
    args = parse_args()
    args.bridge_bin = args.bridge_bin.resolve()
    args.runtime_bin = args.runtime_bin.resolve()
    args.dramconfig = args.dramconfig.expanduser().resolve()
    args.work_dir = args.work_dir.resolve()

    if not args.bridge_bin.exists():
        raise FileNotFoundError(args.bridge_bin)

    rom_only = args.work_dir / "smoke_rom_only.gb"
    rtc_rom = args.work_dir / "smoke_mbc3_rtc.gb"
    write_smoke_rom(rom_only, cart_type=0x00, ram_size_code=0)
    write_smoke_rom(rtc_rom, cart_type=0x10, ram_size_code=2)

    runtime, own_runtime = launch_runtime(args)
    try:
        bridge_buffer_roundtrip(args.bridge_bin)
        run_core_smoke(args, rom_only)
        run_persistence_smoke(args, rtc_rom)
    finally:
        if runtime is not None and own_runtime and not args.keep_runtime:
            runtime.terminate()
            try:
                runtime.wait(timeout=5)
            except subprocess.TimeoutExpired:
                runtime.kill()
                runtime.wait(timeout=5)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

GAMEBOY_ROOT = Path(__file__).resolve().parents[1]
SW_ROOT = GAMEBOY_ROOT / "sw"
sys.path.insert(0, str(SW_ROOT))
sys.path.insert(0, str(GAMEBOY_ROOT / "scripts"))

from board_smoke import launch_runtime, print_board_access_summary  # noqa: E402
from gameboy_host.bridge import GameboyBridge  # noqa: E402
from gameboy_host.buffers import BridgeBuffer  # noqa: E402
from gameboy_host.device import FRAME_BYTES, GameboyDevice  # noqa: E402

STAT_STALLS = 0x0100
STAT_CLOCKS = 0x0104
DEBUG_VIDEO = 0x0108
DEBUG_CART = 0x010C


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Probe board-side GameBoy core debug registers.")
    parser.add_argument("--bridge-bin", type=Path, default=GAMEBOY_ROOT / "sw/build/gameboy_beethoven_bridge")
    parser.add_argument("--runtime-bin", type=Path, default=GAMEBOY_ROOT / "target/synthesis/runtime/BeethovenRuntime")
    parser.add_argument("--rom", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, default=GAMEBOY_ROOT / "target/board-debug-probe")
    parser.add_argument("--audio-samples", type=int, default=8192)
    parser.add_argument("--start-runtime", action="store_true")
    parser.add_argument("--keep-runtime", action="store_true")
    parser.add_argument("--startup-timeout", type=float, default=20.0)
    parser.add_argument("--samples", type=int, default=8)
    parser.add_argument("--interval", type=float, default=0.5)
    return parser.parse_args()


def decode_video(value: int) -> str:
    x = value & 0xFF
    y = (value >> 8) & 0xFF
    display_off = (value >> 16) & 1
    lcd = (value >> 17) & 1
    vblank = (value >> 18) & 1
    hblank = (value >> 19) & 1
    valid = (value >> 20) & 1
    return f"video=0x{value:08x} x={x} y={y} displayOff={display_off} lcd={lcd} vblank={vblank} hblank={hblank} valid={valid}"


def decode_cart(value: int) -> str:
    is_write = value & 1
    select_rom = (value >> 1) & 1
    access_start = (value >> 2) & 1
    busy = (value >> 3) & 1
    save_enable = (value >> 4) & 1
    save_done = (value >> 5) & 1
    rom_enable = (value >> 6) & 1
    rom_done = (value >> 7) & 1
    address = (value >> 9) & ((1 << 23) - 1)
    return (
        f"cart=0x{value:08x} addr=0x{address:06x} write={is_write} rom={select_rom} "
        f"start={access_start} busy={busy} romEn={rom_enable} romDone={rom_done} "
        f"saveEn={save_enable} saveDone={save_done}"
    )


def main() -> None:
    args = parse_args()
    args.bridge_bin = args.bridge_bin.resolve()
    args.runtime_bin = args.runtime_bin.resolve()
    args.rom = args.rom.resolve()
    args.work_dir = args.work_dir.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=True)

    print_board_access_summary()
    runtime = launch_runtime(args)
    try:
        with GameboyBridge(args.bridge_bin) as bridge:
            rom = BridgeBuffer(bridge, "probe_rom", 8 * 1024 * 1024)
            save = BridgeBuffer(bridge, "probe_save", 128 * 1024)
            frame = BridgeBuffer(bridge, "probe_frame", FRAME_BYTES * 3)
            audio = BridgeBuffer(bridge, "probe_audio", args.audio_samples * 4)
            with GameboyDevice(bridge=bridge, rom_buffer=rom, save_buffer=save, frame_buffer=frame, audio_buffer=audio) as dev:
                dev.set_cgb(True)
                dev.configure_framebuffers()
                dev.configure_audio(args.audio_samples)
                header = dev.load_rom(args.rom)
                dev.reset()
                dev.set_running(True)
                print(f"loaded cart=0x{header.cartridge_type:02x} rom={header.rom_size} ram={header.ram_size}")
                for i in range(args.samples):
                    time.sleep(args.interval)
                    status = dev.status()
                    stalls = bridge.debug_register(STAT_STALLS)
                    clocks = bridge.debug_register(STAT_CLOCKS)
                    video = bridge.debug_register(DEBUG_VIDEO)
                    cart = bridge.debug_register(DEBUG_CART)
                    print(
                        f"sample={i} frameCounter={status.frame_counter} audioWriteIndex={status.audio_write_index} "
                        f"completed={status.frame_completed_index} vblank={int(status.vblank)} overrun={int(status.audio_overrun)} "
                        f"clocks={clocks} stalls={stalls} {decode_video(video)} {decode_cart(cart)}"
                    )
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

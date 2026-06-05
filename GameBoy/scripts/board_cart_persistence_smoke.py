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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Exercise cartridge-driven external RAM and MBC3 RTC persistence on AUP-ZU3."
    )
    parser.add_argument("--bridge-bin", type=Path, default=GAMEBOY_ROOT / "sw/build/gameboy_beethoven_bridge")
    parser.add_argument("--runtime-bin", type=Path, default=GAMEBOY_ROOT / "target/synthesis/runtime/BeethovenRuntime")
    parser.add_argument("--work-dir", type=Path, default=GAMEBOY_ROOT / "target/board-cart-persistence")
    parser.add_argument("--audio-samples", type=int, default=8192)
    parser.add_argument("--start-runtime", action="store_true")
    parser.add_argument("--keep-runtime", action="store_true")
    parser.add_argument("--startup-timeout", type=float, default=20.0)
    parser.add_argument("--settle-seconds", type=float, default=0.25)
    return parser.parse_args()


def emit_rom(path: Path) -> None:
    # Minimal CGB-compatible MBC3+RAM+RTC cartridge program.
    # It enables external RAM/RTC, writes two RAM bytes, writes halted RTC fields,
    # then spins forever. This validates the CPU -> MBC -> shared-save path, not
    # just host-side buffer persistence.
    data = bytearray(32 * 1024)
    program = bytes([
        0xF3,                          # di
        0x31, 0xFE, 0xFF,              # ld sp,$FFFE
        0x3E, 0x0A,                    # ld a,$0A
        0xEA, 0x00, 0x00,              # ld ($0000),a ; RAM/RTC enable
        0xAF,                          # xor a
        0xEA, 0x00, 0x40,              # ld ($4000),a ; RAM bank 0
        0x21, 0x00, 0xA0,              # ld hl,$A000
        0x3E, 0x42,                    # ld a,$42
        0x77,                          # ld (hl),a
        0x23,                          # inc hl
        0x3E, 0x99,                    # ld a,$99
        0x77,                          # ld (hl),a
        0x3E, 0x08,                    # ld a,$08
        0xEA, 0x00, 0x40,              # ld ($4000),a ; RTC seconds
        0x3E, 0x12,                    # ld a,$12
        0xEA, 0x00, 0xA0,              # ld ($A000),a
        0x3E, 0x09,                    # ld a,$09
        0xEA, 0x00, 0x40,              # ld ($4000),a ; RTC minutes
        0x3E, 0x34,                    # ld a,$34
        0xEA, 0x00, 0xA0,              # ld ($A000),a
        0x3E, 0x0A,                    # ld a,$0A
        0xEA, 0x00, 0x40,              # ld ($4000),a ; RTC hours
        0x3E, 0x05,                    # ld a,$05
        0xEA, 0x00, 0xA0,              # ld ($A000),a
        0x3E, 0x0B,                    # ld a,$0B
        0xEA, 0x00, 0x40,              # ld ($4000),a ; RTC days low
        0x3E, 0x67,                    # ld a,$67
        0xEA, 0x00, 0xA0,              # ld ($A000),a
        0x3E, 0x0C,                    # ld a,$0C
        0xEA, 0x00, 0x40,              # ld ($4000),a ; RTC day high/halt
        0x3E, 0x40,                    # ld a,$40 ; halt RTC so value persists
        0xEA, 0x00, 0xA0,              # ld ($A000),a
        0x18, 0xFE,                    # jr -2
    ])
    data[0x100:0x100 + len(program)] = program
    title = b"BEEBRTCWRITE"
    data[0x134:0x134 + len(title)] = title
    data[0x143] = 0x80  # CGB-compatible
    data[0x147] = 0x10  # MBC3 + timer + RAM + battery
    data[0x148] = 0x00  # 32 KiB ROM
    data[0x149] = 0x02  # 8 KiB RAM
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def main() -> None:
    args = parse_args()
    args.bridge_bin = args.bridge_bin.resolve()
    args.runtime_bin = args.runtime_bin.resolve()
    args.work_dir = args.work_dir.resolve()
    args.work_dir.mkdir(parents=True, exist_ok=True)
    rom_path = args.work_dir / "mbc3_ram_rtc_write.gb"
    sav_path = rom_path.with_suffix(".sav")
    rtc_path = rom_path.with_suffix(".rtc")
    sav_path.unlink(missing_ok=True)
    rtc_path.unlink(missing_ok=True)
    emit_rom(rom_path)

    print_board_access_summary()
    runtime = launch_runtime(args)
    try:
        with GameboyBridge(args.bridge_bin) as bridge:
            rom = BridgeBuffer(bridge, "cart_rom", 32 * 1024)
            save = BridgeBuffer(bridge, "cart_save", 8 * 1024)
            frame = BridgeBuffer(bridge, "cart_frame", FRAME_BYTES * 3)
            audio = BridgeBuffer(bridge, "cart_audio", args.audio_samples * 4)
            dev = GameboyDevice(bridge=bridge, rom_buffer=rom, save_buffer=save, frame_buffer=frame, audio_buffer=audio)
            dev.set_cgb(True)
            dev.configure_framebuffers()
            dev.configure_audio(args.audio_samples)
            header = dev.load_rom(rom_path)
            dev.reset()
            dev.set_running(True)
            time.sleep(args.settle_seconds)
            status = dev.status()
            ram_prefix = save.read(0, 16)
            if ram_prefix[:2] != b"\x42\x99":
                raise AssertionError(f"external RAM write mismatch: {ram_prefix[:16].hex()}")
            rtc_state = bridge.read_rtc(latched=False)
            if rtc_state == 0:
                raise AssertionError("RTC state did not change after cartridge writes")
            dev.close()

    finally:
        if runtime is not None and not args.keep_runtime:
            runtime.terminate()
            try:
                runtime.wait(timeout=5)
            except subprocess.TimeoutExpired:
                runtime.kill()
                runtime.wait(timeout=5)

    if sav_path.read_bytes()[:2] != b"\x42\x99":
        raise AssertionError("persisted .sav does not contain cartridge-written bytes")
    persisted_rtc = int(rtc_path.read_text().strip(), 0)
    if persisted_rtc == 0:
        raise AssertionError("persisted .rtc is zero")
    print(
        "board_cart_persistence_smoke ok "
        f"cart=0x{header.cartridge_type:02x} ram={header.ram_size} "
        f"frameCounter={status.frame_counter} ram_prefix={ram_prefix[:16].hex()} rtc_state={persisted_rtc} "
        f"save={sav_path} rtc={rtc_path}"
    )


if __name__ == "__main__":
    main()

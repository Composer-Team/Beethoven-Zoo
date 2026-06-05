#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import struct
import sys

GAMEBOY_ROOT = Path(__file__).resolve().parents[1]
SW_ROOT = GAMEBOY_ROOT / "sw"
sys.path.insert(0, str(SW_ROOT))

from gameboy_host.audio import audio_ring_chunk_to_aplay_pcm  # noqa: E402
from gameboy_host.device import FRAME_BYTES, GameboyDevice  # noqa: E402
from gameboy_host.gamepad import (  # noqa: E402
    ABS_HAT0X,
    ABS_HAT0Y,
    ABS_X,
    ABS_Y,
    BTN_A,
    KEY_POWER,
    AxisInfo,
    LinuxGamepad,
)
from gameboy_host.gtk_app import rgb555_to_rgb888  # noqa: E402
from gameboy_host.registers import Joypad, Register  # noqa: E402


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def smoke_rgb_conversion() -> None:
    frame = memoryview(struct.pack("<HHH", 0x001F, 0x03E0, 0x7C00))
    rgb = rgb555_to_rgb888(frame)
    expected = bytes([255, 0, 0, 0, 255, 0, 0, 0, 255])
    require(rgb[: len(expected)] == expected, f"bad RGB conversion prefix: {rgb[:len(expected)].hex()}")
    require(len(rgb) == 160 * 144 * 3, "RGB conversion should produce one full display frame")


def smoke_gamepad_decoder() -> None:
    gamepad = LinuxGamepad.__new__(LinuxGamepad)
    gamepad._fd = None
    gamepad._buttons = 0
    gamepad._axis_buttons = 0
    gamepad._axis_info = {
        ABS_X: AxisInfo(minimum=0, maximum=65535, flat=0),
        ABS_Y: AxisInfo(minimum=0, maximum=255, flat=0),
    }

    gamepad._handle_abs(ABS_X, 32768)
    require(gamepad.poll().buttons == 0, "unsigned centered ABS_X should be neutral")
    gamepad._handle_abs(ABS_X, 0)
    require(gamepad.poll().buttons & Joypad.LEFT, "ABS_X minimum should press left")
    gamepad._handle_abs(ABS_X, 65535)
    require(gamepad.poll().buttons & Joypad.RIGHT, "ABS_X maximum should press right")
    gamepad._handle_abs(ABS_Y, 0)
    require(gamepad.poll().buttons & Joypad.UP, "ABS_Y minimum should press up")
    gamepad._handle_abs(ABS_Y, 255)
    require(gamepad.poll().buttons & Joypad.DOWN, "ABS_Y maximum should press down")
    gamepad._handle_abs(ABS_HAT0Y, 0)
    require((gamepad.poll().buttons & (Joypad.UP | Joypad.DOWN)) == 0, "neutral HAT0Y should clear vertical dpad")

    gamepad._handle_key(BTN_A, 1)
    require(gamepad.poll().buttons & Joypad.A, "BTN_A press should set A")
    gamepad._handle_key(BTN_A, 0)
    require((gamepad.poll().buttons & Joypad.A) == 0, "BTN_A release should clear A")
    require(
        not LinuxGamepad._has_gamepad_controls_from_sets({KEY_POWER}, set()),
        "gpio-keys power button should not be discovered as a gamepad",
    )
    require(
        LinuxGamepad._has_gamepad_controls_from_sets({BTN_A}, set()),
        "BTN_A capability should be discovered as a gamepad",
    )
    require(
        LinuxGamepad._has_gamepad_controls_from_sets(set(), {ABS_HAT0X, ABS_HAT0Y}),
        "hat axis capabilities should be discovered as a gamepad",
    )


def smoke_audio_conversion() -> None:
    # PL writes Cat(left, right), which lands in DDR as right then left on the
    # little-endian PS. aplay expects left then right S16_LE.
    ring_chunk = bytes.fromhex("22114433ddccbbaa")
    require(
        audio_ring_chunk_to_aplay_pcm(ring_chunk) == bytes.fromhex("44332211bbaaddcc"),
        "audio sample channel/byte order conversion mismatch",
    )


def smoke_direct_mmio_address_writes() -> None:
    class FakeRegs:
        def __init__(self) -> None:
            self.values: dict[Register, int] = {}

        def write32(self, offset: Register, value: int) -> None:
            self.values[offset] = value & 0xFFFF_FFFF

        def close(self) -> None:
            return None

    class FakeBuffer:
        def __init__(self, physical_address: int, size: int) -> None:
            self.physical_address = physical_address
            self.size = size

        def close(self) -> None:
            return None

    regs = FakeRegs()
    frame_base = 0x1_0000_2000
    audio_base = 0x2_0000_3000
    device = GameboyDevice(
        register_base=0,
        control_mmio=regs,
        frame_buffer=FakeBuffer(frame_base, FRAME_BYTES * 3),
        audio_buffer=FakeBuffer(audio_base, 4096),
    )
    device.configure_framebuffers()
    device.configure_audio(16)

    require(regs.values[Register.FRAME_BASE_0_LO] == 0x0000_2000, "frame base 0 low word mismatch")
    require(regs.values[Register.FRAME_BASE_0_HI] == 0x1, "frame base 0 high word mismatch")
    require(regs.values[Register.FRAME_BASE_1_HI] == 0x1, "frame base 1 high word mismatch")
    require(regs.values[Register.FRAME_BASE_2_HI] == 0x1, "frame base 2 high word mismatch")
    require(regs.values[Register.AUDIO_BASE_LO] == 0x0000_3000, "audio base low word mismatch")
    require(regs.values[Register.AUDIO_BASE_HI] == 0x2, "audio base high word mismatch")


def main() -> None:
    smoke_rgb_conversion()
    smoke_gamepad_decoder()
    smoke_audio_conversion()
    smoke_direct_mmio_address_writes()
    print("host_smoke ok")


if __name__ == "__main__":
    main()

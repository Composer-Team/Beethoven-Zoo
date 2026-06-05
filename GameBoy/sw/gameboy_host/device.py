from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .bridge import GameboyBridge, GameboyStatus
from .buffers import BridgeBuffer, Udmabuf
from .mmio import Mmio
from .registers import CartConfig, Control, Register
from .rom import RomHeader, parse_rom_header


FRAME_WIDTH = 160
FRAME_HEIGHT = 144
FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * 2


@dataclass(frozen=True)
class SavePaths:
    ram: Path
    rtc: Path


class GameboyDevice:
    def __init__(
        self,
        register_base: int | None = None,
        *,
        bridge: GameboyBridge | None = None,
        control_mmio: Mmio | None = None,
        rom_buffer: Udmabuf | BridgeBuffer | None = None,
        save_buffer: Udmabuf | BridgeBuffer | None = None,
        frame_buffer: Udmabuf | BridgeBuffer | None = None,
        audio_buffer: Udmabuf | BridgeBuffer | None = None,
    ) -> None:
        if bridge is None and register_base is None and control_mmio is None:
            raise ValueError("register_base or bridge is required")
        self.bridge = bridge
        self.regs = control_mmio or (Mmio(register_base) if bridge is None else None)
        self.rom_buffer = rom_buffer
        self.save_buffer = save_buffer
        self.frame_buffer = frame_buffer
        self.audio_buffer = audio_buffer
        self.header: RomHeader | None = None
        self.audio_capacity_samples = 0
        self._control = 0
        self._buttons = 0
        self._audio_read_index = 0
        self._save_paths: SavePaths | None = None
        self._last_status = GameboyStatus(0, 0, 0, False, False)

    def close(self) -> None:
        self.save_persisted_state()
        if self.bridge is not None:
            self.bridge.close()
        if self.regs is not None:
            self.regs.close()
        for buf in (self.rom_buffer, self.save_buffer, self.frame_buffer, self.audio_buffer):
            if buf is not None:
                buf.close()

    def reset(self) -> None:
        if self.bridge is not None:
            self._send_control(reset=True)
            return
        self._control |= Control.RESET
        self.regs.write32(Register.CONTROL, self._control)
        self._control &= ~Control.RESET
        self.regs.write32(Register.CONTROL, self._control)

    def set_running(self, running: bool) -> None:
        if running:
            self._control |= Control.RUN
        else:
            self._control &= ~Control.RUN
        if self.bridge is not None:
            self._send_control()
        else:
            self.regs.write32(Register.CONTROL, self._control)

    def set_cgb(self, enabled: bool) -> None:
        if enabled:
            self._control |= Control.CGB
        else:
            self._control &= ~Control.CGB
        if self.bridge is not None:
            self._apply_configuration()
        else:
            self.regs.write32(Register.CONTROL, self._control)

    def set_joypad(self, state: int) -> None:
        self._buttons = state & 0xFF
        if self.bridge is not None:
            self._send_control()
        else:
            self.regs.write32(Register.JOYPAD, self._buttons)

    def set_audio_read_index(self, index: int) -> None:
        self._audio_read_index = index & 0xFFFFFF
        if self.bridge is not None:
            self._send_control()
        else:
            self.regs.write32(Register.AUDIO_READ_INDEX, self._audio_read_index)

    def load_rom(self, path: Path) -> RomHeader:
        if self.rom_buffer is None:
            raise RuntimeError("ROM buffer is not configured")
        data = path.read_bytes()
        header = parse_rom_header(data)
        if len(data) > self.rom_buffer.size:
            raise ValueError(f"ROM is {len(data)} bytes but buffer is only {self.rom_buffer.size} bytes")

        self.set_running(False)
        self.rom_buffer.write(data)
        if len(data) < self.rom_buffer.size:
            self.rom_buffer.zero(len(data), self.rom_buffer.size - len(data))
        self.header = header
        self._save_paths = SavePaths(
            ram=path.with_suffix(".sav"),
            rtc=path.with_suffix(".rtc"),
        )

        if self.bridge is None:
            self._write_address(Register.ROM_BASE_LO, Register.ROM_BASE_HI, self.rom_buffer.physical_address)
            self.regs.write32(Register.ROM_MASK, header.rom_size - 1)
            self.regs.write32(Register.CART_CONFIG, header.cart_config)
            if self.save_buffer is not None and header.ram_size > 0:
                self._write_address(Register.SAVE_BASE_LO, Register.SAVE_BASE_HI, self.save_buffer.physical_address)
                self.regs.write32(Register.SAVE_MASK, header.ram_size - 1)
        self._load_persisted_state()
        self._apply_configuration()
        return header

    def configure_framebuffers(self) -> None:
        if self.frame_buffer is None:
            raise RuntimeError("frame buffer is not configured")
        if self.frame_buffer.size < FRAME_BYTES * 3:
            raise ValueError("frame buffer must hold three 160x144 RGB555/RGB565 frames")
        if self.bridge is None:
            base = self.frame_buffer.physical_address
            self._write_address(Register.FRAME_BASE_0_LO, Register.FRAME_BASE_0_HI, base)
            self._write_address(Register.FRAME_BASE_1_LO, Register.FRAME_BASE_1_HI, base + FRAME_BYTES)
            self._write_address(Register.FRAME_BASE_2_LO, Register.FRAME_BASE_2_HI, base + (FRAME_BYTES * 2))
        self._apply_configuration()

    def configure_audio(self, capacity_samples: int) -> None:
        if self.audio_buffer is None:
            raise RuntimeError("audio buffer is not configured")
        if capacity_samples * 4 > self.audio_buffer.size:
            raise ValueError("audio capacity exceeds audio buffer size")
        self.audio_capacity_samples = capacity_samples
        self._audio_read_index = 0
        if self.bridge is None:
            self._write_address(Register.AUDIO_BASE_LO, Register.AUDIO_BASE_HI, self.audio_buffer.physical_address)
            self.regs.write32(Register.AUDIO_CAPACITY, capacity_samples)
            self.regs.write32(Register.AUDIO_READ_INDEX, 0)
        self._apply_configuration()

    def status(self) -> GameboyStatus:
        if self.bridge is not None:
            self._last_status = self.bridge.status()
            return self._last_status
        producer = self.regs.read32(Register.FRAME_PRODUCER)
        self._last_status = GameboyStatus(
            frame_counter=0,
            audio_write_index=self.regs.read32(Register.AUDIO_WRITE_INDEX),
            frame_completed_index=producer & 0x3,
            vblank=False,
            audio_overrun=False,
        )
        return self._last_status

    def latest_frame_view(self) -> memoryview:
        if self.frame_buffer is None:
            raise RuntimeError("frame buffer is not configured")
        index = self.status().frame_completed_index
        return self.frame_buffer.view(index * FRAME_BYTES, FRAME_BYTES)

    def save_persisted_state(self) -> None:
        if self.header is None or self._save_paths is None:
            return
        if self.save_buffer is not None and self.header.ram_size > 0:
            data = bytes(self.save_buffer.view(0, self.header.ram_size))
            self._save_paths.ram.write_bytes(data)
        if self.bridge is not None and (self.header.cart_config & CartConfig.HAS_RTC):
            rtc_state = self.bridge.read_rtc(latched=False)
            self._save_paths.rtc.write_text(f"{rtc_state}\n")

    def _load_persisted_state(self) -> None:
        if self.header is None or self._save_paths is None:
            return
        if self.save_buffer is not None:
            self.save_buffer.zero()
            if self.header.ram_size > 0 and self._save_paths.ram.exists():
                data = self._save_paths.ram.read_bytes()
                self.save_buffer.write(data[: self.header.ram_size])
        if self.bridge is not None and (self.header.cart_config & CartConfig.HAS_RTC) and self._save_paths.rtc.exists():
            rtc_state = int(self._save_paths.rtc.read_text().strip(), 0)
            self.bridge.write_rtc(latched=False, state=rtc_state)

    def _apply_configuration(self) -> None:
        if self.bridge is None:
            return
        if self.header is None or self.rom_buffer is None or self.frame_buffer is None or self.audio_buffer is None:
            return
        save_base = self.save_buffer.physical_address if self.save_buffer is not None else 0
        save_mask = self.header.ram_size - 1 if self.header.ram_size > 0 else 0
        self.bridge.configure(
            rom_base=self.rom_buffer.physical_address,
            rom_mask=self.header.rom_size - 1,
            save_base=save_base,
            save_mask=save_mask,
            frame_bases=(
                self.frame_buffer.physical_address,
                self.frame_buffer.physical_address + FRAME_BYTES,
                self.frame_buffer.physical_address + (FRAME_BYTES * 2),
            ),
            audio_base=self.audio_buffer.physical_address,
            audio_capacity_samples=self.audio_capacity_samples,
            cart_config=self.header.cart_config,
            is_cgb=bool(self._control & Control.CGB),
        )
        self._send_control(clear=True)

    def _send_control(self, *, reset: bool = False, clear: bool = False) -> None:
        assert self.bridge is not None
        self.bridge.control(
            run=bool(self._control & Control.RUN),
            reset=reset,
            clear=clear,
            buttons=self._buttons,
            audio_read_index=self._audio_read_index,
        )

    def _write_address(self, low_register: Register, high_register: Register, address: int) -> None:
        assert self.regs is not None
        self.regs.write32(low_register, address)
        self.regs.write32(high_register, address >> 32)

    def __enter__(self) -> "GameboyDevice":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

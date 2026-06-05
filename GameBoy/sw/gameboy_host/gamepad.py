from __future__ import annotations

from dataclasses import dataclass
import fcntl
from pathlib import Path
import os
import struct

from .registers import Joypad


EV_KEY = 0x01
EV_ABS = 0x03

ABS_X = 0x00
ABS_Y = 0x01
ABS_HAT0X = 0x10
ABS_HAT0Y = 0x11

KEY_POWER = 0x74

BTN_A = 0x130
BTN_B = 0x131
BTN_X = 0x133
BTN_SELECT = 0x13A
BTN_START = 0x13B
BTN_DPAD_UP = 0x220
BTN_DPAD_DOWN = 0x221
BTN_DPAD_LEFT = 0x222
BTN_DPAD_RIGHT = 0x223


KEY_MAP = {
    BTN_A: Joypad.A,
    BTN_B: Joypad.B,
    BTN_X: Joypad.B,
    BTN_SELECT: Joypad.SELECT,
    BTN_START: Joypad.START,
    BTN_DPAD_RIGHT: Joypad.RIGHT,
    BTN_DPAD_LEFT: Joypad.LEFT,
    BTN_DPAD_UP: Joypad.UP,
    BTN_DPAD_DOWN: Joypad.DOWN,
}


@dataclass(frozen=True)
class GamepadState:
    buttons: int = 0


@dataclass(frozen=True)
class AxisInfo:
    minimum: int
    maximum: int
    flat: int


class LinuxGamepad:
    """Small dependency-free reader for Linux evdev gamepads."""

    _EVENT = struct.Struct("llHHI")
    _ABS_INFO = struct.Struct("iiiiii")
    _AXIS_THRESHOLD = 16_384
    _EVIOCGABS_READ = 2 << 30
    _EVIOCGABS_SIZE = _ABS_INFO.size << 16
    _EVIOCGABS_TYPE = ord("E") << 8
    _EVIOCGBIT_READ = 2 << 30
    _EVIOCGBIT_TYPE = ord("E") << 8

    def __init__(self, path: Path | None = None) -> None:
        self.path = path or self._discover()
        self._fd: int | None = os.open(self.path, os.O_RDONLY | os.O_NONBLOCK)
        self._buttons = 0
        self._axis_buttons = 0
        self._axis_info = {
            ABS_X: self._read_abs_info(ABS_X),
            ABS_Y: self._read_abs_info(ABS_Y),
        }

    @classmethod
    def _discover(cls) -> Path:
        candidates = sorted(Path("/dev/input/by-id").glob("*-event-joystick"))
        candidates.extend(sorted(Path("/dev/input").glob("event*")))
        for candidate in candidates:
            try:
                fd = os.open(candidate, os.O_RDONLY | os.O_NONBLOCK)
            except OSError:
                continue
            try:
                if cls._device_has_gamepad_controls(fd):
                    return candidate
            finally:
                os.close(fd)
        raise FileNotFoundError("no readable Linux gamepad event device found")

    @classmethod
    def _device_has_gamepad_controls(cls, fd: int) -> bool:
        key_codes = cls._read_event_bits(fd, EV_KEY, max(KEY_MAP) + 1)
        abs_codes = cls._read_event_bits(fd, EV_ABS, max(ABS_HAT0Y, ABS_Y) + 1)
        return cls._has_gamepad_controls_from_sets(key_codes, abs_codes)

    @staticmethod
    def _has_gamepad_controls_from_sets(key_codes: set[int], abs_codes: set[int]) -> bool:
        if key_codes.intersection(KEY_MAP):
            return True
        return bool(abs_codes.intersection({ABS_X, ABS_Y, ABS_HAT0X, ABS_HAT0Y}))

    @classmethod
    def _read_event_bits(cls, fd: int, event_type: int, bit_count: int) -> set[int]:
        size = (bit_count + 7) // 8
        request = cls._EVIOCGBIT_READ | (size << 16) | cls._EVIOCGBIT_TYPE | (0x20 + event_type)
        buf = bytearray(size)
        try:
            fcntl.ioctl(fd, request, buf, True)
        except OSError:
            return set()
        codes: set[int] = set()
        for byte_index, value in enumerate(buf):
            while value:
                bit = (value & -value).bit_length() - 1
                code = byte_index * 8 + bit
                if code < bit_count:
                    codes.add(code)
                value &= value - 1
        return codes

    def close(self) -> None:
        if self._fd is not None:
            os.close(self._fd)
            self._fd = None

    def poll(self) -> GamepadState:
        while self._fd is not None:
            try:
                data = os.read(self._fd, self._EVENT.size * 32)
            except BlockingIOError:
                break
            if not data:
                break
            for offset in range(0, len(data) - (len(data) % self._EVENT.size), self._EVENT.size):
                _sec, _usec, event_type, code, value = self._EVENT.unpack_from(data, offset)
                if event_type == EV_KEY:
                    self._handle_key(code, value)
                elif event_type == EV_ABS:
                    self._handle_abs(code, value)
        return GamepadState(self._buttons | self._axis_buttons)

    def _handle_key(self, code: int, value: int) -> None:
        bit = KEY_MAP.get(code)
        if bit is None:
            return
        if value:
            self._buttons |= int(bit)
        else:
            self._buttons &= ~int(bit)

    def _handle_abs(self, code: int, value: int) -> None:
        if code in (ABS_X, ABS_HAT0X):
            self._axis_buttons &= ~(Joypad.LEFT | Joypad.RIGHT)
            direction = self._hat_direction(value) if code == ABS_HAT0X else self._axis_direction(code, value)
            if direction < 0:
                self._axis_buttons |= Joypad.LEFT
            elif direction > 0:
                self._axis_buttons |= Joypad.RIGHT
        elif code in (ABS_Y, ABS_HAT0Y):
            self._axis_buttons &= ~(Joypad.UP | Joypad.DOWN)
            direction = self._hat_direction(value) if code == ABS_HAT0Y else self._axis_direction(code, value)
            if direction < 0:
                self._axis_buttons |= Joypad.UP
            elif direction > 0:
                self._axis_buttons |= Joypad.DOWN

    def _read_abs_info(self, code: int) -> AxisInfo | None:
        if self._fd is None:
            return None
        request = self._EVIOCGABS_READ | self._EVIOCGABS_SIZE | self._EVIOCGABS_TYPE | (0x40 + code)
        buf = bytearray(self._ABS_INFO.size)
        try:
            fcntl.ioctl(self._fd, request, buf, True)
        except OSError:
            return None
        _value, minimum, maximum, _fuzz, flat, _resolution = self._ABS_INFO.unpack(buf)
        if maximum <= minimum:
            return None
        return AxisInfo(minimum=minimum, maximum=maximum, flat=max(0, flat))

    def _axis_direction(self, code: int, value: int) -> int:
        info = self._axis_info.get(code)
        if info is None:
            if value < -self._AXIS_THRESHOLD:
                return -1
            if value > self._AXIS_THRESHOLD:
                return 1
            return 0

        center = (info.minimum + info.maximum) / 2
        threshold = max(info.flat, int((info.maximum - info.minimum) * 0.25))
        if value < center - threshold:
            return -1
        if value > center + threshold:
            return 1
        return 0

    @staticmethod
    def _hat_direction(value: int) -> int:
        if value < 0:
            return -1
        if value > 0:
            return 1
        return 0

    def __enter__(self) -> "LinuxGamepad":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

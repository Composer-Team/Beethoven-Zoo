from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import base64
import os
import subprocess
import threading


@dataclass(frozen=True)
class GameboyStatus:
    frame_counter: int
    audio_write_index: int
    frame_completed_index: int
    vblank: bool
    audio_overrun: bool


class GameboyBridgeError(RuntimeError):
    pass


class GameboyBridge:
    def __init__(self, binary: Path) -> None:
        self.binary = binary
        self._proc = subprocess.Popen(
            [str(binary)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        if self._proc.stdin is None or self._proc.stdout is None:
            raise GameboyBridgeError("failed to open bridge pipes")
        self._lock = threading.Lock()

    @staticmethod
    def discover(explicit: Path | None = None) -> Path:
        if explicit is not None:
            return explicit
        env = os.environ.get("BEETHOVEN_GAMEBOY_BRIDGE")
        root = Path(__file__).resolve().parents[2]
        candidates = [
            Path(env) if env else None,
            root / "sw" / "build" / "gameboy_beethoven_bridge",
            root / "target" / "sw" / "gameboy_beethoven_bridge",
        ]
        for candidate in candidates:
            if candidate is not None and candidate.exists():
                return candidate
        raise FileNotFoundError("could not locate gameboy_beethoven_bridge binary")

    def close(self) -> None:
        if self._proc.poll() is not None:
            return
        try:
            self._exchange("quit")
        finally:
            self._proc.terminate()
            self._proc.wait(timeout=1)


    def alloc_buffer(self, name: str, size: int) -> tuple[int, int]:
        line = self._exchange(f"alloc {name} {size}")
        fields = line.split()
        if len(fields) != 4 or fields[0] != "buffer" or fields[1] != name:
            raise GameboyBridgeError(line)
        return int(fields[2], 0), int(fields[3], 0)

    def buffer_write(self, name: str, offset: int, data: bytes) -> None:
        payload = base64.b64encode(data).decode("ascii")
        self._expect_ok(self._exchange(f"buffer_write {name} {offset} {payload}"))

    def buffer_zero(self, name: str, offset: int, size: int) -> None:
        self._expect_ok(self._exchange(f"buffer_zero {name} {offset} {size}"))

    def buffer_read(self, name: str, offset: int, size: int) -> bytes:
        line = self._exchange(f"buffer_read {name} {offset} {size}")
        prefix = "data "
        if not line.startswith(prefix):
            raise GameboyBridgeError(line)
        return base64.b64decode(line[len(prefix):].encode("ascii"))

    def configure(
        self,
        *,
        rom_base: int,
        rom_mask: int,
        save_base: int,
        save_mask: int,
        frame_bases: tuple[int, int, int],
        audio_base: int,
        audio_capacity_samples: int,
        cart_config: int,
        is_cgb: bool,
    ) -> None:
        self._expect_ok(
            self._exchange(
                "configure "
                f"{rom_base} {rom_mask} {save_base} {save_mask} "
                f"{frame_bases[0]} {frame_bases[1]} {frame_bases[2]} "
                f"{audio_base} {audio_capacity_samples} {cart_config} {int(is_cgb)}"
            )
        )

    def control(self, *, run: bool, reset: bool, clear: bool, buttons: int, audio_read_index: int) -> None:
        self._expect_ok(
            self._exchange(
                f"control {int(run)} {int(reset)} {int(clear)} {buttons & 0xFF} {audio_read_index}"
            )
        )

    def status(self) -> GameboyStatus:
        line = self._exchange("status")
        fields = line.split()
        if len(fields) != 6 or fields[0] != "status":
            raise GameboyBridgeError(f"unexpected status response: {line}")
        return GameboyStatus(
            frame_counter=int(fields[1], 0),
            audio_write_index=int(fields[2], 0),
            frame_completed_index=int(fields[3], 0),
            vblank=bool(int(fields[4], 0)),
            audio_overrun=bool(int(fields[5], 0)),
        )

    def debug_register(self, address: int) -> int:
        line = self._exchange(f"debug {address}")
        fields = line.split()
        if len(fields) != 2 or fields[0] != "debug":
            raise GameboyBridgeError(f"unexpected debug response: {line}")
        return int(fields[1], 0)

    def read_rtc(self, *, latched: bool) -> int:
        line = self._exchange(f"rtc_read {int(latched)}")
        fields = line.split()
        if len(fields) != 2 or fields[0] != "rtc":
            raise GameboyBridgeError(f"unexpected rtc response: {line}")
        return int(fields[1], 0)

    def write_rtc(self, *, latched: bool, state: int) -> None:
        self._expect_ok(self._exchange(f"rtc_write {int(latched)} {state}"))

    def _expect_ok(self, line: str) -> None:
        if line != "ok":
            raise GameboyBridgeError(line)

    def _exchange(self, command: str) -> str:
        with self._lock:
            if self._proc.poll() is not None:
                stderr = ""
                if self._proc.stderr is not None:
                    stderr = self._proc.stderr.read()
                raise GameboyBridgeError(f"bridge exited: {stderr.strip()}")
            assert self._proc.stdin is not None
            assert self._proc.stdout is not None
            self._proc.stdin.write(command + "\n")
            self._proc.stdin.flush()
            line = self._proc.stdout.readline()
            if not line:
                stderr = ""
                if self._proc.stderr is not None:
                    stderr = self._proc.stderr.read()
                raise GameboyBridgeError(f"bridge closed without response: {stderr.strip()}")
            line = line.strip()
            if line.startswith("error "):
                raise GameboyBridgeError(line[6:])
            return line

    def __enter__(self) -> "GameboyBridge":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

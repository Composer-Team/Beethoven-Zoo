from __future__ import annotations

import subprocess
import threading
import time

from .device import GameboyDevice


def audio_ring_chunk_to_aplay_pcm(chunk: memoryview | bytes) -> bytes:
    raw = memoryview(chunk)
    if len(raw) % 4 != 0:
        raise ValueError("audio chunk length must be a multiple of one stereo sample")
    payload = bytearray(len(raw))
    payload[0::4] = raw[2::4]
    payload[1::4] = raw[3::4]
    payload[2::4] = raw[0::4]
    payload[3::4] = raw[1::4]
    return bytes(payload)


class AplayAudioPlayer:
    def __init__(self, device: GameboyDevice, sample_rate_hz: int = 48_000, alsa_device: str | None = None) -> None:
        self.device = device
        self.sample_rate_hz = sample_rate_hz
        self.alsa_device = alsa_device
        self._proc: subprocess.Popen[bytes] | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._error: BaseException | None = None

    def start(self) -> None:
        if self._thread is not None:
            return
        cmd = ["aplay", "-q"]
        if self.alsa_device:
            cmd.extend(["-D", self.alsa_device])
        cmd.extend(["-f", "S16_LE", "-c", "2", "-r", str(self.sample_rate_hz)])
        self._error = None
        self._proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="gameboy-audio", daemon=True)
        self._thread.start()

    def poll(self) -> int | None:
        if self._proc is None:
            return None
        return self._proc.poll()

    def check_healthy(self) -> None:
        if self._error is not None:
            raise RuntimeError("audio playback failed") from self._error
        code = self.poll()
        if code is not None:
            raise RuntimeError(f"aplay exited, code={code}")

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1)
            self._thread = None
        if self._proc is not None:
            if self._proc.stdin is not None:
                try:
                    self._proc.stdin.close()
                except BrokenPipeError:
                    pass
            try:
                self._proc.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait(timeout=1)
            self._proc = None

    def _run(self) -> None:
        if self._proc is None or self._proc.stdin is None or self.device.audio_buffer is None:
            return
        read_index = 0
        while not self._stop.is_set():
            status = self.device.status()
            write_index = status.audio_write_index
            capacity = self.device.audio_capacity_samples
            if capacity <= 0 or write_index == read_index:
                time.sleep(0.005)
                continue
            try:
                if write_index > read_index:
                    self._write_chunk(self.device.audio_buffer.view(read_index * 4, (write_index - read_index) * 4))
                else:
                    self._write_chunk(self.device.audio_buffer.view(read_index * 4, (capacity - read_index) * 4))
                    self._write_chunk(self.device.audio_buffer.view(0, write_index * 4))
            except BrokenPipeError as ex:
                self._error = ex
                return
            read_index = write_index
            self.device.set_audio_read_index(read_index)

    def _write_chunk(self, chunk: memoryview) -> None:
        if self._proc is None or self._proc.stdin is None or not chunk:
            return
        self._proc.stdin.write(audio_ring_chunk_to_aplay_pcm(chunk))
        self._proc.stdin.flush()
        code = self._proc.poll()
        if code is not None:
            raise BrokenPipeError(f"aplay exited, code={code}")

    def __enter__(self) -> "AplayAudioPlayer":
        self.start()
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

import mmap
import os
from pathlib import Path


class Udmabuf:
    def __init__(self, name: str) -> None:
        self.name = name
        self.sysfs = Path("/sys/class/udmabuf") / name
        self.devnode = Path("/dev") / name
        if not self.sysfs.exists():
            raise FileNotFoundError(f"missing udmabuf sysfs entry: {self.sysfs}")
        if not self.devnode.exists():
            raise FileNotFoundError(f"missing udmabuf device node: {self.devnode}")

        self.size = int((self.sysfs / "size").read_text().strip(), 0)
        self.physical_address = int((self.sysfs / "phys_addr").read_text().strip(), 0)
        self._fd = os.open(self.devnode, os.O_RDWR | os.O_SYNC)
        self._map = mmap.mmap(
            self._fd,
            self.size,
            mmap.MAP_SHARED,
            mmap.PROT_READ | mmap.PROT_WRITE,
        )

    def close(self) -> None:
        self._map.close()
        os.close(self._fd)

    def view(self, offset: int = 0, size: int | None = None) -> memoryview:
        if size is None:
            size = self.size - offset
        if offset < 0 or size < 0 or offset + size > self.size:
            raise ValueError("requested buffer view is out of range")
        return memoryview(self._map)[offset:offset + size]

    def write(self, data: bytes, offset: int = 0) -> None:
        if offset + len(data) > self.size:
            raise ValueError("write exceeds buffer size")
        self._map[offset:offset + len(data)] = data

    def read(self, offset: int = 0, size: int | None = None) -> bytes:
        return bytes(self.view(offset, size))

    def zero(self, offset: int = 0, size: int | None = None) -> None:
        if size is None:
            size = self.size - offset
        if offset < 0 or size < 0 or offset + size > self.size:
            raise ValueError("requested buffer zero is out of range")
        self._map[offset:offset + size] = b"\x00" * size

    def __enter__(self) -> "Udmabuf":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


class BridgeBuffer:
    _CHUNK_BYTES = 64 * 1024

    def __init__(self, bridge, name: str, size: int) -> None:
        self.bridge = bridge
        self.name = name
        self.physical_address, self.size = bridge.alloc_buffer(name, size)

    def close(self) -> None:
        return None

    def view(self, offset: int = 0, size: int | None = None) -> memoryview:
        if size is None:
            size = self.size - offset
        return memoryview(self.read(offset, size))

    def read(self, offset: int = 0, size: int | None = None) -> bytes:
        if size is None:
            size = self.size - offset
        if offset < 0 or size < 0 or offset + size > self.size:
            raise ValueError("requested buffer read is out of range")
        chunks: list[bytes] = []
        remaining = size
        cursor = offset
        while remaining > 0:
            n = min(remaining, self._CHUNK_BYTES)
            chunks.append(self.bridge.buffer_read(self.name, cursor, n))
            cursor += n
            remaining -= n
        return b"".join(chunks)

    def write(self, data: bytes, offset: int = 0) -> None:
        if offset < 0 or offset + len(data) > self.size:
            raise ValueError("write exceeds buffer size")
        cursor = offset
        view = memoryview(data)
        while view:
            chunk = view[: self._CHUNK_BYTES]
            self.bridge.buffer_write(self.name, cursor, bytes(chunk))
            cursor += len(chunk)
            view = view[len(chunk):]

    def zero(self, offset: int = 0, size: int | None = None) -> None:
        if size is None:
            size = self.size - offset
        if offset < 0 or size < 0 or offset + size > self.size:
            raise ValueError("requested buffer zero is out of range")
        self.bridge.buffer_zero(self.name, offset, size)

    def __enter__(self) -> "BridgeBuffer":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

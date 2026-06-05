import mmap
import os
import struct


class Mmio:
    def __init__(self, base_address: int, size: int = 0x10000, devmem: str = "/dev/mem") -> None:
        self.base_address = base_address
        self.size = size
        self._page_size = mmap.PAGESIZE
        self._page_base = base_address & ~(self._page_size - 1)
        self._page_offset = base_address - self._page_base
        map_size = self._page_offset + size
        self._fd = os.open(devmem, os.O_RDWR | os.O_SYNC)
        self._map = mmap.mmap(
            self._fd,
            map_size,
            mmap.MAP_SHARED,
            mmap.PROT_READ | mmap.PROT_WRITE,
            offset=self._page_base,
        )

    def close(self) -> None:
        self._map.close()
        os.close(self._fd)

    def read32(self, offset: int) -> int:
        start = self._page_offset + offset
        return struct.unpack_from("<I", self._map, start)[0]

    def write32(self, offset: int, value: int) -> None:
        start = self._page_offset + offset
        struct.pack_into("<I", self._map, start, value & 0xFFFF_FFFF)

    def __enter__(self) -> "Mmio":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

from dataclasses import dataclass

from .registers import CartConfig


class RomLoadError(ValueError):
    pass


@dataclass(frozen=True)
class RomHeader:
    cartridge_type: int
    rom_size: int
    ram_size: int
    cart_config: int


def parse_rom_header(data: bytes) -> RomHeader:
    if len(data) < 0x150:
        raise RomLoadError("ROM is too small to contain a valid Game Boy header")

    emu_configs = {
        0x00: CartConfig.MBC_NONE,
        0x01: CartConfig.MBC1,
        0x02: CartConfig.MBC1 | CartConfig.HAS_RAM,
        0x03: CartConfig.MBC1 | CartConfig.HAS_RAM,
        0x05: CartConfig.MBC2 | CartConfig.HAS_RAM,
        0x06: CartConfig.MBC2 | CartConfig.HAS_RAM,
        0x0F: CartConfig.MBC3 | CartConfig.HAS_RTC,
        0x10: CartConfig.MBC3 | CartConfig.HAS_RAM | CartConfig.HAS_RTC,
        0x11: CartConfig.MBC3,
        0x12: CartConfig.MBC3 | CartConfig.HAS_RAM,
        0x13: CartConfig.MBC3 | CartConfig.HAS_RAM,
        0x19: CartConfig.MBC5,
        0x1A: CartConfig.MBC5 | CartConfig.HAS_RAM,
        0x1B: CartConfig.MBC5 | CartConfig.HAS_RAM,
        0x1C: CartConfig.MBC5 | CartConfig.HAS_RUMBLE,
        0x1D: CartConfig.MBC5 | CartConfig.HAS_RAM | CartConfig.HAS_RUMBLE,
        0x1E: CartConfig.MBC5 | CartConfig.HAS_RAM | CartConfig.HAS_RUMBLE,
    }

    cartridge_type = data[0x147]
    if cartridge_type not in emu_configs:
        raise RomLoadError(f"unsupported cartridge type 0x{cartridge_type:02x}")

    rom_size = 32 * 1024 * (1 << data[0x148])
    ram_size = {
        0: 0,
        2: 8 * 1024,
        3: 32 * 1024,
        4: 128 * 1024,
        5: 64 * 1024,
    }.get(data[0x149])
    if ram_size is None:
        raise RomLoadError(f"unsupported RAM size code 0x{data[0x149]:02x}")
    if cartridge_type in (0x05, 0x06):
        ram_size = 512

    return RomHeader(
        cartridge_type=cartridge_type,
        rom_size=rom_size,
        ram_size=ram_size,
        cart_config=CartConfig.ENABLED | int(emu_configs[cartridge_type]),
    )

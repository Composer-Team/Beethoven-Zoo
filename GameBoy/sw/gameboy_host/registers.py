from enum import IntEnum


class Register(IntEnum):
    CONTROL = 0x0000
    STATUS = 0x0004
    JOYPAD = 0x0008
    ROM_BASE_LO = 0x0010
    ROM_BASE_HI = 0x0014
    ROM_MASK = 0x0018
    SAVE_BASE_LO = 0x0020
    SAVE_BASE_HI = 0x0024
    SAVE_MASK = 0x0028
    CART_CONFIG = 0x0030
    FRAME_BASE_0_LO = 0x0040
    FRAME_BASE_0_HI = 0x0044
    FRAME_BASE_1_LO = 0x0048
    FRAME_BASE_1_HI = 0x004C
    FRAME_BASE_2_LO = 0x0050
    FRAME_BASE_2_HI = 0x0054
    FRAME_PRODUCER = 0x0060
    AUDIO_BASE_LO = 0x0070
    AUDIO_BASE_HI = 0x0074
    AUDIO_CAPACITY = 0x0078
    AUDIO_WRITE_INDEX = 0x007C
    AUDIO_READ_INDEX = 0x0080
    RTC_STATE = 0x0090
    RTC_LATCHED = 0x0094
    STAT_STALLS = 0x0100
    STAT_CLOCKS = 0x0104


class Control:
    RUN = 1 << 0
    RESET = 1 << 1
    CGB = 1 << 2
    IRQ_FRAME = 1 << 3
    IRQ_AUDIO = 1 << 4


class Joypad:
    RIGHT = 1 << 0
    LEFT = 1 << 1
    UP = 1 << 2
    DOWN = 1 << 3
    A = 1 << 4
    B = 1 << 5
    SELECT = 1 << 6
    START = 1 << 7


class CartConfig:
    ENABLED = 1 << 0
    HAS_RAM = 1 << 4
    HAS_RTC = 1 << 5
    HAS_RUMBLE = 1 << 6

    MBC_NONE = 0 << 1
    MBC1 = 1 << 1
    MBC2 = 2 << 1
    MBC3 = 3 << 1
    MBC5 = 4 << 1
    MBC7 = 5 << 1

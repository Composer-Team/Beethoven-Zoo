# PS/PL Interface Contract

This file defines the implemented PS/PL contract.

The PS does not drive the emulator through a raw AXI-lite register block
 directly. The user-facing control surface is the Beethoven command interface
generated from `hw/src/main/scala/gameboy_zu3/GameboyBeethovenCore.scala`.

Internally, the wrapper still contains a small register file for RTC and
counters, but PS software reaches it through Beethoven commands.

## Beethoven Command

The generated host binding exposes a single packed command on
`GameboyZu3System`:

```cpp
GameboyZu3System::gameboy(core_id, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, op)
```

The `op` field selects the operation:

| Opcode | Name | Purpose |
| --- | --- | --- |
| `0` | `Configure` | Set ROM/save/frame/audio buffer addresses, masks, cart config, and CGB mode |
| `1` | `Control` | Set run/reset/clear state, joypad bits, and audio read index |
| `2` | `Status` | Read frame counter, completed frame index, vblank, audio write index, audio overrun |
| `3` | `Rtc` | Read or write MBC3 RTC state |

The Python host uses `gameboy_beethoven_bridge` as a thin process boundary over
libbeethoven. The bridge also owns Beethoven-allocated host/FPGA buffers in
bridge mode and exposes text commands for allocation plus base64 chunked
`buffer_write`, `buffer_read`, and `buffer_zero` operations. This keeps GUI,
audio, ROM loading, input, save, and RTC handling on PS/Linux while the PL core
sees only shared DDR addresses.

## Internal Wrapper Register File

These offsets still exist behind the wrapper and are used internally by the
Beethoven core implementation:

| Offset | Name | Purpose |
| --- | --- | --- |
| `0x0018` | `ROM_MASK` | ROM address mask |
| `0x0028` | `SAVE_MASK` | Save RAM address mask |
| `0x0030` | `CART_CONFIG` | MBC type and feature flags |
| `0x0090` | `RTC_STATE` | Live MBC3 RTC state |
| `0x0094` | `RTC_LATCHED` | Latched MBC3 RTC state |
| `0x0100` | `STAT_STALLS` | Cartridge/memory stall counter |
| `0x0104` | `STAT_CLOCKS` | Emulated clock counter |

## `control(...)`

Active fields:

- `run`: run enable.
- `reset`: one-shot emulator reset pulse.
- `clearBuffers`: one-shot framebuffer/audio pointer clear pulse.
- `buttons`: active-high joypad bits, produced by PS/Linux keyboard and optional evdev gamepad input.
- `audioReadIndex`: PS acknowledgement into the audio ring.

Joypad active-high bits:

- Bit 0: Right.
- Bit 1: Left.
- Bit 2: Up.
- Bit 3: Down.
- Bit 4: A.
- Bit 5: B.
- Bit 6: Select.
- Bit 7: Start.

## Video Buffer

The first target uses triple buffering in shared DDR.

- Resolution: 160x144.
- Pixel format in DDR: RGB555 packed in 16-bit pixels.
- Producer: PL writes a complete frame into the current write buffer.
- Consumer: PS displays the most recent completed frame.
- Synchronization: `status()` reports an incrementing frame counter and the
  completed buffer index after the last pixel of a frame is written.

The GTK application scales the frame in software and merges keyboard state with optional Linux evdev gamepad state before sending joypad bits through `control(...)`.

## Audio Buffer

The first target uses a shared DDR ring buffer.

- Sample rate target: 48 kHz.
- Format in shared DDR: packed 32-bit words with right sample in bits `[15:0]`
  and left sample in bits `[31:16]`.
- The host audio path converts that to standard little-endian stereo PCM before
  handing it to Linux audio (`aplay` in the current prototype).
- Producer: PL writes samples and advances the audio write index reported by
  `status()`.
- Consumer: PS reads samples, plays through Linux audio, and sends the updated
  read index back through `control(...)`.

The PL exposes overrun status through `status().audioOverrun`.

## ROM And Save RAM

The PS owns loading and persistence.

- ROM buffer is read-only from the PL point of view.
- Save RAM buffer is read/write from PL and synchronized to disk by PS when the
  game is paused or exits.
- RTC state is exchanged through the `rtc(...)` Beethoven command using the
  same packed 28-bit MBC3 RTC state carried by the integrated core.

## Polling Strategy

Polling is the current host strategy:

- `status().frameCompletedIndex` / `status().frameCounter` for video.
- `status().audioWriteIndex` for audio drain.
- `status().audioOverrun` for diagnostics.

# Goal Definition

## Product Goal

Build a Linux-hosted FPGA Game Boy emulator for the ZU3 platform. The user
interacts with a normal desktop-style GTK application running on the Zynq PS.
The real-time emulator computation runs in PL.

## Target User Flow

1. Boot Linux on the ZU3 board.
2. Launch the Game Boy GTK application.
3. Select a `.gb` or `.gbc` ROM from the filesystem.
4. The application allocates shared buffers, loads the ROM and save data, then
   starts the PL emulator.
5. The GTK window displays frames produced by the PL.
6. Linux keyboard/gamepad input controls the emulated joypad.
7. Audio produced by the PL is played through Linux audio.
8. On pause or exit, save RAM and RTC state are persisted to disk.

## Architecture Goal

The first target is a PS-rendered, PS-audio architecture:

```text
Linux GTK app
  -> ROM/save loading
  -> control/status registers
  -> joypad updates
  -> framebuffer display
  -> audio playback

Beethoven/ZU3 PS-PL bridge
  -> AXI-lite control plane
  -> shared DDR data buffers
  -> optional frame/audio interrupts

PL Game Boy subsystem
  -> Game Boy / Game Boy Color core
  -> emulated cartridge and MBC logic
  -> framebuffer writer
  -> audio ring-buffer writer
```

## Hardware Scope

The PL subsystem should expose only PS-facing interfaces for the first target:

- AXI-lite control/status registers.
- Shared DDR reads for ROM data.
- Shared DDR reads/writes for save RAM.
- Shared DDR writes for video frames.
- Shared DDR writes for audio samples.
- Optional interrupt line for frame-ready or audio-buffer-watermark events.

The first target should not expose or require these PL pins:

- HDMI, LCD, VGA, or other display output.
- I2S, PWM, DAC, or other audio output.
- Physical controller inputs.
- Physical Game Boy cartridge slot.
- Link cable.
- Custom handheld SRAM/SDRAM.

## Compatibility Scope

Phase 1 targets Game Boy and Game Boy Color ROM-file playback using a core
that follows this repo's PS/PL interface. Physical cartridges and GBA are
deferred.

## Definition Of Done For Phase 1

- A simple ROM boots and renders frames in a GTK window.
- Input latency is acceptable for gameplay.
- Audio plays continuously without underruns during normal operation.
- The PL can run for at least 30 minutes without frame-buffer desynchronization,
  audio ring-buffer corruption, or host/control-plane lockup.
- Save RAM is correctly round-tripped for at least one supported game.

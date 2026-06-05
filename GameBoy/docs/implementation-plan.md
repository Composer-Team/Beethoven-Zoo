# Implementation Plan

## Phase 0: Preserve Reference Context

Goal: make the port reproducible and keep reference code close.

Tasks:

- Record the Game Bub source commits inspected for reference.
- Keep Game Bub only as an external reference checkout outside the project, not
  as a submodule or build dependency.
- Define an independent core boundary so implementation can proceed without
  importing upstream code.
- Confirm license implications for distributing derived FPGA and software code.

Exit criteria:

- The reference Game Bub source versions are documented.
- The project builds without Game Bub source code.
- The project has a stable external-core interface to implement against.

## Phase 1: Minimal Core Extraction

Goal: define and elaborate the ZU3 shell around an independent Game Boy core
boundary.

Tasks:

- Implement or connect an independent `ExternalGameboyCore` provider.
- Keep the emulated cartridge behavior compatible with the host register
  contract.
- Drop physical cartridge, link port, PMOD, LCD, HDMI, I2S, custom SRAM, and
  custom SDRAM dependencies.
- Preserve these core outputs:
  - framebuffer x/y/write-enable/pixel
  - vblank
  - left/right audio samples
  - stats and debug signals
- Preserve these inputs:
  - run/reset/mode
  - joypad state
  - ROM/save/RTC configuration

Exit criteria:

- The shell wrapper elaborates to SystemVerilog without any Game Bub code.
- Simulation can later show that framebuffer write events and audio samples are
  produced for a known ROM once the independent core is connected.

## Phase 2: Beethoven/ZU3 Shell

Goal: connect the extracted core to Beethoven's `aupzu3` platform.

Tasks:

- Create a Beethoven accelerator/system wrapper for the Game Boy subsystem.
- Use AXI-lite or Beethoven front-bus-compatible registers for control/status.
- Use shared DDR-backed buffers for ROM, save RAM, framebuffer, and audio.
- Decide whether the PL memory path uses Beethoven memory channels directly or a
  custom AXI master inside a ZU3 platform wrapper.
- Add address-width handling for ZU3 shared DDR addresses.

Exit criteria:

- A synthesis-mode build can generate a ZU3 Vivado project/bitstream candidate.
- The PS can read/write control registers.
- The PL can read a test ROM buffer from shared DDR.

## Phase 3: Video To DDR

Goal: move from PL display output to PS-consumed framebuffers.

Tasks:

- Convert per-pixel core output into linear framebuffer writes.
- Implement triple buffering in shared DDR.
- Publish completed frame index and monotonically increasing frame counter.
- Add overrun/skipped-frame counters.
- Initially poll from PS; add interrupts later if needed.

Exit criteria:

- The PS can read stable 160x144 frames from DDR.
- A test GTK window can display live frames without tearing.

## Phase 4: Audio To DDR

Goal: make PL audio available to Linux audio.

Tasks:

- Convert core audio outputs into signed stereo PCM samples.
- Pick an exact sample cadence.
- Implement a shared DDR audio ring buffer.
- Expose write/read indexes and overrun status.
- Build a PS audio loop using ALSA, PulseAudio, or PipeWire.

Exit criteria:

- Audio plays continuously while the emulator is running.
- Audio underruns/overruns are observable and tunable.

## Phase 5: GTK Host Application

Goal: provide the user-facing emulator app.

Tasks:

- Build a GTK UI for ROM selection, pause/resume, reset, and basic settings.
- Use Linux keyboard and gamepad input.
- Allocate shared buffers and pass physical/device addresses to PL.
- Load ROM and save RAM.
- Drain audio ring buffer.
- Render frames using GTK/Cairo, GDK textures, or OpenGL depending on
  performance.
- Persist save RAM and RTC state.

Exit criteria:

- A ROM can be selected and played from the GTK app.
- Input, video, audio, pause/resume, reset, and save persistence work.

## Phase 6: Robustness And Tuning

Goal: make the emulator usable beyond a demo.

Tasks:

- Measure frame timing, audio latency, host CPU load, and PL memory stalls.
- Add interrupts if polling is too expensive.
- Tune buffer sizes.
- Add diagnostics UI or logs.
- Run long-duration tests.
- Validate multiple MBC types.

Exit criteria:

- 30-minute run without lockups or buffer corruption.
- Acceptable input latency.
- Stable audio.
- Save files survive repeated runs.

## Deferred Work

- Physical cartridges.
- Link cable.
- GBA support.
- Shader/scaler options.
- Save states.
- Packaging as a system service or desktop app.
- Upstreaming framework support if Beethoven needs new platform primitives.

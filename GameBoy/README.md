# Beethoven Game Boy Emulator

![cgb-acid2 running through the Beethoven Game Boy emulator on AUP-ZU3](docs/images/cgb-acid2-zu3.png)

This project implements a Beethoven-powered Game Boy/Game Boy Color emulator
prototype for the AUP-ZU3 platform.

## Goal

Run Game Boy and Game Boy Color ROM files on the ZU3 board with a clean PS/PL
split:

- The PL runs the emulator core and cartridge logic.
- The PS runs Linux, owns the GUI, handles input, loads ROM/save files, and
  outputs video/audio through normal Linux facilities.
- The PL does not drive display, audio, controller, cartridge-slot, link-port,
  or other board-level peripheral pins for the first target.

## First Success Criteria

- A Game Boy ROM can be selected from the Linux filesystem.
- The PS loads the ROM into shared DDR and configures the PL emulator.
- The PL runs the Game Boy core continuously from the loaded ROM.
- The PS receives completed 160x144 RGB frames from PL-visible shared memory
  and displays them in a GTK window.
- The PS receives stereo audio samples from a PL-written ring buffer and plays
  them through Linux audio.
- Keyboard/gamepad input in Linux updates the emulated joypad state.
- Save RAM is persisted to disk when the game is paused or exited.

## Non-Goals For The First Target

- Physical Game Boy cartridges.
- Game Boy link cable.
- PL-side HDMI/LCD/VGA output.
- PL-side I2S/audio codec output.
- GBA support.
- Full Beethoven framework changes unless the existing ZU3 platform surface is
  insufficient.

## Source Acknowledgement

The Game Boy/Game Boy Color core in `hw/src/main/scala/gameboy/**` is derived
from the GPLv3 Game Bub project and adapted locally for this Beethoven/ZU3
example. Game Bub is acknowledged as the upstream reference, but it is not
vendored here and is not a build-time dependency.

- Upstream: `https://github.com/elipsitz/gamebub`
- Zynq reference tag inspected during development: `pynq-release`

## ROM policy

User ROM files are intentionally not committed. The example accepts user-supplied `.gbc` files at run time, and the
repository `.gitignore` excludes ROMs, save files, RTC persistence files, and
board-local ROM staging
directories. The included screenshot uses the public `cgb-acid2` test ROM.

## Project Layout

- `Beethoven.toml`: Beethoven project manifest.
- `build.sbt`: local Chisel build for wrapper elaboration.
- `docs/goal.md`: detailed target definition and constraints.
- `docs/implementation-plan.md`: phased implementation plan.
- `docs/interfaces.md`: PS/PL interface contract.
- `docs/beethoven-interfaces.md`: tables of every Beethoven interface used by
  the example and how each one simplifies PS/PL development.
- `docs/board-bringup.md`: privileged AUP-ZU3 programming and validation
  checklist.
- `scripts/`: validation helpers, including `host_smoke.py`, `sim_smoke.py`, `board_smoke.py`, `board_gui_smoke.py`, and `board_cart_persistence_smoke.py`.
- `hw/`: Chisel/Verilog wrapper code and the vendored/adapted Game Boy core.
- `sw/`: GTK/audio/input Linux host application code plus the Beethoven bridge.

## Current Implementation State

- `hw/src/main/scala/gameboy/**` now contains the integrated Game Boy/Game Boy
  Color core, APU, PPU, cartridge logic, and supporting utilities.
- `hw/src/main/scala/gameboy_zu3/ExternalGameboyCore.scala` wraps that core
  behind the project's ROM/save/frame/audio contract.
- `hw/src/main/scala/gameboy_zu3/GameboyBeethovenCore.scala` adds the
  Beethoven accelerator wrapper, command interface, and memory-channel bridges.
- `sw/gameboy_host` now contains the Python GTK/audio/input host path, including
  keyboard input and optional calibrated Linux evdev gamepad polling. In bridge
  mode it allocates ROM/save/frame/audio buffers through the C++
  `gameboy_beethoven_bridge`, so the Python host no longer requires
  `udmabuf` for simulation or Beethoven-backed board runs.
- `target/synthesis/implementation/design_1_wrapper.bit` and the matching
  `.hwh` are generated AUP-ZU3 board artifacts. They are not committed to the
  Zoo repository; regenerate them with Beethoven/Vivado before board bring-up.

Verified locally:

```bash
sbt compile
sbt "runMain beethoven.cli.Run --mode simulation --manifest Beethoven.toml"
cmake --build sw/build -j4
python3 -m py_compile sw/gameboy_host/*.py
cargo run --manifest-path ../Beethoven-Software/cli/Cargo.toml -- build runtime --simulation
python3 scripts/host_smoke.py
python3 scripts/sim_smoke.py
python3 -m py_compile sw/gameboy_host/*.py scripts/*.py
```

Host-only checks also cover RGB555-to-RGB888 conversion, PL audio-ring-to-Linux PCM packing, Linux evdev gamepad decoding, and direct-MMIO 64-bit buffer address writes through `scripts/host_smoke.py`.

Live simulation path also verified:

- `sw/build/gameboy_status_tb`
- `sw/build/gameboy_runtime_tb`
- `sw/build/gameboy_beethoven_bridge_sim`
- `scripts/sim_smoke.py`, which launches the simulation runtime, verifies
  bridge buffer read/write, loads synthetic ROM-only and MBC3 RTC test images,
  configures the PL core, resets/runs it, updates joypad state, reads status,
  reads framebuffer/audio bytes, and checks save/RTC persistence files.

These work against a running `target/simulation/runtime/BeethovenRuntime`. `scripts/board_smoke.py` is the matching non-interactive AUP-ZU3 board smoke once the PL is programmed and the board runtime can access `/dev/mem`.

Board evidence now captured:

- the generated `design_1_wrapper.bit` has been loaded through Linux FPGA manager on `ssh zu3`;
- `scripts/board_smoke.py --start-runtime --startup-timeout 20` passes against the programmed PL and AArch64 `BeethovenRuntime`;
- an MIT-licensed real CGB ROM (`cgb-acid2.gbc`) loads from the Linux filesystem and starts through the PS host/bridge path;
- the GTK host starts against X `:0` with that ROM in ALSA-null mode, captures a 160x144 screenshot, and the captured PNG is an exact pixel match for the upstream `cgb-acid2` reference image;
- `scripts/board_cart_persistence_smoke.py` runs a generated MBC3+RAM+RTC cartridge program on the PL core, verifies CPU-driven external RAM writes reach the shared save buffer, and verifies persisted `.sav`/`.rtc` files.

Known limitations / deferred hardware validation:

- physical Linux audio playback on the board PS is intentionally deferred. The board exposes ZynqMP DP ALSA nodes after binding the DP codec/card, but sustained playback currently returns `aplay: pcm_write:2127: write error: Input/output error`; use `--no-audio` for interactive runs or `--alsa-null` for automated GUI smoke until DP/audio hardware is fixed.
- physical keyboard/gamepad hardware validation still needs an attached input device. The current board only exposes `gpio-keys`/`KEY_POWER`; the automated GUI smoke verifies GTK key-handler mapping through the XTEST/X11 path and reports `gtk_key_smoke=xtest_fake_key`, and gamepad auto-discovery now rejects the GPIO power key as a false controller.

See `docs/board-bringup.md` for the exact board-side setup, runtime commands,
current board evidence, and deferred hardware validation notes.

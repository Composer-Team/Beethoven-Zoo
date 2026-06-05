# AUP-ZU3 Board Bring-Up

This checklist is for finishing the hardware validation that cannot be run from
an unprivileged SSH account. It assumes the local workstation has already
generated the AUP-ZU3 implementation artifacts:

- `target/synthesis/implementation/design_1_wrapper.bit`
- `target/synthesis/implementation/design_1_wrapper.hwh`
- `target/binding/beethoven_hardware.cc`
- `target/binding/beethoven_hardware.h`

Before moving to privileged board access, refresh the local simulation smoke:

```bash
cd GameBoy
python3 scripts/host_smoke.py
python3 scripts/sim_smoke.py
```

Those commands do not replace board evidence, but they verify the current host
RGB/audio-packing/gamepad helpers, direct-MMIO 64-bit buffer address writes,
bridge-buffer host path, synthetic ROM load/configure/run flow, framebuffer and
audio buffer reads, joypad update, and save/RTC persistence plumbing.

## Access Requirements

The board user must be able to do all of the following:

- program the PL, either through `fpgautil`, `xmutil`, PYNQ, or Linux FPGA
  manager firmware writes;
- access the Beethoven runtime's low-level device nodes, currently expected to
  include `/dev/mem` and the generated UIO devices;
- start and stop the per-project `BeethovenRuntime` daemon;
- run the Python host with access to display, input, and Linux audio.

The current `ssh zu3` account is `mason`. It is in the `sudo` group and can
perform privileged board validation when the correct sudo password is supplied.
Root SSH is still not available, and the board does not provide `pynq`,
`fpgautil`, or `xmutil`; use Linux FPGA manager writes for programming.

## Build Artifacts For The Board

After copying the checkout to the board, or from an AArch64 cross-build
environment, build the release runtime and host bridge from the `GameBoy/`
directory:

```bash
cargo run --manifest-path ../Beethoven-Software/cli/Cargo.toml -- build runtime --release
cmake -S sw -B sw/build -DCMAKE_BUILD_TYPE=Release
cmake --build sw/build -j4
python3 -m py_compile sw/gameboy_host/*.py scripts/*.py
```

A workstation `x86-64` build of `target/synthesis/runtime/BeethovenRuntime` is
useful as a compile check, but it will not run on the AArch64 ZU3 PS. Build this
binary on the board or cross-compile it for AArch64 before the runtime probe.
The current board staging directory is `/home/mason/beethoven-gameboy-zu3`, and
the AArch64 runtime, bridge, and Python host syntax checks have been built there.

The expected board-side executables are:

- `target/synthesis/runtime/BeethovenRuntime`
- `sw/build/gameboy_beethoven_bridge`

If the release runtime is built directly on the board, make sure
`libbeethoven-zynq` is installed or discoverable through the Beethoven CLI
configuration.

The Zynq libbeethoven allocator uses huge pages for bridge-owned host buffers.
Before board smoke or normal bridge-mode host runs, provision the page classes
used by the default ROM/frame/audio buffers unless the image does this
persistently at boot:

```bash
echo 32 | sudo tee /sys/kernel/mm/hugepages/hugepages-2048kB/nr_hugepages
echo 4 | sudo tee /sys/kernel/mm/hugepages/hugepages-32768kB/nr_hugepages
```

If the runtime is launched before the account has the required device access,
the previously observed `scripts/board_smoke.py --start-runtime` failure was:

```text
runtime exited before bridge became ready (code=13); stderr='Error opening /dev/mem. Errno: Permission denied'
```

If the bridge is run without a live runtime, the current observed
`scripts/board_smoke.py` failure is:

```text
bridge did not become ready; stdout='' stderr='Error opening file /compo_c_1001 No such file or directory'
```

## Program The PL

Use the loader available for the board connection. `beethoven flash` can program
the generated bitstream over Vivado/JTAG when run on the machine connected to
the board's JTAG cable and able to reach Xilinx `hw_server` at `localhost:3121`:

```bash
# Vivado/JTAG path from the GameBoy project root
beethoven flash

# Equivalent when running the CLI from source
cargo run --manifest-path ../../Beethoven-Software/cli/Cargo.toml -- flash
```

This uses `target/synthesis/implementation/jtag_program.tcl` and the bitstream
under `target/synthesis/implementation/xilinx_work/beethoven.runs/impl_1/`. It
does not use Linux FPGA manager and does not write nonvolatile boot flash.

Observed GBC emulator flash test from the Zoo checkout:

```text
cd Beethoven-Zoo/GameBoy
cargo run --manifest-path ../../Beethoven-Software/cli/Cargo.toml -- flash
...
INFO: [Labtoolstcl 44-466] Opening hw_target localhost:3121/xilinx_tcf/Xilinx/880225000189A
INFO: [Labtools 27-3164] End of startup status: HIGH
=== post-program done state ===
  device : xczu3_0
✓ flash complete.
```

Other board-side loaders remain valid. Examples:

```bash
# fpgautil-style systems
sudo fpgautil -b target/synthesis/implementation/design_1_wrapper.bit
```

```bash
# PYNQ-style systems with the pynq Python package installed
python3 - <<'PY'
from pynq import Overlay
Overlay("target/synthesis/implementation/design_1_wrapper.bit")
PY
```

```bash
# Linux FPGA manager systems. Some kernels require a .bin converted from the
# .bit instead of the raw .bit file.
sudo cp target/synthesis/implementation/design_1_wrapper.bit /lib/firmware/
echo design_1_wrapper.bit | sudo tee /sys/class/fpga_manager/fpga0/firmware
```

After programming, confirm that the expected UIO nodes and `/dev/mem` are
accessible to the account that will run the runtime and bridge.

## Start Runtime And Probe

In terminal A on the board:

```bash
cd GameBoy
BEETHOVEN_PROJECT_ROOT="$PWD" \
  target/synthesis/runtime/BeethovenRuntime
```

In terminal B, verify the bridge can talk to the programmed PL:

```bash
cd GameBoy
printf 'status\nquit\n' | sw/build/gameboy_beethoven_bridge
```

Expected result: the bridge prints a `status ...` line followed by `ok` and
does not hang or crash.

## Board Smoke

For the repeatable non-interactive board smoke, run this after the PL is
programmed and the runtime can access `/dev/mem`:

```bash
PYTHONPATH=sw python3 scripts/board_smoke.py --start-runtime
```

If a privileged terminal is already running `BeethovenRuntime`, omit
`--start-runtime` and run the same script as the bridge/host user:

```bash
PYTHONPATH=sw python3 scripts/board_smoke.py
```

The board smoke generates synthetic ROM-only and MBC3 RTC images under
`target/board-smoke/`, verifies bridge-owned buffer read/write, configures and
runs the PL core, reads framebuffer/audio bytes, updates joypad state, and
checks host-side save/RTC persistence plumbing.

For the non-interactive GUI/reference-image smoke, run:

```bash
DISPLAY=:0 XDG_RUNTIME_DIR=/run/user/1001 PYTHONPATH=sw \
  python3 scripts/board_gui_smoke.py --start-runtime \
  --rom target/roms/cgb-acid2.gbc --alsa-null --settle-seconds 5
```

This captures `target/board-gui-smoke/board_gui_smoke.png`. The current board
capture has been compared against upstream `mattcurrie/cgb-acid2`
`img/reference.png` and matched exactly. `--alsa-null` is only an automated
software-path check; it is not physical audio playback evidence.

For cartridge-driven RAM/RTC persistence, run:

```bash
PYTHONPATH=sw python3 scripts/board_cart_persistence_smoke.py --start-runtime
```

That script generates an MBC3+RAM+RTC ROM, runs it on the PL core, verifies CPU
writes into external RAM, and checks the resulting `.sav` and `.rtc` files.
It is still not a substitute for the physical audio/input evidence listed below.

## Run The Host

For a non-interactive ROM/configuration smoke:

```bash
PYTHONPATH=sw python3 -m gameboy_host \
  --bridge-bin sw/build/gameboy_beethoven_bridge \
  --rom /path/to/test.gb \
  --run \
  --no-audio
```

For interactive validation without the deferred audio path:

```bash
PYTHONPATH=sw python3 -m gameboy_host \
  --bridge-bin sw/build/gameboy_beethoven_bridge \
  --rom /path/to/test.gbc \
  --run \
  --gtk \
  --no-audio \
  --gamepad
```

The Python host keeps GUI rendering, keyboard/gamepad input, ROM loading,
audio playback, save RAM persistence, and RTC persistence on PS/Linux. Add
`--gamepad-device /dev/input/eventN` if auto-discovery chooses the wrong
controller. The PL sees only Beethoven commands and shared DDR buffers.

## Current Completion Evidence

Direct evidence captured for each non-deferred item:

- PL programmed with the generated `design_1_wrapper.bit`. Current evidence: FPGA manager reported `/sys/class/fpga_manager/fpga0/state=operating` after writing `design_1_wrapper.bit`.
- `target/synthesis/runtime/BeethovenRuntime` running on the board. Current evidence: `scripts/board_smoke.py --start-runtime --startup-timeout 20` reached the bridge and passed.
- `gameboy_beethoven_bridge` returns `status` against the programmed PL. Current evidence: board host probes returned `runtime_ready status ...;ok`.
- A real `.gb` or `.gbc` ROM loads from the Linux filesystem. Current evidence: MIT-licensed `cgb-acid2.gbc` from `mattcurrie/cgb-acid2` v1.1 loaded as `cart=0x00 rom=32768 ram=0`.
- GTK displays frames produced by the PL. Current evidence: `board_gui_smoke.py --rom target/roms/cgb-acid2.gbc --alsa-null --settle-seconds 5` captured `target/board-gui-smoke/board_gui_smoke.png`, and local comparison against upstream `cgb-acid2/img/reference.png` found `0` differing pixels.
- Linux audio plays samples drained from the shared audio ring. Known limitation/deferred: after binding the ZynqMP DP codec/card, ALSA exposes `card0` (`DP mon`) and short writes can open the PCM, but sustained playback currently fails with `aplay: pcm_write:2127: write error: Input/output error`. Use `--no-audio` or `--alsa-null` until DP/audio hardware is fixed.
- Keyboard or gamepad input changes the emulated joypad state during gameplay. Current automated evidence: `board_gui_smoke.py` verifies the GTK `z` -> `Joypad.A` path through XTEST and reports `gtk_key_smoke=xtest_fake_key`. Physical-device validation remains hardware-limited because the current board exposes only `gpio-keys`/`KEY_POWER`, not a full keyboard/gamepad; gamepad auto-discovery rejects that GPIO power key instead of treating it as a controller.
- Save RAM is written to disk on exit and restored on relaunch. Current evidence: `board_cart_persistence_smoke.py` ran a generated MBC3+RAM+RTC ROM on PL, observed `ram_prefix=4299...`, and persisted the cartridge-written bytes to `.sav`.
- MBC3 RTC state is persisted and restored for an RTC-capable ROM. Current evidence: `board_cart_persistence_smoke.py` observed nonzero cartridge-written RTC state and persisted it to `.rtc`.

Local simulation, bitstream generation, bridge-only status checks, and GTK
process liveness alone are not sufficient evidence; the current completion
evidence also includes live board smoke, reference-image framebuffer comparison,
XTEST keyboard-path validation, and cartridge-driven save/RTC persistence.

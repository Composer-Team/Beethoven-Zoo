# A3 Beethoven-Zoo Demo

This project ports the legacy Composer A3 attention example into the current Beethoven-Zoo layout and targets the AUP-ZU3 board by default.

## What changed from the original A3 example

The original A3 example lived under the legacy Composer examples tree and was built with the old Beethoven build shim. This Zoo version keeps the same checked A3 workload and golden data, but updates the project around the current Beethoven toolchain.

### Project layout

- Added a Zoo manifest in `Beethoven.toml`.
- Added an sbt project in `build.sbt` using Scala 2.13.18 and Chisel 7.5.0.
- Depends on the published `edu.duke.cs.apex %% beethoven-hardware` artifact by default, plus a pinned `fixedpoint` Git source dependency for the A3 fixed-point math library.
- Added a standard Zoo software build in `sw/CMakeLists.txt`.
- Moved the A3 testbench to `sw/a3_tb.cc`.
- Preserved the original n=320, d=64, q=16 golden data in `sw/A3-n320d64q16.h`.

### Current Beethoven hardware API

The hardware sources under `hw/src/main/scala/design/A3` were updated from the legacy API to current Beethoven/Chisel conventions:

- Uses `AcceleratorCore`, `BeethovenIO`, `AccelCommand`, and current reader/writer/scratchpad accessors.
- Uses `org.chipsalliance.cde.config.Parameters`.
- Uses the current Chisel 7 `chisel3` package.
- Uses upstream `fixedpoint` plus `fixedpoint.shadow` mux helpers.
- Generates C++ binding constants for the software testbench with `CppGeneration.addUserCppDefinition`.

### LUT generation

The original exponent LUT construction generated very large Chisel `VecInit` tables. With current Chisel/FIRRTL this produced packed SystemVerilog assignment patterns that Icarus could not elaborate for simulation.

This port replaces those large generated `VecInit` tables with inline Verilog case-ROM black boxes in `A3LUT.scala`. That keeps the hardware behavior but avoids simulator-incompatible packed array assignment patterns.

### Command contract

The software sends one query per `A3::attention(...)` command. Each command provides:

- query address
- output address
- key matrix address
- value matrix address
- `refresh_spads`

The first checked pass refreshes key/value scratchpads before each query. The second checked pass reuses the scratchpad contents. Both paths are verified against the 1024-word golden output.

### AUP-ZU3 target

The demo targets AUP-ZU3 by default:

```toml
[platform]
target = "aupzu3"
simulator = "icarus"

[platform.aupzu3]
dram-size-gb = 4
memory-channels = 1
clock-rate-mhz = 100
```

AUP-ZU3 has a narrower memory path than the original/default simulation platform. A3's key/value scratchpad rows are wide, so current Beethoven-Hardware needed fixes for this target.

## Beethoven-Hardware fixes required by A3 on ZU3

The A3 port required changes in Beethoven-Hardware, not only in this example.

### Wide flat-packed scratchpad loads

Files:

- `Beethoven-Hardware/src/main/scala/beethoven/MemoryStreams/Loaders/CScratchpadPackedSubwordLoader.scala`
- `Beethoven-Hardware/src/main/scala/beethoven/MemoryStreams/MemoryScratchpad.scala`

Problem:

- A3 flat-packs scratchpad entries wider than one AUP-ZU3 memory beat.
- The previous packed-subword loader assumed a scratchpad entry fit within one beat or that one beat contained multiple scratchpad entries.
- On ZU3 this produced invalid empty-vector cases during elaboration and could not correctly initialize the scratchpads.

Fix:

- Detect when a flat-packed scratchpad entry is wider than the memory beat.
- Require an integer, power-of-two number of beats per scratchpad entry.
- Assemble a wide scratchpad row from multiple returned memory beats.
- Scale scratchpad init indexing by `beatsPerScratchpadEntry`.
- Route scratchpads with rows wider than the platform beat through the low-resource reader path.

### Lightweight reader multi-beat response handling

File:

- `Beethoven-Hardware/src/main/scala/beethoven/MemoryStreams/Readers/LightweightReader.scala`

Problem:

- The low-resource reader buffered returned beats but did not clear `storageFilled` when downstream consumed the assembled data.
- It also did not track all beats in an outstanding TileLink transaction precisely enough for the multi-beat wide-row path.
- AUP-ZU3 simulation and hardware could hang after commands were accepted because the reader path stopped making progress.

Fix:

- Clear `storageFilled` on `io.channel.data.fire`.
- Track returned transaction beats with `txBeatsLeft`.
- Keep `sourceBusy` asserted until the final beat of the outstanding request returns.

### Scratchpad refresh wait in A3Core

File:

- `hw/src/main/scala/design/A3/A3Core.scala`

Problem:

- Scratchpad init request acceptance does not by itself expose a completion signal to the accelerator core.
- Starting query compute immediately after accepting the refresh requests could read stale or partially refreshed key/value scratchpad data on the low-resource ZU3 path.

Fix:

- Added `sWaitRefresh` after refresh request emission.
- Waits a conservative `keyValueLenBytes / 16 + 2048` cycles before accepting query data when `refresh_spads` is set.
- Non-refresh commands skip this wait and proceed directly to query read.

A future Beethoven-Hardware improvement should expose an explicit scratchpad-init-complete handshake so examples do not need a fixed wait.

## Build and run

### Simulation

From this directory:

```sh
beethoven check
beethoven build hw --simulation
beethoven build sw
beethoven sim a3_tb
```

Expected output:

```text
[PASS] refresh=true matched 1024 output words.
[PASS] refresh=false matched 1024 output words.
```

### AUP-ZU3 synthesis and flash

Source Vivado first:

```sh
source /opt/Xilinx/2025.1/Vivado/settings64.sh
beethoven synth
beethoven flash
```

The board runtime test uses the generated bindings and the `sw/a3_tb.cc` testbench. On the tested board, hugepages had to be configured after reboot before running the FPGA client:

```sh
echo 32 > /sys/kernel/mm/hugepages/hugepages-2048kB/nr_hugepages
echo 4  > /sys/kernel/mm/hugepages/hugepages-32768kB/nr_hugepages
```

## Verification performed

This port was verified with:

- `beethoven check`
- AUP-ZU3-target simulation with Icarus
- Default-platform simulation sanity check
- Vivado 2025.1 synthesis/implementation for AUP-ZU3
- JTAG flash to `xczu3`
- Real board execution over `ssh zu3`

Both simulation and real hardware produced:

```text
[PASS] refresh=true matched 1024 output words.
[PASS] refresh=false matched 1024 output words.
```

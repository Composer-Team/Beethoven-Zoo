# Beethoven Interfaces Used By The GameBoy Example

This document extracts the Beethoven-facing interfaces in the `GameBoy` example
and shows how they replace a large amount of manual PS/PL integration work.

The design split is:

```text
PS/Linux host code
  -> generated Beethoven C++ binding + libbeethoven runtime
  -> Beethoven command/response and memory-stream fabric
  -> PL Game Boy / Game Boy Color core
  -> shared DDR buffers for ROM, save RAM, video, and audio
```

## Project-level Beethoven configuration

Defined in `Beethoven.toml`.

| Interface | Value in this example | Purpose | What Beethoven provides |
|---|---:|---|---|
| `[project].name` | `gameboy` | Names the Beethoven project. | Consistent generated artifact namespace under `target/`. |
| `[hardware].src-dir` | `hw` | Points Beethoven at the Chisel hardware wrapper. | Hardware elaboration entry point discovery. |
| `[hardware.beethoven-hardware].path` | `../../Beethoven-Hardware` | Locates the Beethoven framework checkout. | Imports framework types such as `AcceleratorCore`, `AccelCommand`, and memory channels. |
| `[software].src-dir` | `sw` | Points at host-side C++/Python software. | Common layout for generated bindings and host builds. |
| `[platform].target` | `aupzu3` | Selects AUP-ZU3 / Zynq UltraScale+ platform. | Platform integration, address sizing, AXI/PS wiring, Vivado collateral. |
| `[platform].simulator` | `verilator` | Selects the simulation backend. | Simulation runtime generation. |
| `[platform.aupzu3].dram-size-gb` | `4` | Board DDR size model. | Address map/platform sizing. |
| `[platform.aupzu3].memory-channels` | `1` | Number of external memory channels. | Generated memory fabric connected to the selected FPGA platform. |
| `[platform.aupzu3].clock-rate-mhz` | `100` | PL clock target. | Platform clock assumptions for generated RTL/scripts. |
| `[build].output-dir` | `target` | Artifact directory. | Binding, simulation, synthesis, runtime, and implementation outputs. |

## Hardware wrapper interfaces

Defined in `hw/src/main/scala/gameboy_zu3/GameboyBeethovenCore.scala`.

| Beethoven type/API | Exact use | Purpose in GameBoy | Development simplification |
|---|---|---|---|
| `AcceleratorCore` | `class GameboyBeethovenCore extends AcceleratorCore` | Makes the GBC subsystem a Beethoven accelerator core. | The wrapper plugs into Beethoven's generated system instead of hand-authoring a full top-level PS/PL shell. |
| `AccelCommand("gameboy")` | `class GameboyCmd extends AccelCommand("gameboy")` | Declares the host-visible command payload. | Produces a typed generated C++ binding rather than manual MMIO register plumbing. |
| `AccelResponse("gameboy_resp")` | `class GameboyResp extends AccelResponse("gameboy_resp")` | Declares the host-visible response payload. | Handles response routing and packing through Beethoven's command path. |
| `BeethovenIO(...)` | `val cmd = BeethovenIO(new GameboyCmd, new GameboyResp)` | Instantiates the command/response endpoint. | Single command/response port replaces many ad-hoc control registers. |
| `AcceleratorConfig` | `class GameboyZu3Config extends AcceleratorConfig(...)` | Declares the complete accelerator system. | Centralizes core count, system name, module constructor, and memory-channel declarations. |
| `AcceleratorSystemConfig` | `name = "GameboyZu3System", nCores = 1` | Names the generated host namespace and selects one core. | Generated host API becomes `GameboyZu3System::gameboy(...)`. |
| `ModuleBuilder` | `ModuleBuilder(p => new GameboyBeethovenCore()(p))` | Supplies the Chisel module constructor to Beethoven. | Keeps project-specific Chisel isolated from platform generation. |
| `Address.addrBits()` | Used for PS/PL physical address-width fields. | Supports board physical addresses wider than 32 bits; current generated binding reports 49 address bits. | Avoids hardcoding unsafe address widths in PL memory-channel bridges. |

## Command/response ABI

Declared in Scala and emitted into the generated C++ binding.

### Command payload

| Field | Width | Producer | Consumer | Meaning |
|---|---:|---|---|---|
| `arg0` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `arg1` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `arg2` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `arg3` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `arg4` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `arg5` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `arg6` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `arg7` | 64 | PS bridge / generated binding | PL wrapper | Operation-specific payload. |
| `op` | 3 | PS bridge / generated binding | PL wrapper | Selects `Configure`, `Control`, `Status`, `Rtc`, or `Debug`. |

### Response payload

| Field | Width | Producer | Consumer | Meaning |
|---|---:|---|---|---|
| `data` | 64 | PL wrapper | PS bridge / generated binding | Operation-specific response value. |

### Generated C++ call

From `target/binding/beethoven_hardware.h`:

```cpp
beethoven::response_handle<beethoven::rocc_response>
GameboyZu3System::gameboy(
  uint16_t core_id,
  uint64_t arg0,
  uint64_t arg1,
  uint64_t arg2,
  uint64_t arg3,
  uint64_t arg4,
  uint64_t arg5,
  uint64_t arg6,
  uint64_t arg7,
  uint8_t op);
```

| Generated item | Value | Used by | Why it matters |
|---|---:|---|---|
| Namespace | `GameboyZu3System` | `sw/gameboy_bridge.cc` | Matches `AcceleratorSystemConfig.name`. |
| Method | `gameboy(...)` | `issue(...)` helper in the C++ bridge | Host code sends typed accelerator commands instead of writing raw command registers. |
| Response handle | `response_handle<rocc_response>` | `.get().data` | Beethoven manages response synchronization. |
| `beethovenNumAddrBits` | `49` | Generated binding / address mask | Confirms PS/PL physical addresses are not limited to 32 bits. |
| `NUM_DDR_CHANNELS` | `1` in simulation | Runtime/sim binding | Matches manifest memory-channel count. |

## GameBoy command operations

| Opcode | Scala name | Bridge text command(s) | Request packing | Response packing | Simplification |
|---:|---|---|---|---|---|
| `0` | `Configure` | `configure` | `arg0=romBase`; `arg1[22:0]=romMask`; `arg1[39:23]=saveMask`; `arg1[40]=isCgb`; `arg1[47:41]=cartConfig`; `arg2=saveBase`; `arg3..5=frameBase[0..2]`; `arg6=audioBase`; `arg7[23:0]=audioCapacitySamples` | `data=1` | One transaction passes all base addresses and cartridge configuration. |
| `1` | `Control` | `control` | `arg0[0]=run`; `arg0[1]=reset`; `arg0[2]=clear`; `arg0[15:8]=buttons`; `arg0[39:16]=audioReadIndex` | `data=1` | One transaction handles run state, reset pulse, buffer clear, joypad state, and audio consumer index. |
| `2` | `Status` | `status` | no payload | `data[31:0]=frameCounter`; `data[55:32]=audioWriteIndex`; `data[57:56]=frameCompletedIndex`; `data[58]=vblank`; `data[59]=audioOverrun` | Host polls one value for video/audio synchronization and diagnostics. |
| `3` | `Rtc` | `rtc_read`, `rtc_write` | `arg0[0]=write`; `arg0[1]=latched`; `arg0[29:2]=rtcState` for writes | `data[27:0]=rtcState` | RTC persistence is exposed as a small command instead of a separate peripheral. |
| `4` | `Debug` | `debug` | `arg0[29:0]=debugAddress` | `data[31:0]=debugRegister` | Internal probes are available without adding a debug bus to the PS. |

## Beethoven memory channels

Declared in `memoryChannelConfig` and instantiated with `getReaderModule(...)` /
`getWriterModule(...)`.

| Channel name | Beethoven config | Direction from PL perspective | Data bytes | Bridge module | Local PL endpoint | Shared DDR buffer | Purpose |
|---|---|---|---:|---|---|---|---|
| `rom` | `ReadChannelConfig("rom", dataBytes = 4)` | Read | 4 | `ReadMemoryChannelBridge(addressWidth = 25, dataWidth = 32, transferBytes = 4)` | `subsystem.io.romMemory` | ROM buffer | PL fetches cartridge ROM data loaded by PS. |
| `save_read` | `ReadChannelConfig("save_read", dataBytes = 1)` | Read | 1 | `ReadMemoryChannelBridge(addressWidth = 17, dataWidth = 8, transferBytes = 1)` | read side of `subsystem.io.saveMemory` | Save RAM buffer | PL reads byte-granular external cartridge RAM. |
| `save_write` | `WriteChannelConfig("save_write", dataBytes = 1)` | Write | 1 | `WriteMemoryChannelBridge(addressWidth = 17, dataWidth = 8, transferBytes = 1)` | write side of `subsystem.io.saveMemory` | Save RAM buffer | PL writes byte-granular external cartridge RAM for `.sav` persistence. |
| `frame` | `WriteChannelConfig("frame", dataBytes = 4)` | Write | 4 | `WriteMemoryChannelBridge(addressWidth = Address.addrBits(), dataWidth = 32, transferBytes = 4)` | `subsystem.io.frameMemory` | Triple framebuffer | PL writes completed RGB555 video frames to DDR for GTK display. |
| `audio` | `WriteChannelConfig("audio", dataBytes = 4)` | Write | 4 | `WriteMemoryChannelBridge(addressWidth = Address.addrBits(), dataWidth = 32, transferBytes = 4)` | `subsystem.io.audioMemory` | Audio ring | PL writes packed stereo samples to DDR for PS audio drain. |

## Channel bridge handshake

Defined in `MemoryChannelBridges.scala`.

| Bridge | Beethoven-side interface | GameBoy-side interface | Request behavior | Data behavior | Why this helps |
|---|---|---|---|---|---|
| `ReadMemoryChannelBridge` | `Decoupled[ChannelTransactionBundle]` request + `DataChannelIO(dataWidth)` data | `MemoryInterface(addressWidth, dataWidth)` | Emits `addr = baseAddress + (mem.address << addressShiftBits)` and `len = transferBytes` when local memory request is enabled and not a write. | Waits for returned data, exposes it as `mem.dataRead`, and pulses `mem.done`. | Converts the core's simple local memory request into a Beethoven memory-stream read. |
| `WriteMemoryChannelBridge` | `Decoupled[ChannelTransactionBundle]` request + `WriterDataChannelIO(dataWidth)` data | `MemoryInterface(addressWidth, dataWidth)` | Emits `addr = baseAddress + (mem.address << addressShiftBits)` and `len = transferBytes` when local memory request is enabled and is a write. | Sends `mem.dataWrite`, waits for Beethoven writer flush, then pulses `mem.done`. | Converts local save/frame/audio writes into DDR writes without hand-writing AXI masters. |

## Host-side Beethoven runtime/libbeethoven interfaces

Used in `sw/gameboy_bridge.cc`.

| Interface | Exact use | Purpose | Simplification |
|---|---|---|---|
| `#include <beethoven_hardware.h>` | Generated project binding | Provides `GameboyZu3System::gameboy(...)`. | Host code does not hand-maintain command IDs or generated offsets. |
| `beethoven::fpga_handle_t` | `beethoven::fpga_handle_t handle;` | Creates the runtime/FPGA context. | Abstracts simulation vs real FPGA handle setup. |
| `beethoven::set_fpga_context(&handle)` | Called once at bridge startup. | Makes subsequent generated binding calls use this FPGA context. | Avoids passing context through every generated call. |
| `handle.malloc(size)` | Bridge command `alloc`. | Allocates PS/PL-visible memory and returns a `remote_ptr`. | Host gets a buffer with both host virtual and FPGA physical addresses. |
| `beethoven::remote_ptr` | Stored in bridge buffer map. | Owns host pointer, FPGA address, and length. | Single object tracks shared-buffer metadata. |
| `remote_ptr.getFpgaAddr()` | Returned to Python as `physical_address`. | Provides the address sent through `Configure`. | No manual `/proc/pagemap` handling in Python host code. |
| `remote_ptr.getHostAddr()` | Used for `memcpy`, `memset`, and base64 reads. | Lets PS populate/drain shared buffers. | GUI/audio/ROM code operates on normal host memory views. |
| `remote_ptr.getLen()` | Bounds checks bridge reads/writes. | Prevents out-of-range host buffer access. | Keeps buffer safety in one bridge layer. |
| `handle.copy_to_fpga(buffer)` | After `buffer_write` / `buffer_zero`. | Flushes host writes toward FPGA-visible memory where needed. | Abstracts cache maintenance/platform differences. |
| `handle.copy_from_fpga(buffer)` | Before `buffer_read`. | Refreshes host view from FPGA-written memory where needed. | Abstracts cache maintenance/platform differences. |

## Python bridge layer over Beethoven

`sw/gameboy_host/bridge.py` keeps the GUI application independent from the C++
runtime binding.

| Python API | C++ bridge command | Beethoven call underneath | Used for |
|---|---|---|---|
| `alloc_buffer(name, size)` | `alloc` | `fpga_handle_t::malloc` | Create ROM/save/frame/audio buffers. |
| `buffer_write(name, offset, data)` | `buffer_write` | `remote_ptr.getHostAddr()` + `copy_to_fpga` | Load ROM bytes and save/RTC state into shared DDR. |
| `buffer_zero(name, offset, size)` | `buffer_zero` | `memset` + `copy_to_fpga` | Clear unused ROM/save/audio/frame regions. |
| `buffer_read(name, offset, size)` | `buffer_read` | `copy_from_fpga` + host read | Read framebuffers, audio ring data, and smoke-test buffers. |
| `configure(...)` | `configure` | `GameboyZu3System::gameboy(..., op=0)` | Pass all buffer base addresses and cartridge metadata to PL. |
| `control(...)` | `control` | `GameboyZu3System::gameboy(..., op=1)` | Run/reset/clear, joypad state, audio read index. |
| `status()` | `status` | `GameboyZu3System::gameboy(..., op=2)` | Poll video/audio progress and diagnostics. |
| `read_rtc(...)` | `rtc_read` | `GameboyZu3System::gameboy(..., op=3)` | Persist MBC3 RTC state. |
| `write_rtc(...)` | `rtc_write` | `GameboyZu3System::gameboy(..., op=3)` | Restore MBC3 RTC state. |
| `debug_register(address)` | `debug` | `GameboyZu3System::gameboy(..., op=4)` | Probe internal PL state during board bring-up. |

## Shared-buffer contract implemented through Beethoven

| Buffer | Allocated by | Address delivered by | Filled/drained by PS | Accessed by PL through | Synchronization path |
|---|---|---|---|---|---|
| ROM | C++ bridge via `handle.malloc` / Python `BridgeBuffer` | `remote_ptr.getFpgaAddr()` -> `Configure.arg0` | PS loads `.gbc` file and writes bytes before run. | `rom` read channel | Static after configuration. |
| Save RAM | C++ bridge via `handle.malloc` / Python `BridgeBuffer` | `Configure.arg2` | PS loads `.sav` before run and writes `.sav` on close. | `save_read` + `save_write` channels | Host persistence at load/exit; PL byte writes update DDR. |
| Framebuffers | C++ bridge via `handle.malloc` / Python `BridgeBuffer` | `Configure.arg3..arg5` | PS reads latest completed frame for GTK. | `frame` write channel | `Status.frameCompletedIndex` and `Status.frameCounter`. |
| Audio ring | C++ bridge via `handle.malloc` / Python `BridgeBuffer` | `Configure.arg6`, capacity in `arg7` | PS drains samples and forwards them to Linux audio/null sink. | `audio` write channel | `Status.audioWriteIndex` plus `Control.audioReadIndex`. |

## CLI/runtime interfaces used by this example

| Beethoven CLI/runtime surface | Used in the GameBoy flow | Output or effect | Simplification |
|---|---|---|---|
| `beethoven build hw` / `sbt runMain beethoven.cli.Run ...` | Hardware elaboration and binding generation. | RTL, `target/binding/beethoven_hardware.{h,cc}`. | Host software gets generated C++ calls from the Chisel command declaration. |
| `beethoven synth` | AUP-ZU3 synthesis/implementation/bitstream flow. | Vivado implementation under `target/synthesis/implementation/`. | One project-level flow drives setup, synth, impl, and bitstream. |
| `beethoven flash` | Tested for the GBC emulator bitstream. | Programs the FPGA over Vivado/JTAG using `jtag_program.tcl`. | Avoids manual Vivado hardware-manager steps when JTAG is available. |
| `beethoven build runtime` | Builds `target/synthesis/runtime/BeethovenRuntime`. | Runtime daemon for the selected platform. | Common runtime process bridges generated host calls to the programmed fabric. |
| `BeethovenRuntime` | Started before board bridge/smoke runs. | Runtime service used by libbeethoven/generated binding. | Host bridge code can use the same generated binding in board and simulation flows. |

## How Beethoven simplifies this example

| Without Beethoven | With Beethoven in this example |
|---|---|
| Manually design a PS/PL command register map and maintain matching C++ constants. | Declare `GameboyCmd` / `GameboyResp`; use generated `GameboyZu3System::gameboy(...)`. |
| Hand-write AXI masters for ROM, save RAM, frame writes, and audio writes. | Declare `ReadChannelConfig` / `WriteChannelConfig`; connect local bridges to named Beethoven channels. |
| Manually solve board address width and platform physical address sizing. | Use `Address.addrBits()` and generated `beethovenNumAddrBits = 49`. |
| Manually allocate contiguous PS/PL-visible buffers and expose physical addresses to Python. | Use `fpga_handle_t::malloc`, `remote_ptr`, and generated/runtime cache maintenance helpers. |
| Maintain separate simulation and board host APIs. | Use the same generated command binding and bridge protocol for simulation and AUP-ZU3 board runs. |
| Add separate peripherals for display, audio, input, ROM, and save RAM. | Keep peripherals on PS/Linux and expose only shared DDR buffers plus compact commands to PL. |
| Use Vivado hardware manager manually for JTAG programming. | Use `beethoven flash` when the local machine can reach the JTAG cable. |

## Minimal mental model

The GameBoy example uses Beethoven for exactly two hardware-facing abstractions:

1. **A compact command/response channel** named `gameboy` for configuration,
   control, status, RTC, and debug.
2. **Five named DDR memory streams** for ROM, save RAM, framebuffers, and audio.

Everything else in the application stays normal software: Python loads ROMs,
GTK displays frames, Linux handles input/audio, and save files live on disk.
Beethoven is the integration layer that turns those software buffers and Chisel
interfaces into a generated PS/PL system.

# Beethoven Zoo

A collection of complete example accelerators and demos built with the Beethoven hardware/software framework.

## Examples

| Example | Platform | Summary |
|---|---|---|
| [`GameBoy`](GameBoy/) | AUP-ZU3 / Zynq UltraScale+ | Game Boy / Game Boy Color emulator: PS Linux owns ROM loading, GTK display, input, audio, save/RTC persistence; PL runs the GBC core through Beethoven command and memory-stream interfaces. |
| [`SystolicArray`](SystolicArray/) | Generic | Simple systolic-array accelerator with a fixed-point matmul testbench. |
| [`SHAKE256`](SHAKE256/) | Generic | Verilog SHAKE256 demo with a C++ software reference testbench. |
| [`A3`](A3/) | Generic simulation (`default` / Icarus) | Legacy attention accelerator ported behind a manifest-driven Zoo wrapper with deterministic A3 golden-vector simulation. |

## Development Note

A standalone Beethoven-Zoo clone can build after `beethoven setup`.

Developers iterating on Beethoven-Hardware can still override individual manifests with a source `path`, but the tutorial smoke path should not require sibling checkouts.


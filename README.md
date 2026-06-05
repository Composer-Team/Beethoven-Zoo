# Beethoven Zoo

A collection of complete example accelerators and demos built with the Beethoven hardware/software framework.

## Examples

| Example | Platform | Summary |
|---|---|---|
| [`GameBoy`](GameBoy/) | AUP-ZU3 / Zynq UltraScale+ | Game Boy / Game Boy Color emulator: PS Linux owns ROM loading, GTK display, input, audio, save/RTC persistence; PL runs the GBC core through Beethoven command and memory-stream interfaces. |
| [`SystolicArray`](SystolicArray/) | Generic simulation (`default` / Icarus) | Manifest-driven port of the legacy Chisel systolic-array accelerator with a deterministic C++ fixed-point matmul testbench. |

## Checkout layout

Most examples expect the Zoo checkout to sit next to the Beethoven framework checkouts:

```text
<workspace>/
  Beethoven-Zoo/
  Beethoven-Hardware/
  Beethoven-Software/
```

For custom locations, set `BEETHOVEN_HARDWARE` for SBT builds and `BEETHOVEN_SOFTWARE` for CMake host builds.

## Repository policy

- User ROM files are not redistributed. Bring your own legally obtained `.gb`/`.gbc` files.
- Build products, waveforms, board-local ROM staging directories, save files, and RTC persistence files are ignored.
- Examples should include source, host code, scripts, and documentation needed to rebuild or rerun them.

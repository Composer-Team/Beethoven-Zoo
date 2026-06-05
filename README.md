# Beethoven Zoo

A collection of complete example accelerators and demos built with the Beethoven hardware/software framework.

## Examples

| Example | Platform | Summary |
|---|---|---|
| [`GameBoy`](GameBoy/) | AUP-ZU3 / Zynq UltraScale+ | Game Boy / Game Boy Color emulator: PS Linux owns ROM loading, GTK display, input, audio, save/RTC persistence; PL runs the GBC core through Beethoven command and memory-stream interfaces. |
| [`SystolicArray`](SystolicArray/) | Generic | Simple systolic-array accelerator with a fixed-point matmul testbench. |
| [`SHAKE256`](SHAKE256/) | Generic | Verilog SHAKE256 demo with a C++ software reference testbench. |

## Checkout layout

Most examples expect the Zoo checkout to sit next to the Beethoven framework checkouts:

```text
<workspace>/
  Beethoven-Zoo/
  Beethoven-Hardware/
  Beethoven-Software/
```

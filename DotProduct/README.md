# Dot Product — Beethoven Tutorial Exercise

Compute the dot product of two vectors and return the scalar result **directly to the host in the command response** — no output buffer, no write-back to memory.

This example is designed as a **fill-in-the-blank exercise** that teaches the parts of the Beethoven API that the systolic-array example does *not* cover: a custom response payload, a writer-free core, and reading the result straight off `.get()`. The accelerator logic itself (a multiply-accumulate) is already provided — every blank is a Beethoven API call.

---

## What this example teaches

| Concept | Systolic Array | Dot Product |
|---|---|---|
| Memory channels | 2 readers + 1 writer | **1 reader, no writer** |
| Response type | `EmptyAccelResponse()` | **Custom `AccelResponse` with a payload field** |
| State machine | idle → go → **flush** → response | idle → go → response (**no flush state**) |
| Reading the result | `copy_from_fpga` + host pointer | **`.get()` returns a struct directly** |

### Data layout

The two input vectors are streamed as a **single interleaved stream**: one 64-bit beat per index `i`, carrying `a[i]` in the low 32 bits and `b[i]` in the high 32 bits. One in-order read stream (instead of two separate read channels) keeps the core simple and matches how the data is packed by the host testbench.

---

## Where to fill in the blanks (exercise form)

The exercise version replaces the lines below with `/* TODO */`. This checked-in version is the full reference solution (it builds and passes in simulation). The blanks fall into four groups:

### 1. `DotProductConfig.scala` — declare the memory channel

```scala
memoryChannelConfig = List( /* TODO */ )
```

Fill in a single `ReadChannelConfig` named `"pairs"`. There is **no `WriteChannelConfig`** — the result is returned in the response, not written to memory.

**Hint:** each beat carries one `(a, b)` pair, so the channel width is `pairBytes` (8) from `Constants.scala`.

### 2. `DotProductCore.scala` — wire up the request channel

```scala
pairs.requestChannel.valid     /* := ??? */
pairs.requestChannel.bits.addr /* := ??? */
pairs.requestChannel.bits.len  /* := ??? */
```

The request fires on `cmd_fire`. The address comes from the command (`io.req.bits.in_addr`); the length in bytes is `pairBytes * length`.

### 3. `DotProductCore.scala` — handshakes, response, and state machine

```scala
io.req.ready        /* := ??? */   // idle AND the reader is ready for a request
io.resp.valid       /* := ??? */   // valid in s_response
io.resp.bits.result /* := ??? */   // the accumulator
```

```scala
when(state === s_idle) {
  // TODO: go to s_go on cmd_fire
}.elsewhen(state === s_go) {
  // TODO: go to s_response when the last pair has been consumed
}.elsewhen(state === s_response) {
  // TODO: go back to s_idle once the host accepts the response
}
```

Note there is **no `s_flush` state**. Because there is no writer, the core can respond as soon as the accumulation finishes.

### 4. `dot_product_tb.cc` — host-side API

```cpp
// allocate one interleaved {a,b} buffer
// auto pairs = ???;

// copy it to the FPGA
// ???

// dispatch and read the scalar result back out of the response
// auto resp = DotProductCore::dot_product(???, ???, ???).get();
// resp.result   // uint64_t dot product
```

The generated stub is named after the command (`"dot_product"`). Its arguments are the core ID followed by the command fields **in alphabetical order**: `in_addr`, then `length`. `.get()` returns `DotProductCore::dot_product_resp`, a struct with a single `uint64_t result` field.

---

## File layout

```
DotProduct/
├── Beethoven.toml                  # project manifest (simulator, paths)
├── build.sbt                       # sbt build (do not edit)
├── project/build.properties
├── hw/src/main/scala/dot_product/
│   ├── Constants.scala             # elemBytes (4), pairBytes (8)
│   ├── DotProductCore.scala        # DotProductCmd, DotProductResp, DotProductCore
│   └── DotProductConfig.scala      # AcceleratorConfig + memory channel list
└── sw/
    ├── CMakeLists.txt
    └── dot_product_tb.cc           # C++ testbench (sum-of-squares 1..16 = 1496)
```

## Building and running in simulation

```bash
# from this directory
beethoven sim          # builds hw + runtime + sw, then runs the testbench
```

Expected output:

```
[PASS] dot_product: sum-of-squares 1..16 = 1496
```

### Simulator note (Icarus)

The default simulator is Icarus Verilog (`simulator = "icarus"` in `Beethoven.toml`). Icarus 12.0 cannot elaborate one SystemVerilog packed-array initializer that CIRCT emits in the generated `target/simulation/hw/TLToAXI4.sv` (a TileLink→AXI4 id table). It is a trivial identity table; if a fresh `beethoven build hw` regenerates the file and the Icarus build fails on `TLToAXI4.sv`, replace

```verilog
wire [3:0][1:0] _GEN = '{2'h3, 2'h2, 2'h1, 2'h0};
...
assign out_arw_bits_id = _GEN[nodeIn_a_bits_source];
```

with the inlined identity

```verilog
assign out_arw_bits_id = nodeIn_a_bits_source;
```

then re-run `beethoven build runtime`. A newer Icarus (or a Verilator backend with a compatible runtime) avoids the manual edit.

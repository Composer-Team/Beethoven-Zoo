#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>

#include <cstdint>
#include <cstdio>

using namespace beethoven;

namespace {
constexpr int kInnerDimension = 4;
} // namespace

// Minimal smoke test: exercise "init_weights" in isolation (no matmul), to
// confirm the command completes (the scratchpad init DMA + settle wait +
// response handshake) without needing to involve the rest of the pipeline.
int main() {
  fpga_handle_t handle;
  set_fpga_context(&handle);

  static_assert(DATA_WIDTH_BYTES == sizeof(int16_t), "testbench assumes 16-bit fixed-point data");

  auto weights = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * kInnerDimension);
  auto *host_wgt = static_cast<int16_t *>(weights.getHostAddr());

  for (int i = 0; i < SYSTOLIC_ARRAY_DIM * kInnerDimension; ++i) {
    host_wgt[i] = static_cast<int16_t>(i);
  }

  handle.copy_to_fpga(weights);

  SystolicArrayCore::init_weights(0, kInnerDimension, weights).get();

  handle.shutdown();

  std::printf("[PASS] init_weights: command completed for inner dimension %d.\n", kInnerDimension);
  return 0;
}

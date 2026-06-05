#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>

using namespace beethoven;

namespace {
constexpr int kInnerDimension = 4;
constexpr int kMagnitudeMask = (1 << (DATA_WIDTH_BYTES * 8 - 1)) - 1;
constexpr int kSignBit = 1 << (DATA_WIDTH_BYTES * 8 - 1);

int16_t fp_to_fixp(int numerator, int denominator) {
  const int magnitude = (std::abs(numerator) << FRAC_BITS) / denominator;
  return static_cast<int16_t>(magnitude | (numerator < 0 ? kSignBit : 0));
}

double fixp_to_fp(int16_t value) {
  const double magnitude = static_cast<double>(value & kMagnitudeMask) / (1 << FRAC_BITS);
  return (value & kSignBit) ? -magnitude : magnitude;
}

int16_t fixed_product(int16_t lhs, int16_t rhs) {
  const int lhs_magnitude = lhs & kMagnitudeMask;
  const int rhs_magnitude = rhs & kMagnitudeMask;
  const int sign = ((lhs ^ rhs) & kSignBit);
  const int magnitude = (lhs_magnitude * rhs_magnitude) >> FRAC_BITS;
  return static_cast<int16_t>(sign | magnitude);
}

int16_t fixed_add_positive(int16_t lhs, int16_t rhs) {
  return static_cast<int16_t>((lhs & kMagnitudeMask) + (rhs & kMagnitudeMask));
}
} // namespace

int main() {
  fpga_handle_t handle;
  set_fpga_context(&handle);

  static_assert(DATA_WIDTH_BYTES == sizeof(int16_t), "testbench assumes 16-bit fixed-point data");

  auto activations = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * kInnerDimension);
  auto weights = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * kInnerDimension);
  auto outputs = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * SYSTOLIC_ARRAY_DIM);

  auto *host_act = static_cast<int16_t *>(activations.getHostAddr());
  auto *host_wgt = static_cast<int16_t *>(weights.getHostAddr());
  auto *host_out = static_cast<int16_t *>(outputs.getHostAddr());

  int16_t expected[SYSTOLIC_ARRAY_DIM * SYSTOLIC_ARRAY_DIM] = {};

  for (int k = 0; k < kInnerDimension; ++k) {
    for (int i = 0; i < SYSTOLIC_ARRAY_DIM; ++i) {
      host_act[k * SYSTOLIC_ARRAY_DIM + i] = fp_to_fixp(((k + 2 * i) % 5) + 1, 8);
      host_wgt[k * SYSTOLIC_ARRAY_DIM + i] = fp_to_fixp(((2 * k + i) % 5) + 1, 8);
    }
  }

  for (int row = 0; row < SYSTOLIC_ARRAY_DIM; ++row) {
    for (int col = 0; col < SYSTOLIC_ARRAY_DIM; ++col) {
      int16_t sum = 0;
      for (int k = 0; k < kInnerDimension; ++k) {
        const int16_t act = host_act[k * SYSTOLIC_ARRAY_DIM + row];
        const int16_t wgt = host_wgt[k * SYSTOLIC_ARRAY_DIM + col];
        sum = fixed_add_positive(sum, fixed_product(act, wgt));
      }
      expected[col * SYSTOLIC_ARRAY_DIM + row] = sum;
    }
  }

  handle.copy_to_fpga(activations);
  handle.copy_to_fpga(weights);

  SystolicArrayCore::matmul(0, activations, kInnerDimension, outputs, weights).get();

  handle.copy_from_fpga(outputs);

  int errors = 0;
  for (int i = 0; i < SYSTOLIC_ARRAY_DIM; ++i) {
    for (int j = 0; j < SYSTOLIC_ARRAY_DIM; ++j) {
      const int index = i * SYSTOLIC_ARRAY_DIM + j;
      if (host_out[index] != expected[index]) {
        if (errors < 16) {
          std::printf("[FAIL] out[%d,%d]: got %.6f (0x%04x), expected %.6f (0x%04x)\n",
                      i,
                      j,
                      fixp_to_fp(host_out[index]),
                      static_cast<unsigned>(static_cast<uint16_t>(host_out[index])),
                      fixp_to_fp(expected[index]),
                      static_cast<unsigned>(static_cast<uint16_t>(expected[index])));
        }
        ++errors;
      }
    }
  }

  handle.shutdown();

  if (errors == 0) {
    std::printf("[PASS] systolic_array: %d x %d matmul with inner dimension %d matched.\n",
                SYSTOLIC_ARRAY_DIM,
                SYSTOLIC_ARRAY_DIM,
                kInnerDimension);
    return 0;
  }

  std::printf("[FAIL] systolic_array: %d mismatches.\n", errors);
  return 1;
}

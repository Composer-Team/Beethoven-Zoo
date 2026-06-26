#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>

using namespace beethoven;

namespace {
constexpr int kInnerDimension = 16;
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

namespace {
void fill_matrix(int16_t *host_buf, int seed) {
  for (int k = 0; k < kInnerDimension; ++k) {
    for (int i = 0; i < SYSTOLIC_ARRAY_DIM; ++i) {
      host_buf[k * SYSTOLIC_ARRAY_DIM + i] = fp_to_fixp(((seed * k + 2 * i) % 5) + 1, 8);
    }
  }
}

void compute_expected(const int16_t *host_act, const int16_t *host_wgt, int16_t *expected) {
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
}

int check_outputs(const int16_t *host_out, const int16_t *expected, const char *label) {
  int errors = 0;
  for (int i = 0; i < SYSTOLIC_ARRAY_DIM; ++i) {
    for (int j = 0; j < SYSTOLIC_ARRAY_DIM; ++j) {
      const int index = i * SYSTOLIC_ARRAY_DIM + j;
      if (host_out[index] != expected[index]) {
        if (errors < 16) {
          std::printf("[FAIL %s] out[%d,%d]: got %.6f (0x%04x), expected %.6f (0x%04x)\n",
                      label,
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
  return errors;
}
} // namespace

int main() {
  fpga_handle_t handle;
  set_fpga_context(&handle);

  static_assert(DATA_WIDTH_BYTES == sizeof(int16_t), "testbench assumes 16-bit fixed-point data");

  auto activations = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * kInnerDimension);
  auto activations2 = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * kInnerDimension);
  auto weights = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * kInnerDimension);
  auto outputs = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * SYSTOLIC_ARRAY_DIM);
  auto outputs2 = handle.malloc(sizeof(int16_t) * SYSTOLIC_ARRAY_DIM * SYSTOLIC_ARRAY_DIM);

  auto *host_act = static_cast<int16_t *>(activations.getHostAddr());
  auto *host_act2 = static_cast<int16_t *>(activations2.getHostAddr());
  auto *host_wgt = static_cast<int16_t *>(weights.getHostAddr());
  auto *host_out = static_cast<int16_t *>(outputs.getHostAddr());
  auto *host_out2 = static_cast<int16_t *>(outputs2.getHostAddr());

  fill_matrix(host_act, 1);
  fill_matrix(host_act2, 3);
  fill_matrix(host_wgt, 2);

  int16_t expected[SYSTOLIC_ARRAY_DIM * SYSTOLIC_ARRAY_DIM] = {};
  int16_t expected2[SYSTOLIC_ARRAY_DIM * SYSTOLIC_ARRAY_DIM] = {};
  compute_expected(host_act, host_wgt, expected);
  compute_expected(host_act2, host_wgt, expected2);

  handle.copy_to_fpga(activations);
  handle.copy_to_fpga(activations2);
  handle.copy_to_fpga(weights);

  // Load the weight matrix into the on-chip scratchpad once...
  SystolicArrayCore::init_weights(0, kInnerDimension, weights).get();

  // ...and reuse it across multiple matmul calls without reloading.
  SystolicArrayCore::matmul(0, activations, kInnerDimension, outputs).get();
  SystolicArrayCore::matmul(0, activations2, kInnerDimension, outputs2).get();

  handle.copy_from_fpga(outputs);
  handle.copy_from_fpga(outputs2);

  int errors = check_outputs(host_out, expected, "call1");
  errors += check_outputs(host_out2, expected2, "call2 (reused weights)");

  handle.shutdown();

  if (errors == 0) {
    std::printf("[PASS] systolic_array: %d x %d matmul with inner dimension %d matched across "
                "weight reuse.\n",
                SYSTOLIC_ARRAY_DIM,
                SYSTOLIC_ARRAY_DIM,
                kInnerDimension);
    return 0;
  }

  std::printf("[FAIL] systolic_array: %d mismatches.\n", errors);
  return 1;
}

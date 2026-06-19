#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>

#include <cassert>
#include <cstdint>
#include <cstdio>

using namespace beethoven;

int main() {
  fpga_handle_t handle;
  set_fpga_context(&handle);

  // static_assert(ELEM_BYTES == sizeof(uint32_t), "testbench assumes 32-bit elements");
  // static_assert(PAIR_BYTES == sizeof(uint64_t), "testbench assumes 64-bit {a,b} pairs");

  // Compute the dot product of a = [1, 2, ..., N] with itself.
  // Expected result: sum of squares 1^2 + 2^2 + ... + N^2 = N*(N+1)*(2N+1)/6
  constexpr uint32_t kLength = 16;

  // One interleaved buffer: each 64-bit word holds a[i] (low 32b) and b[i] (high 32b).
  auto input_a = handle.malloc(sizeof(uint32_t) * kLength);
  auto input_b = handle.malloc(sizeof(uint32_t) * kLength);
  auto *host_input_a = static_cast<uint32_t *>(input_a.getHostAddr());
  auto *host_input_b = static_cast<uint32_t *>(input_b.getHostAddr());
  uint64_t expected = 0;
  for (uint32_t i = 0; i < kLength; ++i) {
    const uint32_t a = i + 1;
    const uint32_t b = i + 1;
    host_input_a[i] = a;
    host_input_b[i] = b;
    expected += (uint64_t)a * b;
  }

  handle.copy_to_fpga(input_a);
  handle.copy_to_fpga(input_b);
  // Generated stub argument order is alphabetical by field name:
  //   dot_product(core_id, in_addr, length)
  // .get() returns DotProductCore::dot_product_resp { uint64_t result; }
  auto resp = DotProductCore::dot_product(0, input_a, input_b, kLength).get();

  handle.shutdown();

  if (resp.result == expected) {
    std::printf("[PASS] dot_product: sum-of-squares 1..%u = %llu\n",
                kLength, (unsigned long long)resp.result);
    return 0;
  }
  std::printf("[FAIL] dot_product: got %llu, expected %llu\n",
              (unsigned long long)resp.result, (unsigned long long)expected);
  return 1;
}

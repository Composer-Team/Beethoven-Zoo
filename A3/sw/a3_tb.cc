#include "A3-n320d64q16.h"
#include <beethoven/fpga_handle.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

using namespace beethoven;

namespace {
constexpr int kExpectedWords = numQueries * d;

int compare_outputs(remote_ptr output, const char *label) {
  auto *output_words = static_cast<uint32_t *>(output.getHostAddr());
  int failed = 0;
  for (int idx = 0; idx < kExpectedWords; ++idx) {
    const uint32_t got = output_words[idx];
    const uint32_t expected = expectedOutput[idx];
    if (got != expected) {
      std::printf("[FAIL] %s idx=%d expected=0x%08x got=0x%08x\n", label, idx, expected, got);
      ++failed;
    }
  }
  if (failed == 0) {
    std::printf("[PASS] %s matched %d output words.\n", label, kExpectedWords);
  }
  return failed;
}

void run_attention_batch(remote_ptr queries, remote_ptr keys, remote_ptr values, remote_ptr outputs) {
  for (int query_idx = 0; query_idx < numQueries; ++query_idx) {
    const int query_offset = query_idx * d * inputWidthBytes;
    const int output_offset = query_idx * d * outputWidthBytes;
    A3::attention(
        0,
        keys,
        1,
        outputs + output_offset,
        queries + query_offset,
        false,
        values)
        .get();
  }
}
void refresh_scratchpads(remote_ptr queries, remote_ptr keys, remote_ptr values, remote_ptr outputs) {
  A3::attention(0, keys, 1, outputs, queries, true, values).get();
}
}

int main() {
  fpga_handle_t handle;

  auto queries = handle.malloc(numQueries * d * inputWidthBytes);
  auto keys = handle.malloc(n * d * inputWidthBytes);
  auto values = handle.malloc(n * d * inputWidthBytes);
  auto outputs = handle.malloc(numQueries * d * outputWidthBytes);

  std::memcpy(queries.getHostAddr(), queryInputs, sizeof(queryInputs));
  std::memcpy(keys.getHostAddr(), keyMatrix, sizeof(keyMatrix));
  std::memcpy(values.getHostAddr(), valueMatrix, sizeof(valueMatrix));
  std::memset(outputs.getHostAddr(), 0, numQueries * d * outputWidthBytes);

  handle.copy_to_fpga(queries);
  handle.copy_to_fpga(keys);
  handle.copy_to_fpga(values);
  handle.copy_to_fpga(outputs);

  refresh_scratchpads(queries, keys, values, outputs);
  std::memset(outputs.getHostAddr(), 0, numQueries * d * outputWidthBytes);
  handle.copy_to_fpga(outputs);
  run_attention_batch(queries, keys, values, outputs);
  std::memset(outputs.getHostAddr(), 0, numQueries * d * outputWidthBytes);
  handle.copy_to_fpga(outputs);
  run_attention_batch(queries, keys, values, outputs);
  handle.copy_from_fpga(outputs);
  int failed = compare_outputs(outputs, "refresh=true");

  std::memset(outputs.getHostAddr(), 0, numQueries * d * outputWidthBytes);
  handle.copy_to_fpga(outputs);

  run_attention_batch(queries, keys, values, outputs);
  handle.copy_from_fpga(outputs);
  failed += compare_outputs(outputs, "refresh=false");

  handle.shutdown();
  return failed == 0 ? 0 : 1;
}

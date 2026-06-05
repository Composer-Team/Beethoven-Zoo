#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>

#include <cstdint>
#include <iostream>

int main() {
  beethoven::fpga_handle_t handle;
  beethoven::set_fpga_context(&handle);
  const uint64_t data = GameboyZu3System::gameboy(0, 0, 0, 0, 0, 0, 0, 0, 0, 2).get().data;
  std::cout << "frameCounter=" << static_cast<uint32_t>(data & 0xFFFFFFFFULL)
            << " audioWriteIndex=" << static_cast<uint32_t>((data >> 32) & 0xFFFFFFULL)
            << " frameCompletedIndex=" << static_cast<unsigned>((data >> 56) & 0x3ULL)
            << " vblank=" << static_cast<unsigned>((data >> 58) & 0x1ULL)
            << " audioOverrun=" << static_cast<unsigned>((data >> 59) & 0x1ULL)
            << std::endl;
  return 0;
}

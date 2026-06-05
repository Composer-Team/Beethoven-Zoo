#include <beethoven/allocator/alloc.h>
#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <thread>

namespace {
constexpr uint8_t kOpConfigure = 0;
constexpr uint8_t kOpControl = 1;
constexpr uint8_t kOpStatus = 2;
constexpr uint8_t kOpRtc = 3;
constexpr uint64_t kCartEnabled = 1ULL;
constexpr size_t kRomBytes = 32 * 1024;
constexpr size_t kSaveBytes = 8 * 1024;
constexpr size_t kFrameBytes = 160 * 144 * 2 * 3;
constexpr size_t kAudioSamples = 8192;
constexpr size_t kAudioBytes = kAudioSamples * 4;

uint64_t gameboy_cmd(
    uint64_t arg0,
    uint64_t arg1,
    uint64_t arg2,
    uint64_t arg3,
    uint64_t arg4,
    uint64_t arg5,
    uint64_t arg6,
    uint64_t arg7,
    uint8_t op) {
  return GameboyZu3System::gameboy(0, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, op).get().data;
}

uint64_t pack_config_arg1(uint64_t romMask, uint64_t saveMask, bool isCgb, uint64_t cartConfig) {
  return (romMask & ((1ULL << 23) - 1)) |
         ((saveMask & ((1ULL << 17) - 1)) << 23) |
         ((uint64_t(isCgb) & 1ULL) << 40) |
         ((cartConfig & 0x7FULL) << 41);
}

uint64_t pack_control_arg0(bool run, bool reset, bool clear, uint8_t buttons, uint32_t audioReadIndex) {
  return (uint64_t(run) << 0) |
         (uint64_t(reset) << 1) |
         (uint64_t(clear) << 2) |
         (uint64_t(buttons) << 8) |
         ((uint64_t(audioReadIndex) & 0xFFFFFFULL) << 16);
}
}

int main() {
  beethoven::fpga_handle_t handle;
  beethoven::set_fpga_context(&handle);

  auto rom = handle.malloc(kRomBytes);
  auto save = handle.malloc(kSaveBytes);
  auto frame = handle.malloc(kFrameBytes);
  auto audio = handle.malloc(kAudioBytes);

  std::memset(rom.getHostAddr(), 0x00, rom.getLen());
  std::memset(save.getHostAddr(), 0x00, save.getLen());
  std::memset(frame.getHostAddr(), 0x00, frame.getLen());
  std::memset(audio.getHostAddr(), 0x00, audio.getLen());

  handle.copy_to_fpga(rom);
  handle.copy_to_fpga(save);
  handle.copy_to_fpga(frame);
  handle.copy_to_fpga(audio);

  const uint64_t romMask = kRomBytes - 1;
  const uint64_t saveMask = kSaveBytes - 1;
  const uint64_t cartConfig = kCartEnabled;

  gameboy_cmd(
      rom.getFpgaAddr(),
      pack_config_arg1(romMask, saveMask, true, cartConfig),
      save.getFpgaAddr(),
      frame.getFpgaAddr(),
      frame.getFpgaAddr() + (160 * 144 * 2),
      frame.getFpgaAddr() + (160 * 144 * 4),
      audio.getFpgaAddr(),
      kAudioSamples,
      kOpConfigure);

  gameboy_cmd(pack_control_arg0(false, true, true, 0, 0), 0, 0, 0, 0, 0, 0, 0, kOpControl);
  gameboy_cmd(pack_control_arg0(true, false, false, 0, 0), 0, 0, 0, 0, 0, 0, 0, kOpControl);

  std::this_thread::sleep_for(std::chrono::milliseconds(20));

  const uint64_t status = gameboy_cmd(0, 0, 0, 0, 0, 0, 0, 0, kOpStatus);
  const auto frameCounter = static_cast<uint32_t>(status & 0xFFFFFFFFULL);
  const auto audioWriteIndex = static_cast<uint32_t>((status >> 32) & 0xFFFFFFULL);
  const auto frameCompletedIndex = static_cast<unsigned>((status >> 56) & 0x3ULL);
  const auto vblank = static_cast<unsigned>((status >> 58) & 0x1ULL);
  const auto audioOverrun = static_cast<unsigned>((status >> 59) & 0x1ULL);

  std::cout << "frameCounter=" << frameCounter
            << " audioWriteIndex=" << audioWriteIndex
            << " frameCompletedIndex=" << frameCompletedIndex
            << " vblank=" << vblank
            << " audioOverrun=" << audioOverrun
            << std::endl;

  gameboy_cmd(pack_control_arg0(false, false, false, 0, audioWriteIndex), 0, 0, 0, 0, 0, 0, 0, kOpControl);
  return 0;
}

#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <exception>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr uint8_t kOpConfigure = 0;
constexpr uint8_t kOpControl = 1;
constexpr uint8_t kOpStatus = 2;
constexpr uint8_t kOpRtc = 3;
constexpr uint8_t kOpDebug = 4;
constexpr std::string_view kBase64Alphabet =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

uint64_t parse_u64(const std::string &token) {
  size_t consumed = 0;
  const auto value = std::stoull(token, &consumed, 0);
  if (consumed != token.size()) {
    throw std::invalid_argument("trailing characters");
  }
  return value;
}

std::vector<std::string> split(const std::string &line) {
  std::istringstream stream(line);
  std::vector<std::string> tokens;
  for (std::string token; stream >> token;) {
    tokens.push_back(token);
  }
  return tokens;
}

void expect_args(const std::vector<std::string> &tokens, size_t count) {
  if (tokens.size() != count) {
    std::ostringstream oss;
    oss << "expected " << (count - 1) << " arguments";
    throw std::runtime_error(oss.str());
  }
}

std::string base64_encode(const uint8_t *data, size_t len) {
  std::string out;
  out.reserve(((len + 2) / 3) * 4);
  for (size_t i = 0; i < len; i += 3) {
    const uint32_t b0 = data[i];
    const uint32_t b1 = (i + 1 < len) ? data[i + 1] : 0;
    const uint32_t b2 = (i + 2 < len) ? data[i + 2] : 0;
    const uint32_t triple = (b0 << 16) | (b1 << 8) | b2;
    out.push_back(kBase64Alphabet[(triple >> 18) & 0x3F]);
    out.push_back(kBase64Alphabet[(triple >> 12) & 0x3F]);
    out.push_back((i + 1 < len) ? kBase64Alphabet[(triple >> 6) & 0x3F] : '=');
    out.push_back((i + 2 < len) ? kBase64Alphabet[triple & 0x3F] : '=');
  }
  return out;
}

std::vector<uint8_t> base64_decode(const std::string &text) {
  static const auto decode_table = [] {
    std::array<int8_t, 256> table{};
    table.fill(-1);
    for (size_t i = 0; i < kBase64Alphabet.size(); ++i) {
      table[static_cast<uint8_t>(kBase64Alphabet[i])] = static_cast<int8_t>(i);
    }
    table[static_cast<uint8_t>('=')] = 0;
    return table;
  }();

  if (text.size() % 4 != 0) {
    throw std::invalid_argument("base64 length is not a multiple of 4");
  }
  size_t padding = 0;
  if (!text.empty() && text[text.size() - 1] == '=') {
    ++padding;
  }
  if (text.size() > 1 && text[text.size() - 2] == '=') {
    ++padding;
  }

  std::vector<uint8_t> out;
  out.reserve((text.size() / 4) * 3 - padding);
  for (size_t i = 0; i < text.size(); i += 4) {
    const auto c0 = decode_table[static_cast<uint8_t>(text[i])];
    const auto c1 = decode_table[static_cast<uint8_t>(text[i + 1])];
    const auto c2 = decode_table[static_cast<uint8_t>(text[i + 2])];
    const auto c3 = decode_table[static_cast<uint8_t>(text[i + 3])];
    if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) {
      throw std::invalid_argument("invalid base64 data");
    }
    const uint32_t triple = (static_cast<uint32_t>(c0) << 18) |
                            (static_cast<uint32_t>(c1) << 12) |
                            (static_cast<uint32_t>(c2) << 6) |
                            static_cast<uint32_t>(c3);
    out.push_back(static_cast<uint8_t>((triple >> 16) & 0xFF));
    if (text[i + 2] != '=') {
      out.push_back(static_cast<uint8_t>((triple >> 8) & 0xFF));
    }
    if (text[i + 3] != '=') {
      out.push_back(static_cast<uint8_t>(triple & 0xFF));
    }
  }
  return out;
}

uint64_t issue(uint64_t arg0, uint64_t arg1, uint64_t arg2, uint64_t arg3, uint64_t arg4, uint64_t arg5, uint64_t arg6, uint64_t arg7, uint8_t op) {
  return GameboyZu3System::gameboy(0, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, op).get().data;
}

} // namespace

int main() {
  try {
    beethoven::fpga_handle_t handle;
    beethoven::set_fpga_context(&handle);

    std::map<std::string, beethoven::remote_ptr> buffers;

    auto require_buffer = [&buffers](const std::string &name) -> beethoven::remote_ptr & {
      auto it = buffers.find(name);
      if (it == buffers.end()) {
        throw std::runtime_error("unknown buffer: " + name);
      }
      return it->second;
    };

    auto check_range = [](const beethoven::remote_ptr &buffer, uint64_t offset, uint64_t length) {
      if (offset > buffer.getLen() || length > buffer.getLen() - offset) {
        throw std::runtime_error("buffer range is out of bounds");
      }
    };

    std::string line;
    while (std::getline(std::cin, line)) {
      if (line.empty()) {
        continue;
      }
      try {
        const auto tokens = split(line);
        if (tokens.empty()) {
          continue;
        }
        if (tokens[0] == "quit") {
          std::cout << "ok" << std::endl;
          break;
        }
        if (tokens[0] == "alloc") {
          expect_args(tokens, 3);
          const std::string &name = tokens[1];
          const uint64_t size = parse_u64(tokens[2]);
          if (buffers.contains(name)) {
            throw std::runtime_error("buffer already exists: " + name);
          }
          auto buffer = handle.malloc(size);
          const auto fpgaAddr = buffer.getFpgaAddr();
          const auto actualSize = buffer.getLen();
          buffers.emplace(name, buffer);
          std::cout << "buffer " << name << ' ' << fpgaAddr << ' ' << actualSize << std::endl;
          continue;
        }
        if (tokens[0] == "buffer_write") {
          expect_args(tokens, 4);
          auto &buffer = require_buffer(tokens[1]);
          const uint64_t offset = parse_u64(tokens[2]);
          const auto payload = base64_decode(tokens[3]);
          check_range(buffer, offset, payload.size());
          std::memcpy(static_cast<uint8_t *>(buffer.getHostAddr()) + offset, payload.data(), payload.size());
          handle.copy_to_fpga(buffer);
          std::cout << "ok" << std::endl;
          continue;
        }
        if (tokens[0] == "buffer_zero") {
          expect_args(tokens, 4);
          auto &buffer = require_buffer(tokens[1]);
          const uint64_t offset = parse_u64(tokens[2]);
          const uint64_t length = parse_u64(tokens[3]);
          check_range(buffer, offset, length);
          std::memset(static_cast<uint8_t *>(buffer.getHostAddr()) + offset, 0, length);
          handle.copy_to_fpga(buffer);
          std::cout << "ok" << std::endl;
          continue;
        }
        if (tokens[0] == "buffer_read") {
          expect_args(tokens, 4);
          auto &buffer = require_buffer(tokens[1]);
          const uint64_t offset = parse_u64(tokens[2]);
          const uint64_t length = parse_u64(tokens[3]);
          check_range(buffer, offset, length);
          handle.copy_from_fpga(buffer);
          const auto *ptr = static_cast<const uint8_t *>(buffer.getHostAddr()) + offset;
          std::cout << "data " << base64_encode(ptr, length) << std::endl;
          continue;
        }
        if (tokens[0] == "configure") {
          expect_args(tokens, 12);
          const uint64_t romBase = parse_u64(tokens[1]);
          const uint64_t romMask = parse_u64(tokens[2]);
          const uint64_t saveBase = parse_u64(tokens[3]);
          const uint64_t saveMask = parse_u64(tokens[4]);
          const uint64_t frameBase0 = parse_u64(tokens[5]);
          const uint64_t frameBase1 = parse_u64(tokens[6]);
          const uint64_t frameBase2 = parse_u64(tokens[7]);
          const uint64_t audioBase = parse_u64(tokens[8]);
          const uint64_t audioCapacity = parse_u64(tokens[9]);
          const uint64_t cartConfig = parse_u64(tokens[10]);
          const uint64_t isCgb = parse_u64(tokens[11]);
          const uint64_t arg1 = (romMask & ((1ULL << 23) - 1)) |
                                ((saveMask & ((1ULL << 17) - 1)) << 23) |
                                ((isCgb & 1ULL) << 40) |
                                ((cartConfig & 0x7FULL) << 41);
          issue(romBase, arg1, saveBase, frameBase0, frameBase1, frameBase2, audioBase, audioCapacity, kOpConfigure);
          std::cout << "ok" << std::endl;
          continue;
        }
        if (tokens[0] == "control") {
          expect_args(tokens, 6);
          const uint64_t run = parse_u64(tokens[1]);
          const uint64_t reset = parse_u64(tokens[2]);
          const uint64_t clear = parse_u64(tokens[3]);
          const uint64_t buttons = parse_u64(tokens[4]);
          const uint64_t audioReadIndex = parse_u64(tokens[5]);
          const uint64_t arg0 = (run & 1ULL) |
                                ((reset & 1ULL) << 1) |
                                ((clear & 1ULL) << 2) |
                                ((buttons & 0xFFULL) << 8) |
                                ((audioReadIndex & 0xFFFFFFULL) << 16);
          issue(arg0, 0, 0, 0, 0, 0, 0, 0, kOpControl);
          std::cout << "ok" << std::endl;
          continue;
        }
        if (tokens[0] == "status") {
          expect_args(tokens, 1);
          const uint64_t data = issue(0, 0, 0, 0, 0, 0, 0, 0, kOpStatus);
          std::cout << "status "
                    << static_cast<uint32_t>(data & 0xFFFFFFFFULL) << ' '
                    << static_cast<uint32_t>((data >> 32) & 0xFFFFFFULL) << ' '
                    << static_cast<unsigned>((data >> 56) & 0x3ULL) << ' '
                    << static_cast<unsigned>((data >> 58) & 0x1ULL) << ' '
                    << static_cast<unsigned>((data >> 59) & 0x1ULL) << std::endl;
          continue;
        }
        if (tokens[0] == "debug") {
          expect_args(tokens, 2);
          const uint64_t address = parse_u64(tokens[1]);
          const uint64_t data = issue(address, 0, 0, 0, 0, 0, 0, 0, kOpDebug);
          std::cout << "debug " << static_cast<uint32_t>(data & 0xFFFFFFFFULL) << std::endl;
          continue;
        }
        if (tokens[0] == "rtc_read") {
          expect_args(tokens, 2);
          const uint64_t latched = parse_u64(tokens[1]);
          const uint64_t data = issue((latched & 1ULL) << 1, 0, 0, 0, 0, 0, 0, 0, kOpRtc);
          std::cout << "rtc " << static_cast<uint32_t>(data & 0x0FFFFFFFULL) << std::endl;
          continue;
        }
        if (tokens[0] == "rtc_write") {
          expect_args(tokens, 3);
          const uint64_t latched = parse_u64(tokens[1]);
          const uint64_t state = parse_u64(tokens[2]);
          const uint64_t arg0 = 1ULL | ((latched & 1ULL) << 1) | ((state & 0x0FFFFFFFULL) << 2);
          issue(arg0, 0, 0, 0, 0, 0, 0, 0, kOpRtc);
          std::cout << "ok" << std::endl;
          continue;
        }
        std::cout << "error unknown-command" << std::endl;
      } catch (const std::exception &ex) {
        std::cout << "error " << ex.what() << std::endl;
      }
    }
    return 0;
  } catch (const std::exception &ex) {
    std::cerr << "bridge startup failed: " << ex.what() << std::endl;
    return 1;
  }
}

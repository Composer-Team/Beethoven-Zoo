#ifndef SHAKE256_HPP
#define SHAKE256_HPP

#include <cstdint>
#include <cstring>
#include <vector>

class Shake256 {
public:
    Shake256();

    // Absorb input data (equivalent to hasher.update())
    void update(const uint8_t* data, size_t len);

    // Finalize and squeeze output (equivalent to hasher.digest())
    std::vector<uint8_t> digest(size_t output_len);

    // Reset to initial state
    void reset();

private:
    static constexpr size_t STATE_SIZE = 25;    // 25 x uint64_t = 1600 bits
    static constexpr size_t RATE = 136;         // bytes per block
    static constexpr int ROUNDS = 24;
    static constexpr uint8_t SHAKE_PAD = 0x1f;

    uint64_t state[STATE_SIZE];
    uint8_t buffer[RATE];
    size_t pos;         // position in buffer
    bool finalized;

    void absorb_block();
    void keccak_p1600();
    static uint64_t rotl(uint64_t x, int n);
};

#endif // SHAKE256_HPP

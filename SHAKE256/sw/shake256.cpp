#include "shake256.hpp"

// Round constants for iota step
static const uint64_t RC[24] = {
    0x0000000000000001ULL, 0x0000000000008082ULL, 0x800000000000808aULL,
    0x8000000080008000ULL, 0x000000000000808bULL, 0x0000000080000001ULL,
    0x8000000080008081ULL, 0x8000000000008009ULL, 0x000000000000008aULL,
    0x0000000000000088ULL, 0x0000000080008009ULL, 0x000000008000000aULL,
    0x000000008000808bULL, 0x800000000000008bULL, 0x8000000000008089ULL,
    0x8000000000008003ULL, 0x8000000000008002ULL, 0x8000000000000080ULL,
    0x000000000000800aULL, 0x800000008000000aULL, 0x8000000080008081ULL,
    0x8000000000008080ULL, 0x0000000080000001ULL, 0x8000000080008008ULL
};

// Rotation offsets for rho step
static const int RHO[24] = {
    1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14,
    27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
};

// Permutation indices for pi step
static const int PI[24] = {
    10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4,
    15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
};

Shake256::Shake256() {
    reset();
}

void Shake256::reset() {
    std::memset(state, 0, sizeof(state));
    std::memset(buffer, 0, sizeof(buffer));
    pos = 0;
    finalized = false;
}

uint64_t Shake256::rotl(uint64_t x, int n) {
    return (x << n) | (x >> (64 - n));
}

// The Keccak-p[1600,24] permutation
void Shake256::keccak_p1600() {
    uint64_t C[5];

    for (int round = 0; round < ROUNDS; round++) {
        // Theta
        for (int x = 0; x < 5; x++) {
            C[x] = state[x] ^ state[x + 5] ^ state[x + 10] ^ state[x + 15] ^ state[x + 20];
        }
        for (int x = 0; x < 5; x++) {
            uint64_t t = C[(x + 4) % 5] ^ rotl(C[(x + 1) % 5], 1);
            for (int y = 0; y < 5; y++) {
                state[x + 5 * y] ^= t;
            }
        }

        // Rho + Pi (combined)
        uint64_t last = state[1];
        for (int i = 0; i < 24; i++) {
            int j = PI[i];
            uint64_t temp = state[j];
            state[j] = rotl(last, RHO[i]);
            last = temp;
        }

        // Chi
        for (int y = 0; y < 5; y++) {
            uint64_t row[5];
            for (int x = 0; x < 5; x++) {
                row[x] = state[x + 5 * y];
            }
            for (int x = 0; x < 5; x++) {
                state[x + 5 * y] = row[x] ^ ((~row[(x + 1) % 5]) & row[(x + 2) % 5]);
            }
        }

        // Iota
        state[0] ^= RC[round];
    }
}

// XOR buffer into state and apply permutation
void Shake256::absorb_block() {
    // XOR buffer into state (little-endian)
    for (size_t i = 0; i < RATE / 8; i++) {
        uint64_t lane = 0;
        for (int j = 0; j < 8; j++) {
            lane |= static_cast<uint64_t>(buffer[i * 8 + j]) << (8 * j);
        }
        state[i] ^= lane;
    }
    keccak_p1600();
}

void Shake256::update(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) {
        buffer[pos++] = data[i];
        if (pos == RATE) {
            absorb_block();
            pos = 0;
        }
    }
}

std::vector<uint8_t> Shake256::digest(size_t output_len) {
    // Finalize if not already done
    if (!finalized) {
        // Clear rest of buffer
        std::memset(buffer + pos, 0, RATE - pos);

        // SHAKE256 padding
        buffer[pos] = SHAKE_PAD;
        buffer[RATE - 1] |= 0x80;

        // Absorb final block
        absorb_block();

        finalized = true;
    }

    // Squeeze output
    std::vector<uint8_t> output(output_len);
    size_t out_pos = 0;

    while (out_pos < output_len) {
        // Extract bytes from state (little-endian)
        for (size_t i = 0; i < RATE && out_pos < output_len; i++) {
            size_t lane = i / 8;
            size_t byte_in_lane = i % 8;
            output[out_pos++] = static_cast<uint8_t>(state[lane] >> (8 * byte_in_lane));
        }

        // If more bytes needed, permute again
        if (out_pos < output_len) {
            keccak_p1600();
        }
    }

    return output;
}

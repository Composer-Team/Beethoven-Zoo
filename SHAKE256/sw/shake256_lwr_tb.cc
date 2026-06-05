#include <cstdint>
#include <cstdio>
#include <vector>
#include <beethoven/fpga_handle.h>
#include <beethoven_hardware.h>
#include "shake256.hpp"

using namespace beethoven;

// Software reference implementation of SHAKE256 LWR-PRF.
//
// For each vector index v in 0..count-1:
//   1. Absorb (nonce || v) into SHAKE256 (16 bytes total).
//   2. Squeeze N_BLOCKS * 136 bytes.
//   3. For each 136-byte block, extract up to ELEMS_PER_BLK=90 elements
//      of ELEM_WIDTH=12 bits from bits [1079:0] (top 8 bits discarded).
//   4. Accumulate dot product <a, sk> over Z (sk binary).
//   5. Apply LWR rounding to Z_P.
static void shake256_lwr_ref(const uint8_t *nonce_bytes, const uint8_t *sk_bytes,
                              int count, int *prf_out)
{
    const int N_LWR = SHAKE256_LWR_N_LWR;     // 445
    const int N = SHAKE256_LWR_N;             // 2048
    const int P = SHAKE256_LWR_P;             // 16
    const int ELEM_WIDTH = 12;                // log2(2*N) = log2(4096)
    const int RATE_BITS = 1088;               // SHAKE256 rate in bits
    const int RATE_BYTES = RATE_BITS / 8;     // 136 bytes per squeeze block
    const int ELEMS_PER_BLK = RATE_BITS / ELEM_WIDTH; // 90 (top 8 bits discarded)
    const int N_BLOCKS = (N_LWR + ELEMS_PER_BLK - 1) / ELEMS_PER_BLK; // 5
    const int OUT_BYTES = N_BLOCKS * RATE_BYTES; // 680

    // Extract binary secret key (bit i = (sk_bytes[i/8] >> (i%8)) & 1)
    int sk[N_LWR];
    for (int i = 0; i < N_LWR; i++)
        sk[i] = (sk_bytes[i / 8] >> (i % 8)) & 1;

    for (int v = 0; v < count; v++) {
        // Input: nonce (8 bytes) || index (8 bytes, little-endian 64-bit)
        Shake256 hasher;
        hasher.update(nonce_bytes, SHAKE256_LWR_NONCE_BYTES);
        uint64_t idx = (uint64_t)v;
        uint8_t idx_bytes[8];
        for (int j = 0; j < 8; j++)
            idx_bytes[j] = (uint8_t)((idx >> (8 * j)) & 0xFF);
        hasher.update(idx_bytes, 8);

        // Squeeze N_BLOCKS full rate blocks
        std::vector<uint8_t> sq = hasher.digest(OUT_BYTES);

        // Extract N_LWR elements, 12 bits each, from successive blocks.
        // Element j within a block = bits [j*12+11 : j*12] of the block byte array.
        // Top 8 bits of each block (bits [1087:1080]) are automatically unused
        // since element 89 ends at bit 1079 (byte 134 bit 7).
        long long dot = 0;
        int elem_count = 0;

        for (int blk = 0; blk < N_BLOCKS && elem_count < N_LWR; blk++) {
            const uint8_t *block = &sq[blk * RATE_BYTES];
            for (int j = 0; j < ELEMS_PER_BLK && elem_count < N_LWR; j++) {
                int bit_start = j * ELEM_WIDTH;
                int byte0 = bit_start / 8;
                int bit0 = bit_start % 8;
                // Read 3 bytes to safely span any 12-bit window
                uint32_t bits = (uint32_t)block[byte0]
                              | ((uint32_t)block[byte0 + 1] << 8)
                              | ((uint32_t)block[byte0 + 2] << 16);
                int elem = (bits >> bit0) & ((1 << ELEM_WIDTH) - 1);
                if (sk[elem_count])
                    dot += elem;
                elem_count++;
            }
        }

        // LWR rounding: floor(P/N * (dot mod N)) mod P
        int inner_mod_2N = (int)(dot & ((2 * N) - 1)); // lower 12 bits
        int msb = (inner_mod_2N >> 11) & 1;            // 1 if in [N, 2N)
        int inner_mod_N = inner_mod_2N & (N - 1);      // lower 11 bits
        int rounded = inner_mod_N >> 7;                 // top 4 bits of 11-bit value
        prf_out[v] = msb ? (P - rounded) & 0xF : rounded;
    }

    printf("Reference generated %d PRF outputs\n", count);
}

int main() {
    fpga_handle_t handle;

    const int count = 16; // must be even
    const int out_bytes = count / 2; // 2 nibbles packed per byte

    auto nonce_buf = handle.malloc(SHAKE256_LWR_NONCE_BYTES);
    auto sk_buf = handle.malloc(SHAKE256_LWR_SK_BYTES);
    auto out_buf = handle.malloc(out_bytes);

    auto nonce_host = (uint8_t *)nonce_buf.getHostAddr();
    auto sk_host = (uint8_t *)sk_buf.getHostAddr();

    // Test vector
    for (int i = 0; i < SHAKE256_LWR_NONCE_BYTES; i++) nonce_host[i] = (uint8_t)(i + 1);
    for (int i = 0; i < SHAKE256_LWR_SK_BYTES; i++) sk_host[i] = 0x55; // 0b01010101

    // Compute expected PRF outputs using software reference
    int expected[count];
    shake256_lwr_ref(nonce_host, sk_host, count, expected);

    handle.copy_to_fpga(nonce_buf);
    handle.copy_to_fpga(sk_buf);
    printf("Addresses copied\n");

    SHAKE256LWRCore::shake256_lwr(0,
        count,
        nonce_buf.getFpgaAddr(),
        out_buf.getFpgaAddr(),
        sk_buf.getFpgaAddr()
    ).get();

    handle.copy_from_fpga(out_buf);

    // Verify: unpack 4-bit nibbles (lower nibble = first PRF output)
    auto output = (uint8_t *)out_buf.getHostAddr();
    int errors = 0;
    for (int i = 0; i < out_bytes; i++) {
        int hw0 = output[i] & 0xF;
        int hw1 = (output[i] >> 4) & 0xF;
        int idx0 = 2 * i, idx1 = 2 * i + 1;
        if (hw0 != expected[idx0]) {
            printf("PRF[%d] mismatch: hw=%d ref=%d\n", idx0, hw0, expected[idx0]);
            errors++;
        }
        if (hw1 != expected[idx1]) {
            printf("PRF[%d] mismatch: hw=%d ref=%d\n", idx1, hw1, expected[idx1]);
            errors++;
        }
    }

    if (errors == 0)
        printf("OK: all %d PRF outputs match\n", count);
    else
        printf("FAIL: %d / %d PRF outputs incorrect\n", errors, count);

    return errors > 0 ? 1 : 0;
}

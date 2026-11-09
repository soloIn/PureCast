#include "aes.h"
#include <string.h>

#define Nb 4
#define Nk 4
#define Nr 10

typedef uint8_t state_t[4][4];

static const uint8_t sbox[256] = {
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
};

static const uint8_t rsbox[256] = {
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
    0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
    0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
    0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
    0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
    0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
    0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
    0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
    0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
    0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
    0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
    0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
    0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
    0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
    0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d
};

static const uint8_t Rcon[11] = {
    0x8d, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36
};

static uint8_t getSBoxValue(uint8_t num) { return sbox[num]; }

static void KeyExpansion(uint8_t* RoundKey, const uint8_t* Key) {
    unsigned i, j, k;
    uint8_t tempa[4];

    for (i = 0; i < Nk; ++i) {
        RoundKey[(i * 4) + 0] = Key[(i * 4) + 0];
        RoundKey[(i * 4) + 1] = Key[(i * 4) + 1];
        RoundKey[(i * 4) + 2] = Key[(i * 4) + 2];
        RoundKey[(i * 4) + 3] = Key[(i * 4) + 3];
    }

    for (i = Nk; i < Nb * (Nr + 1); ++i) {
        k = (i - 1) * 4;
        tempa[0] = RoundKey[k + 0];
        tempa[1] = RoundKey[k + 1];
        tempa[2] = RoundKey[k + 2];
        tempa[3] = RoundKey[k + 3];

        if (i % Nk == 0) {
            const uint8_t u8tmp = tempa[0];
            tempa[0] = tempa[1];
            tempa[1] = tempa[2];
            tempa[2] = tempa[3];
            tempa[3] = u8tmp;

            tempa[0] = getSBoxValue(tempa[0]);
            tempa[1] = getSBoxValue(tempa[1]);
            tempa[2] = getSBoxValue(tempa[2]);
            tempa[3] = getSBoxValue(tempa[3]);

            tempa[0] = tempa[0] ^ Rcon[i / Nk];
        }

        j = i * 4;
        k = (i - Nk) * 4;
        RoundKey[j + 0] = RoundKey[k + 0] ^ tempa[0];
        RoundKey[j + 1] = RoundKey[k + 1] ^ tempa[1];
        RoundKey[j + 2] = RoundKey[k + 2] ^ tempa[2];
        RoundKey[j + 3] = RoundKey[k + 3] ^ tempa[3];
    }
}

void AES_init_ctx(struct AES_ctx* ctx, const uint8_t* key) {
    KeyExpansion(ctx->RoundKey, key);
}

void AES_init_ctx_iv(struct AES_ctx* ctx, const uint8_t* key, const uint8_t* iv) {
    KeyExpansion(ctx->RoundKey, key);
    memcpy(ctx->Iv, iv, AES_BLOCKLEN);
}

void AES_ctx_set_iv(struct AES_ctx* ctx, const uint8_t* iv) {
    memcpy(ctx->Iv, iv, AES_BLOCKLEN);
}

#if defined(__ARM_FEATURE_CRYPTO)
#include <arm_neon.h>

void AES_CBC_decrypt_buffer(struct AES_ctx* ctx, uint8_t* buf, size_t length) {
    if (length == 0 || (length % AES_BLOCKLEN) != 0) return;

    // 💡 ARMv8 Crypto 指令硬件加速 (vaesdq_u8 / vaesimcq_u8)
    uint8x16_t rk[11];
    rk[10] = vld1q_u8(ctx->RoundKey + 160);
    for (int r = 9; r >= 1; --r) {
        rk[r] = vaesimcq_u8(vld1q_u8(ctx->RoundKey + r * 16));
    }
    rk[0] = vld1q_u8(ctx->RoundKey);

    uint8x16_t iv = vld1q_u8(ctx->Iv);

    for (size_t b = 0; b < length; b += AES_BLOCKLEN) {
        uint8x16_t cipher = vld1q_u8(buf + b);
        uint8x16_t s = cipher;

        s = vaesimcq_u8(vaesdq_u8(s, rk[10]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[9]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[8]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[7]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[6]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[5]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[4]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[3]));
        s = vaesimcq_u8(vaesdq_u8(s, rk[2]));
        s = vaesdq_u8(s, rk[1]);
        s = veorq_u8(s, rk[0]);

        uint8x16_t plain = veorq_u8(s, iv);
        vst1q_u8(buf + b, plain);
        iv = cipher;
    }

    vst1q_u8(ctx->Iv, iv);
}

#else

// 💡 32-bit T-Table 查表加速实现 (适用于不支持 ARM Crypto 硬件指令的架构与单元测试)
#define GETU32(pt) (((uint32_t)(pt)[0] << 24) ^ ((uint32_t)(pt)[1] << 16) ^ ((uint32_t)(pt)[2] <<  8) ^ ((uint32_t)(pt)[3]))
#define PUTU32(ct, st) { (ct)[0] = (uint8_t)((st) >> 24); (ct)[1] = (uint8_t)((st) >> 16); (ct)[2] = (uint8_t)((st) >>  8); (ct)[3] = (uint8_t)(st); }

static uint8_t xtime(uint8_t x) {
    return (uint8_t)((x << 1) ^ (((x >> 7) & 1) * 0x1b));
}

static uint8_t Multiply(uint8_t x, uint8_t y) {
    return (uint8_t)(((y & 1) * x) ^
            ((y >> 1 & 1) * xtime(x)) ^
            ((y >> 2 & 1) * xtime(xtime(x))) ^
            ((y >> 3 & 1) * xtime(xtime(xtime(x)))) ^
            ((y >> 4 & 1) * xtime(xtime(xtime(xtime(x))))));
}

static uint32_t Td0[256];
static uint32_t Td1[256];
static uint32_t Td2[256];
static uint32_t Td3[256];
static int tables_initialized = 0;

static void init_tables(void) {
    if (tables_initialized) return;
    for (int i = 0; i < 256; i++) {
        uint8_t s = rsbox[i];
        uint32_t a = Multiply(s, 0x0e);
        uint32_t b = Multiply(s, 0x09);
        uint32_t c = Multiply(s, 0x0d);
        uint32_t d = Multiply(s, 0x0b);
        uint32_t v = (a << 24) | (b << 16) | (c << 8) | d;
        Td0[i] = v;
        Td1[i] = (v >> 8) | (v << 24);
        Td2[i] = (v >> 16) | (v << 16);
        Td3[i] = (v >> 24) | (v << 8);
    }
    tables_initialized = 1;
}

static void InvCipher_TTable(const uint8_t in[16], uint8_t out[16], const uint32_t rk[44], const uint32_t eq_rk[44]) {
    uint32_t s0 = GETU32(in)      ^ rk[40];
    uint32_t s1 = GETU32(in + 4)  ^ rk[41];
    uint32_t s2 = GETU32(in + 8)  ^ rk[42];
    uint32_t s3 = GETU32(in + 12) ^ rk[43];

    uint32_t t0, t1, t2, t3;

    for (int r = 9; r >= 1; r--) {
        t0 = Td0[(s0 >> 24) & 0xff] ^ Td1[(s3 >> 16) & 0xff] ^ Td2[(s2 >> 8) & 0xff] ^ Td3[s1 & 0xff] ^ eq_rk[r * 4 + 0];
        t1 = Td0[(s1 >> 24) & 0xff] ^ Td1[(s0 >> 16) & 0xff] ^ Td2[(s3 >> 8) & 0xff] ^ Td3[s2 & 0xff] ^ eq_rk[r * 4 + 1];
        t2 = Td0[(s2 >> 24) & 0xff] ^ Td1[(s1 >> 16) & 0xff] ^ Td2[(s0 >> 8) & 0xff] ^ Td3[s3 & 0xff] ^ eq_rk[r * 4 + 2];
        t3 = Td0[(s3 >> 24) & 0xff] ^ Td1[(s2 >> 16) & 0xff] ^ Td2[(s1 >> 8) & 0xff] ^ Td3[s0 & 0xff] ^ eq_rk[r * 4 + 3];
        s0 = t0; s1 = t1; s2 = t2; s3 = t3;
    }

    uint8_t b0 = rsbox[(s0 >> 24) & 0xff] ^ (uint8_t)(rk[0] >> 24);
    uint8_t b1 = rsbox[(s3 >> 16) & 0xff] ^ (uint8_t)(rk[0] >> 16);
    uint8_t b2 = rsbox[(s2 >> 8) & 0xff]  ^ (uint8_t)(rk[0] >> 8);
    uint8_t b3 = rsbox[s1 & 0xff]         ^ (uint8_t)(rk[0]);

    uint8_t b4 = rsbox[(s1 >> 24) & 0xff] ^ (uint8_t)(rk[1] >> 24);
    uint8_t b5 = rsbox[(s0 >> 16) & 0xff] ^ (uint8_t)(rk[1] >> 16);
    uint8_t b6 = rsbox[(s3 >> 8) & 0xff]  ^ (uint8_t)(rk[1] >> 8);
    uint8_t b7 = rsbox[s2 & 0xff]         ^ (uint8_t)(rk[1]);

    uint8_t b8 = rsbox[(s2 >> 24) & 0xff] ^ (uint8_t)(rk[2] >> 24);
    uint8_t b9 = rsbox[(s1 >> 16) & 0xff] ^ (uint8_t)(rk[2] >> 16);
    uint8_t ba = rsbox[(s0 >> 8) & 0xff]  ^ (uint8_t)(rk[2] >> 8);
    uint8_t bb = rsbox[s3 & 0xff]         ^ (uint8_t)(rk[2]);

    uint8_t bc = rsbox[(s3 >> 24) & 0xff] ^ (uint8_t)(rk[3] >> 24);
    uint8_t bd = rsbox[(s2 >> 16) & 0xff] ^ (uint8_t)(rk[3] >> 16);
    uint8_t be = rsbox[(s1 >> 8) & 0xff]  ^ (uint8_t)(rk[3] >> 8);
    uint8_t bf = rsbox[s0 & 0xff]         ^ (uint8_t)(rk[3]);

    out[0] = b0; out[1] = b1; out[2] = b2; out[3] = b3;
    out[4] = b4; out[5] = b5; out[6] = b6; out[7] = b7;
    out[8] = b8; out[9] = b9; out[10] = ba; out[11] = bb;
    out[12] = bc; out[13] = bd; out[14] = be; out[15] = bf;
}

void AES_CBC_decrypt_buffer(struct AES_ctx* ctx, uint8_t* buf, size_t length) {
    if (length == 0 || (length % AES_BLOCKLEN) != 0) return;

    init_tables();

    uint32_t rk[44];
    for (int i = 0; i < 44; i++) {
        rk[i] = GETU32(ctx->RoundKey + i * 4);
    }

    uint32_t eq_rk[44];
    eq_rk[40] = rk[40]; eq_rk[41] = rk[41]; eq_rk[42] = rk[42]; eq_rk[43] = rk[43];
    eq_rk[0]  = rk[0];  eq_rk[1]  = rk[1];  eq_rk[2]  = rk[2];  eq_rk[3]  = rk[3];

    for (int r = 1; r < 10; r++) {
        for (int c = 0; c < 4; c++) {
            uint32_t k = rk[r * 4 + c];
            uint8_t k0 = (uint8_t)(k >> 24);
            uint8_t k1 = (uint8_t)(k >> 16);
            uint8_t k2 = (uint8_t)(k >> 8);
            uint8_t k3 = (uint8_t)(k);
            eq_rk[r * 4 + c] = 
                Td0[sbox[k0]] ^ Td1[sbox[k1]] ^ Td2[sbox[k2]] ^ Td3[sbox[k3]];
        }
    }

    size_t i;
    uint8_t storeNextIv[AES_BLOCKLEN];
    for (i = 0; i < length; i += AES_BLOCKLEN) {
        memcpy(storeNextIv, buf + i, AES_BLOCKLEN);
        InvCipher_TTable(buf + i, buf + i, rk, eq_rk);
        for (uint8_t b = 0; b < AES_BLOCKLEN; ++b) {
            buf[i + b] ^= ctx->Iv[b];
        }
        memcpy(ctx->Iv, storeNextIv, AES_BLOCKLEN);
    }
}

#endif

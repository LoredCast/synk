#include "synk.h"

#include <stdio.h>
#include <string.h>

typedef struct {
    uint32_t state[8];
    uint64_t bitlen;
    uint8_t  data[64];
    size_t   datalen;
} Sha256Ctx;

static const uint32_t K[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
    0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
    0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
    0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
    0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
    0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
};

static inline uint32_t rotr32(uint32_t x, uint32_t n) {
    return (x >> n) | (x << (32u - n));
}

static void sha256_transform(Sha256Ctx *ctx, const uint8_t block[64]) {
    uint32_t m[64];
    for (int i = 0; i < 16; ++i) {
        int j = i * 4;
        m[i] = ((uint32_t)block[j] << 24)
             | ((uint32_t)block[j + 1] << 16)
             | ((uint32_t)block[j + 2] << 8)
             | ((uint32_t)block[j + 3]);
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = rotr32(m[i - 15], 7) ^ rotr32(m[i - 15], 18) ^ (m[i - 15] >> 3);
        uint32_t s1 = rotr32(m[i - 2], 17) ^ rotr32(m[i - 2], 19) ^ (m[i - 2] >> 10);
        m[i] = m[i - 16] + s0 + m[i - 7] + s1;
    }

    uint32_t a = ctx->state[0];
    uint32_t b = ctx->state[1];
    uint32_t c = ctx->state[2];
    uint32_t d = ctx->state[3];
    uint32_t e = ctx->state[4];
    uint32_t f = ctx->state[5];
    uint32_t g = ctx->state[6];
    uint32_t h = ctx->state[7];

    for (int i = 0; i < 64; ++i) {
        uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + S1 + ch + K[i] + m[i];
        uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    ctx->state[5] += f;
    ctx->state[6] += g;
    ctx->state[7] += h;
}

static void sha256_init(Sha256Ctx *ctx) {
    ctx->datalen = 0;
    ctx->bitlen = 0;
    ctx->state[0] = 0x6a09e667u;
    ctx->state[1] = 0xbb67ae85u;
    ctx->state[2] = 0x3c6ef372u;
    ctx->state[3] = 0xa54ff53au;
    ctx->state[4] = 0x510e527fu;
    ctx->state[5] = 0x9b05688cu;
    ctx->state[6] = 0x1f83d9abu;
    ctx->state[7] = 0x5be0cd19u;
}

static void sha256_update(Sha256Ctx *ctx, const uint8_t *data, size_t len) {
    size_t i = 0;
    if (ctx->datalen > 0) {
        size_t need = 64 - ctx->datalen;
        size_t take = len < need ? len : need;
        memcpy(ctx->data + ctx->datalen, data, take);
        ctx->datalen += take;
        i += take;
        if (ctx->datalen == 64) {
            sha256_transform(ctx, ctx->data);
            ctx->bitlen += 512;
            ctx->datalen = 0;
        }
    }
    while (i + 64 <= len) {
        sha256_transform(ctx, data + i);
        ctx->bitlen += 512;
        i += 64;
    }
    if (i < len) {
        ctx->datalen = len - i;
        memcpy(ctx->data, data + i, ctx->datalen);
    }
}

static void sha256_final_hex(Sha256Ctx *ctx, char out_hex[SYNK_SHA256_HEX_LEN]) {
    size_t i = ctx->datalen;
    ctx->bitlen += (uint64_t)ctx->datalen * 8u;

    ctx->data[i++] = 0x80u;
    if (i > 56) {
        while (i < 64) ctx->data[i++] = 0;
        sha256_transform(ctx, ctx->data);
        memset(ctx->data, 0, 56);
    } else {
        while (i < 56) ctx->data[i++] = 0;
    }

    for (int j = 0; j < 8; ++j) {
        ctx->data[63 - j] = (uint8_t)(ctx->bitlen >> (j * 8));
    }
    sha256_transform(ctx, ctx->data);

    static const char HEX[] = "0123456789abcdef";
    for (int j = 0; j < 8; ++j) {
        uint32_t v = ctx->state[j];
        out_hex[j * 8 + 0] = HEX[(v >> 28) & 0xFu];
        out_hex[j * 8 + 1] = HEX[(v >> 24) & 0xFu];
        out_hex[j * 8 + 2] = HEX[(v >> 20) & 0xFu];
        out_hex[j * 8 + 3] = HEX[(v >> 16) & 0xFu];
        out_hex[j * 8 + 4] = HEX[(v >> 12) & 0xFu];
        out_hex[j * 8 + 5] = HEX[(v >> 8)  & 0xFu];
        out_hex[j * 8 + 6] = HEX[(v >> 4)  & 0xFu];
        out_hex[j * 8 + 7] = HEX[v & 0xFu];
    }
    out_hex[64] = '\0';
}

void synk_sha256_bytes(const uint8_t *data, size_t len, char out_hex[SYNK_SHA256_HEX_LEN]) {
    Sha256Ctx ctx;
    sha256_init(&ctx);
    if (data && len > 0) {
        sha256_update(&ctx, data, len);
    }
    sha256_final_hex(&ctx, out_hex);
}

int synk_sha256_file(const char *path, char out_hex[SYNK_SHA256_HEX_LEN]) {
    if (!path || !out_hex) return -1;
    FILE *fp = fopen(path, "rb");
    if (!fp) {
        out_hex[0] = '\0';
        return -1;
    }
    Sha256Ctx ctx;
    sha256_init(&ctx);
    uint8_t buf[65536];
    for (;;) {
        size_t n = fread(buf, 1, sizeof(buf), fp);
        if (n > 0) {
            sha256_update(&ctx, buf, n);
        }
        if (n < sizeof(buf)) {
            if (ferror(fp)) {
                fclose(fp);
                out_hex[0] = '\0';
                return -1;
            }
            break;
        }
    }
    fclose(fp);
    sha256_final_hex(&ctx, out_hex);
    return 0;
}

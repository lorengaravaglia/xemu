/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Semantic check of the com_f32/com_f64 sequence emitted by the AArch64 TCG
 * backend (tcg/aarch64/tcg-target.c.inc).
 *
 * scripts/aarch64-fp-encoding-test.py checks that the emitted instruction
 * bytes are what we intend.  It cannot check that the chosen conditions mean
 * the right thing.  com must synthesise an x86 EFLAGS layout -- CF at bit 0,
 * PF at bit 2, ZF at bit 6, with COMISD semantics -- out of AArch64's NZCV,
 * because gen_fcom() (target/i386/ops_fpu.h) masks the result with 0x45 and
 * shifts it into the x87 status word.  The mapping is not obvious: ZF is
 * "equal or unordered", which is not expressible as any single AArch64
 * condition.
 *
 * This runs the exact sequence on real hardware and compares against the
 * table in gen_fcom().  Requires an AArch64 host or device.
 *
 * Build and run on an Android device:
 *   $NDK/aarch64-linux-android28-clang -O2 -static \
 *       scripts/aarch64-fp-com-semantics.c -o /tmp/comtest
 *   adb push /tmp/comtest /data/local/tmp/ && \
 *       adb shell "chmod 755 /data/local/tmp/comtest && /data/local/tmp/comtest"
 */
#include <stdint.h>
#include <stdio.h>

static uint64_t com_d(double a, double b)
{
    uint64_t out;
    __asm__ volatile(
        "fcmp   %d[a], %d[b]\n\t"
        "cset   %[out], lt\n\t"          /* CF = less or unordered  */
        "cset   x16, vs\n\t"             /* unordered               */
        "cset   x17, eq\n\t"             /* equal                   */
        "orr    %[out], %[out], x16, lsl #2\n\t"   /* PF -> bit 2   */
        "orr    x17, x17, x16\n\t"                 /* ZF = eq | uo  */
        "orr    %[out], %[out], x17, lsl #6\n\t"   /* ZF -> bit 6   */
        : [out] "=&r"(out)
        : [a] "w"(a), [b] "w"(b)
        : "x16", "x17", "cc");
    return out;
}

static uint64_t com_s(float a, float b)
{
    uint64_t out;
    __asm__ volatile(
        "fcmp   %s[a], %s[b]\n\t"
        "cset   %[out], lt\n\t"
        "cset   x16, vs\n\t"
        "cset   x17, eq\n\t"
        "orr    %[out], %[out], x16, lsl #2\n\t"
        "orr    x17, x17, x16\n\t"
        "orr    %[out], %[out], x17, lsl #6\n\t"
        : [out] "=&r"(out)
        : [a] "w"(a), [b] "w"(b)
        : "x16", "x17", "cc");
    return out;
}

int main(void)
{
    double dnan = __builtin_nan("");
    float fnan = __builtin_nanf("");
    int fail = 0;

    /* From gen_fcom():  C3 C2 C0 = ZF PF CF = bits 6, 2, 0
     *   a > b  -> 0x00,  a < b -> 0x01,  a == b -> 0x40,  unordered -> 0x45 */
    struct { const char *name; double a, b; uint64_t want; } td[] = {
        { "d: a > b",      2.0,  1.0,  0x00 },
        { "d: a < b",      1.0,  2.0,  0x01 },
        { "d: a == b",     1.0,  1.0,  0x40 },
        { "d: NaN, 1.0",   0.0,  1.0,  0x45 },   /* a patched below */
        { "d: 1.0, NaN",   1.0,  0.0,  0x45 },   /* b patched below */
        { "d: -1 < 1",    -1.0,  1.0,  0x01 },
        { "d: 0 == -0",    0.0, -0.0,  0x40 },
        { "d: inf > 1",  1e308*10, 1.0, 0x00 },
    };
    td[3].a = dnan;
    td[4].b = dnan;

    for (unsigned i = 0; i < sizeof(td) / sizeof(td[0]); i++) {
        uint64_t got = com_d(td[i].a, td[i].b);
        int ok = (got == td[i].want);
        printf("%-14s want 0x%02llx  got 0x%02llx  %s\n", td[i].name,
               (unsigned long long)td[i].want, (unsigned long long)got,
               ok ? "OK" : "FAIL");
        fail |= !ok;
    }

    struct { const char *name; float a, b; uint64_t want; } ts[] = {
        { "s: a > b",   2.0f, 1.0f, 0x00 },
        { "s: a < b",   1.0f, 2.0f, 0x01 },
        { "s: a == b",  1.0f, 1.0f, 0x40 },
        { "s: NaN",     0.0f, 1.0f, 0x45 },
    };
    ts[3].a = fnan;

    for (unsigned i = 0; i < sizeof(ts) / sizeof(ts[0]); i++) {
        uint64_t got = com_s(ts[i].a, ts[i].b);
        int ok = (got == ts[i].want);
        printf("%-14s want 0x%02llx  got 0x%02llx  %s\n", ts[i].name,
               (unsigned long long)ts[i].want, (unsigned long long)got,
               ok ? "OK" : "FAIL");
        fail |= !ok;
    }

    printf("\n%s\n", fail ? "FAILED" : "ALL OK");
    return fail;
}

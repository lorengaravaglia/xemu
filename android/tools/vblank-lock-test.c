/*
 * Off-device test of the vblank lock maths (android/app/src/main/cpp/
 * vblank_lock.h): synthetic panel vsync streams, including dropped callbacks
 * and refresh-rate switches the Thor itself cannot produce.
 *
 *   cc -O2 -I android/app/src/main/cpp android/tools/vblank-lock-test.c \
 *      -o /tmp/vblank-lock-test && /tmp/vblank-lock-test
 */
#include <stdio.h>
#include <stdlib.h>
#include "vblank_lock.h"

#define NOMINAL 16666666LL
#define PHASE   1000000LL

typedef struct { const char *name; double hz1, hz2; int64_t switch_at_ms; int drop_every; } Case;

/* Vsync timestamp i of a panel at hz1 until switch_at, then hz2. */
static int64_t vsync_time(const Case *c, int64_t i, int64_t *period_out)
{
    int64_t p1 = (int64_t)(1e9 / c->hz1), p2 = (int64_t)(1e9 / c->hz2);
    int64_t n1 = c->switch_at_ms * 1000000LL / p1;
    if (!c->switch_at_ms || i <= n1) { *period_out = p1; return i * p1; }
    *period_out = p2;
    return n1 * p1 + (i - n1) * p2;
}

static int run(const Case *c)
{
    VblankTracker t = { 0 };
    int64_t vi = 1, prev = 0, now = 0;
    int off_grid = 0, locked = 0, bad_step = 0, total = 0;
    int64_t settle_ns = (c->switch_at_ms + 1500) * 1000000LL;

    for (int f = 0; f < 600; f++) {               /* 10 s of emulated vblanks */
        int64_t period;
        /* Deliver every vsync callback up to now (some dropped). */
        while (vsync_time(c, vi, &period) <= now) {
            if (!c->drop_every || vi % c->drop_every) {
                vblank_track_vsync(&t, vsync_time(c, vi, &period));
            }
            vi++;
        }
        int n = 0;
        int64_t next = vblank_next_deadline(prev ? prev : now, NOMINAL, PHASE,
                                            t.last_ns, t.period_ns, &n);
        if (!next) {
            next = (prev ? prev : now) + NOMINAL;  /* free-run fallback */
        }
        if (now >= settle_ns) {
            total++;
            if (n) {
                locked++;
                /* On the grid: next - PHASE must be (near) a real vsync. */
                int64_t p, lo = 0, hi = vi + 400, best = INT64_MAX;
                for (int64_t j = lo; j < hi; j++) {
                    int64_t d = vblank_abs64(vsync_time(c, j, &p) - (next - PHASE));
                    if (d < best) best = d;
                }
                if (best > 200000) off_grid++;        /* > 0.2 ms off a vsync */
                if (prev) {
                    int64_t step = next - prev, pp;
                    vsync_time(c, vi, &pp);
                    int64_t vs = (step + pp / 2) / pp;
                    if (vs != n) bad_step++;
                }
            }
        }
        prev = next;
        now = next;
    }
    /* Lockable iff some whole number of vsyncs is within 1 ms of 16.67 ms. */
    int64_t pf = (int64_t)(1e9 / c->hz2);
    int64_t nn = (NOMINAL + pf / 2) / pf;
    int expect_lock = nn >= 1 && vblank_abs64(nn * pf - NOMINAL) < 1000000;
    int ok = (expect_lock ? (locked == total && off_grid == 0 && bad_step == 0) : locked == 0);
    printf("%-34s locked %3d/%3d  off-grid %d  wrong-step %d  -> %s\n",
           c->name, locked, total, off_grid, bad_step, ok ? "PASS" : "FAIL");
    return ok;
}

int main(void)
{
    Case cases[] = {
        { "60.000 Hz",                      60.0,   60.0,   0,    0 },
        { "59.753 Hz (Thor)",               59.753, 59.753, 0,    0 },
        { "59.94 Hz",                       59.94,  59.94,  0,    0 },
        { "120 Hz (every 2nd vsync)",       120.0,  120.0,  0,    0 },
        { "60 Hz, every 7th callback lost", 60.0,   60.0,   0,    7 },
        { "120 -> 60 Hz at 3 s",            120.0,  60.0,   3000, 0 },
        { "60 -> 120 Hz at 3 s",            60.0,   120.0,  3000, 0 },
        { "90 Hz (no lock expected)",       90.0,   90.0,   0,    0 },
        { "50 Hz (no lock expected)",       50.0,   50.0,   0,    0 },
    };
    int pass = 0, n = sizeof(cases) / sizeof(cases[0]);
    for (int i = 0; i < n; i++) {
        pass += run(&cases[i]);
    }
    printf("%d/%d passed\n", pass, n);
    return pass == n ? 0 : 1;
}

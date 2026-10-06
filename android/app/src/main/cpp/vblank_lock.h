/*
 * Vblank lock maths (FINDINGS BD), kept pure so it can be tested off-device:
 * android/tools/vblank-lock-test.c feeds it synthetic vsync streams.
 *
 *  - vblank_track_vsync(): the panel's vsync period, from successive vsync
 *    timestamps.  A lone odd interval (a skipped callback) is ignored, but a
 *    new interval that persists -- the panel changed refresh rate -- replaces
 *    the estimate, so 120 -> 60 Hz (or back) is followed.
 *  - vblank_next_deadline(): the next emulated vblank on the panel's vsync
 *    grid.  The emulated vblank is ~60 Hz, so on a panel at an integer
 *    multiple of that (120 Hz, 180 Hz...) it lands on every n-th vsync.
 *    Panels with no such multiple (50 Hz, 90 Hz) are not locked.
 */
#ifndef VBLANK_LOCK_H
#define VBLANK_LOCK_H

#include <stdbool.h>
#include <stdint.h>

typedef struct VblankTracker {
    int64_t last_ns;        /* latest vsync timestamp */
    int64_t period_ns;      /* smoothed vsync period, 0 until known */
    int64_t cand_ns;        /* a different interval seen repeatedly... */
    int cand_count;         /* ...and how many times in a row */
} VblankTracker;

/* Consecutive matching intervals needed to accept a new refresh rate. */
#define VBLANK_RATE_CHANGE_RUN 8

static inline int64_t vblank_abs64(int64_t v) { return v < 0 ? -v : v; }

static inline void vblank_track_vsync(VblankTracker *t, int64_t vsync_ns)
{
    int64_t prev = t->last_ns;
    t->last_ns = vsync_ns;
    if (!prev) {
        return;
    }
    int64_t d = vsync_ns - prev;
    if (d < 2000000 || d > 100000000) {             /* not a frame interval */
        return;
    }
    int64_t p = t->period_ns;
    if (!p) {
        t->period_ns = d;
        return;
    }
    if (d > p * 3 / 4 && d < p * 5 / 4) {
        /* One period: fold into a slow average, forget any candidate. */
        t->period_ns = p + (d - p) / 16;
        t->cand_count = 0;
        return;
    }
    /* Off by more than a quarter: a skipped callback, or a new rate.  Only a
     * run of matching intervals means a new rate. */
    if (t->cand_count && vblank_abs64(d - t->cand_ns) < t->cand_ns / 10) {
        t->cand_ns += (d - t->cand_ns) / 4;
        if (++t->cand_count >= VBLANK_RATE_CHANGE_RUN) {
            t->period_ns = t->cand_ns;
            t->cand_count = 0;
        }
    } else {
        t->cand_ns = d;
        t->cand_count = 1;
    }
}

/*
 * The next emulated vblank after [prev], or 0 when the panel cannot be locked
 * to (caller free-runs).  [nominal_ns] is the emulated vblank interval
 * (16666666), [phase_ns] the offset after a vsync, [vsync_last]/[vsync_period]
 * the tracker's state.  Every step is a whole number of vsyncs: n vsyncs where
 * n * period is within 1 ms of the nominal interval.
 */
static inline int64_t vblank_next_deadline(int64_t prev, int64_t nominal_ns,
                                           int64_t phase_ns, int64_t vsync_last,
                                           int64_t vsync_period, int *n_out)
{
    if (!vsync_last || vsync_period <= 0) {
        return 0;
    }
    int64_t n = (nominal_ns + vsync_period / 2) / vsync_period;
    if (n < 1 || vblank_abs64(n * vsync_period - nominal_ns) >= 1000000) {
        return 0;
    }
    int64_t step = n * vsync_period;
    int64_t base = vsync_last + phase_ns;
    int64_t target = prev + step;
    int64_t off = target - base;
    /* Nearest vsync grid line to the nominal target (floor-safe for <0). */
    int64_t k = off >= 0 ? (off + vsync_period / 2) / vsync_period
                         : -((-off + vsync_period / 2) / vsync_period);
    int64_t next = base + k * vsync_period;
    while (next <= prev + step / 2) {               /* never early */
        next += vsync_period;
    }
    if (n_out) {
        *n_out = (int)n;
    }
    return next;
}

#endif

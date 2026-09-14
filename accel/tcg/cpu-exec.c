/*
 *  emulator main execution loop
 *
 *  Copyright (c) 2003-2005 Fabrice Bellard
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see <http://www.gnu.org/licenses/>.
 */

#include "qemu/osdep.h"
#if defined(__ANDROID__) || defined(ANDROID)
#include <sys/syscall.h>
#include <unistd.h>
#endif
#include "qemu/qemu-print.h"
#include "qapi/error.h"
#include "qapi/type-helpers.h"
#include "hw/core/cpu.h"
#include "accel/tcg/cpu-ops.h"
#include "accel/tcg/helper-retaddr.h"
#include "trace.h"
#include "disas/disas.h"
#include "exec/cpu-common.h"
#include "exec/cpu-interrupt.h"
#include "exec/page-protection.h"
#include "exec/mmap-lock.h"
#include "exec/translation-block.h"
#include "tcg/tcg.h"
#include "qemu/atomic.h"
#include "qemu/rcu.h"
#include "exec/log.h"
#include "qemu/main-loop.h"
#include "exec/icount.h"
#include "exec/replay-core.h"
#include "system/tcg.h"
#include "exec/helper-proto-common.h"
#include "tcg-accel-ops.h"
#include "tb-jmp-cache.h"
#include "tb-hash.h"
#include "tb-code-hash.h"
#include "tb-context.h"
#include "tb-internal.h"
#include "internal-common.h"

/* -icount align implementation. */

typedef struct SyncClocks {
    int64_t diff_clk;
    int64_t last_cpu_icount;
    int64_t realtime_clock;
} SyncClocks;

#if !defined(CONFIG_USER_ONLY)
/* Allow the guest to have a max 3ms advance.
 * The difference between the 2 clocks could therefore
 * oscillate around 0.
 */
#define VM_CLOCK_ADVANCE 3000000
#define THRESHOLD_REDUCE 1.5
#define MAX_DELAY_PRINT_RATE 2000000000LL
#define MAX_NB_PRINTS 100

int64_t max_delay;
int64_t max_advance;

static void align_clocks(SyncClocks *sc, CPUState *cpu)
{
    int64_t cpu_icount;

    if (!icount_align_option) {
        return;
    }

    cpu_icount = cpu->icount_extra + cpu->neg.icount_decr.u16.low;
    sc->diff_clk += icount_to_ns(sc->last_cpu_icount - cpu_icount);
    sc->last_cpu_icount = cpu_icount;

    if (sc->diff_clk > VM_CLOCK_ADVANCE) {
#ifndef _WIN32
        struct timespec sleep_delay, rem_delay;
        sleep_delay.tv_sec = sc->diff_clk / 1000000000LL;
        sleep_delay.tv_nsec = sc->diff_clk % 1000000000LL;
        if (nanosleep(&sleep_delay, &rem_delay) < 0) {
            sc->diff_clk = rem_delay.tv_sec * 1000000000LL + rem_delay.tv_nsec;
        } else {
            sc->diff_clk = 0;
        }
#else
        Sleep(sc->diff_clk / SCALE_MS);
        sc->diff_clk = 0;
#endif
    }
}

static void print_delay(const SyncClocks *sc)
{
    static float threshold_delay;
    static int64_t last_realtime_clock;
    static int nb_prints;

    if (icount_align_option &&
        sc->realtime_clock - last_realtime_clock >= MAX_DELAY_PRINT_RATE &&
        nb_prints < MAX_NB_PRINTS) {
        if ((-sc->diff_clk / (float)1000000000LL > threshold_delay) ||
            (-sc->diff_clk / (float)1000000000LL <
             (threshold_delay - THRESHOLD_REDUCE))) {
            threshold_delay = (-sc->diff_clk / 1000000000LL) + 1;
            qemu_printf("Warning: The guest is now late by %.1f to %.1f seconds\n",
                        threshold_delay - 1,
                        threshold_delay);
            nb_prints++;
            last_realtime_clock = sc->realtime_clock;
        }
    }
}

static void init_delay_params(SyncClocks *sc, CPUState *cpu)
{
    if (!icount_align_option) {
        return;
    }
    sc->realtime_clock = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL_RT);
    sc->diff_clk = qemu_clock_get_ns(QEMU_CLOCK_VIRTUAL) - sc->realtime_clock;
    sc->last_cpu_icount
        = cpu->icount_extra + cpu->neg.icount_decr.u16.low;
    if (sc->diff_clk < max_delay) {
        max_delay = sc->diff_clk;
    }
    if (sc->diff_clk > max_advance) {
        max_advance = sc->diff_clk;
    }

    /* Print every 2s max if the guest is late. We limit the number
       of printed messages to NB_PRINT_MAX(currently 100) */
    print_delay(sc);
}
#else
static void align_clocks(SyncClocks *sc, const CPUState *cpu)
{
}

static void init_delay_params(SyncClocks *sc, const CPUState *cpu)
{
}
#endif /* CONFIG USER ONLY */

struct tb_desc {
    TCGTBCPUState s;
    CPUArchState *env;
    tb_page_addr_t page_addr0;
};

#if defined(__ANDROID__) || defined(ANDROID)
/*
 * Translation blocks executed, for the deterministic benchmark.
 *
 * Wall time alone cannot tell "the host got faster" from "the guest did less
 * work".  Comparing this count between runs answers that: if two runs execute
 * the same number of TBs, they did the same work and the wall times are
 * comparable.  vCPU-thread only, so no atomics.
 */
unsigned long long xemu_tb_exec_count;
/* Guest instructions executed (sum of tb->icount over executed TBs).  Slightly
 * optimistic when a TB exits early, but stable enough to compare runs. */
unsigned long long xemu_guest_insn_count;
/* Thread id of the vCPU thread, so the benchmark can read its CPU time. */
int xemu_vcpu_tid;
#endif

#if defined(__ANDROID__) || defined(ANDROID)
/*
 * Spin-wait elision.
 *
 * Halo busy-waits for a 64-bit tick counter to reach a target -- measured at
 * ~23% of generated-code time, at guest 0x000bb0d4-0x000bb0f2.  The guest is
 * waiting for WALL-CLOCK TIME to pass, and that counter is advanced by a timer
 * interrupt, so sleeping until the next interrupt is a faithful emulation of
 * the wait rather than a shortcut: the guest cannot observe the difference,
 * and the host stops burning a core at 90+ degrees to accomplish nothing.
 *
 * The loop is two chained blocks, so the dispatcher never sees it.  TBs whose
 * PC falls in the configured range are therefore translated unchained, which
 * makes each iteration visible here; after enough consecutive iterations with
 * no other code running, the thread sleeps briefly.  Any block outside the
 * range resets the count, so real work is never delayed.
 *
 *   debug.xemu.spin_lo / spin_hi   guest PC range (0 disables)
 *   debug.xemu.spin_us            sleep per burst, default 200us
 *   debug.xemu.spin_thresh        consecutive iterations before sleeping
 *
 * NOTE the range is game-specific.  Proving the value comes first; detecting
 * spins dynamically is the follow-up if it is worth having.
 */
uint32_t g_spin_lo, g_spin_hi;
int g_spin_us = 200, g_spin_thresh = 64;
unsigned long long xemu_spin_hits, xemu_spin_sleeps, xemu_spin_us_total;

/*
 * Automatic spin detection.
 *
 * The loop we care about is two chained blocks, so it never reaches the
 * dispatcher and cannot be observed from here directly.  Rather than unchain
 * everything -- which costs about 50% -- the detector opens a brief probe
 * window every few seconds: during it all blocks return to the dispatcher, so
 * a few thousand iterations are visible, and then chaining resumes.
 *
 * Inside a probe, a spin shows up as the same PC recurring with the guest's
 * register state unchanged.  That second condition is what makes this safe to
 * run on any game: a strlen or memcpy loop is also a tight backward branch
 * over pure loads, but it advances a pointer every iteration, so its hash
 * changes and it is never mistaken for a wait.
 */
/*
 * Off by default.  The detector is correct -- it finds real wait loops and
 * never fired on real work -- but on the save-state benchmark the only loop
 * present is the one the game idles in, which runs 60-190 iterations a frame,
 * and committing it measured 0.2-0.6 ms/frame WORSE over six A/B pairs: its
 * blocks stay unchained for the whole session and the sleep is charged to the
 * frame.  Enable with debug.xemu.spin_auto=1 on a workload that actually
 * spins.
 */
int g_spin_auto;                     /* debug.xemu.spin_auto=1 enables */

/*
 * debug.xemu.nochain=1 translates everything unchained, so every block returns
 * to the dispatcher.  This exists to make xemu_guest_insn_count exact: that
 * counter is incremented after tb_add_jump, so chained blocks never reach it
 * and it normally reports only chain breaks (~300x low).  A nochain run is the
 * only way to get a true guest-instruction count, which is what turns host
 * instructions per guest instruction into a real number rather than an
 * estimate.  It is a measurement mode, not an optimisation -- it costs ~50%.
 */
int g_nochain;
unsigned long long xemu_spin_probes, xemu_spin_found;

/*
 * The confirmed loop is a SET of blocks, not a PC window.  Halo's wait calls a
 * helper, so it straddles two regions 0x11e000 apart (0x1810f0/0x18111b and
 * 0x63482/0x63492); any window around one half excludes the other, the
 * consecutive-iteration counter reset on every call, and the elision never
 * armed.  Matching exact block addresses removes the guesswork.
 */
#define SPIN_SET_MAX 16
/*
 * How many consecutive probes may find nothing before the detector stops.
 * Six was too few -- a probe only sees 40 ms, and the loop a game idles in can
 * easily not run during the handful of windows that follow start-up, so the
 * detector gave up before it had looked anywhere interesting.  Fifteen bounds
 * the one-time cost at roughly two minutes of occasional probing.
 */
#define SPIN_GIVE_UP 15
static vaddr spin_set[SPIN_SET_MAX];
static int spin_set_n;
static int spin_probes_fruitless;
int xemu_spin_best_run;   /* longest identical-state run seen, for diagnosis */
static int spin_probe_left;          /* dispatcher visits left in this probe */
static int64_t spin_next_probe_ms;
static int64_t spin_probe_end_ms;
/*
 * Per-block memory, not a single candidate.  A wait loop is usually several
 * blocks -- the one in Halo alternates between two -- so "the same PC twice in
 * a row" never holds, and the first version of this detector found nothing for
 * exactly that reason.  Each block instead remembers the register state it was
 * last entered with, and a run only grows when that same block is re-entered
 * in an identical state.
 */
/*
 * The probe must span a whole guest frame.  A visit budget alone was the
 * wrong shape: 4000 unchained visits is roughly half a millisecond of wall
 * time, while the wait loop runs at the END of a 33 ms frame, so the window
 * almost never overlapped it and the score never climbed past 10.  Bound it
 * by time instead, with a visit cap only as a backstop.
 */
#define SPIN_PROBE_VISITS   400000 /* backstop only */
#define SPIN_PROBE_MS       40     /* must span a whole guest frame */
#define SPIN_PROBE_EVERY_MS 8000   /* keep probing; games have several waits */
#define SPIN_CONFIRM_RUN    32     /* re-entries in identical state to confirm */
#define SPIN_WINDOW         0x80   /* bytes either side of the confirmed block */
/* 16 slots was far too few: a probe sees thousands of distinct PCs and the
 * wait loop's entries were evicted by collisions long before they could
 * accumulate.  This is a few KB and removes the eviction problem. */
#define SPIN_SLOTS 1024
static struct { vaddr pc; uint64_t hash; int run; int visits; } spin_seen[SPIN_SLOTS];
static unsigned spin_probe_visits;  /* total, to turn visits into a share */

static void xemu_spin_commit(void);
bool xemu_spin_member(vaddr pc);

static void xemu_spin_probe(vaddr pc)
{
    extern uint64_t xemu_guest_reg_hash(void);
    unsigned i;
    uint64_t h;

    if (!g_spin_auto || g_spin_lo) {
        return;                      /* disabled, or overridden by property */
    }
    /*
     * Probing is not free: it unchains every block for the duration, and an
     * A/B put the recurring cost at about 1% of frame time.  A game exposes
     * its wait loops in the first few seconds, so stop once several probes in
     * a row have turned up nothing new and keep only the membership test.
     */
    if (spin_set_n >= SPIN_SET_MAX || spin_probes_fruitless >= SPIN_GIVE_UP) {
        return;
    }

    if (spin_probe_left <= 0) {
        int64_t now = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);

        if (now < spin_next_probe_ms) {
            return;
        }
        spin_next_probe_ms = now + SPIN_PROBE_EVERY_MS;
        spin_probe_end_ms = now + SPIN_PROBE_MS;
        spin_probe_left = SPIN_PROBE_VISITS;
        memset(spin_seen, 0, sizeof(spin_seen));
        spin_probe_visits = 0;
        xemu_spin_probes++;
    }
    if (--spin_probe_left <= 0 ||
        qemu_clock_get_ms(QEMU_CLOCK_REALTIME) > spin_probe_end_ms) {
        spin_probe_left = 0;
        xemu_spin_commit();
        return;
    }

    h = xemu_guest_reg_hash();
    i = (unsigned)((pc >> 4) * 2654435761u) & (SPIN_SLOTS - 1);

    /*
     * Score rather than a strict run.  Requiring N identical re-entries in a
     * row was too strict: this loop polls a counter, so the loaded value --
     * and the flags derived from it -- change whenever the timer ticks, and
     * the longest identical run measured was 9 against a threshold of 32.
     *
     * Scoring +1 when a block is re-entered in a state it was last in, and
     * -1 when it is not, still separates the two cases cleanly.  A wait loop
     * repeats far more often than it changes and climbs steadily; a strlen or
     * memcpy advances a pointer every iteration, never repeats a state, and
     * can never accumulate.
     */
    spin_probe_visits++;

    if (spin_seen[i].pc != pc) {
        spin_seen[i].pc = pc;
        spin_seen[i].hash = h;
        spin_seen[i].run = 0;
        spin_seen[i].visits = 1;
        return;
    }
    spin_seen[i].visits++;

    if (spin_seen[i].hash != h) {
        spin_seen[i].hash = h;
        if (spin_seen[i].run > 0) {
            spin_seen[i].run--;
        }
        return;
    }

    spin_seen[i].run++;
    if (spin_seen[i].run > xemu_spin_best_run) {
        xemu_spin_best_run = spin_seen[i].run;
    }
}

/*
 * Commit at the END of a probe, to the highest-scoring block rather than the
 * first to cross the threshold.  Taking the first pick locked onto a minor
 * repeater at 0x192cb3 -- 200 iterations a frame, no sleeps, no gain -- while
 * the loop that actually matters was never considered, because finding one
 * candidate stopped the search.
 */
static void xemu_spin_commit(void)
{
    unsigned covered = 0;
    int cand[SPIN_SET_MAX], n = 0, i, j, k;

    /*
     * A block belongs to a wait loop if it is re-entered in the state it was
     * last in on MOST of its visits (run*2 >= visits).  Real work fails that
     * by construction: a copy or transform loop advances a pointer every
     * iteration, so its state never repeats and its run stays at zero.
     */
    for (i = 0; i < SPIN_SLOTS; i++) {
        if (spin_seen[i].run < SPIN_CONFIRM_RUN ||
            spin_seen[i].run * 2 < spin_seen[i].visits) {
            continue;
        }
        for (j = 0; j < n; j++) {
            if (spin_seen[i].visits > spin_seen[cand[j]].visits) {
                break;
            }
        }
        if (j < SPIN_SET_MAX) {
            for (k = (n < SPIN_SET_MAX ? n : SPIN_SET_MAX - 1); k > j; k--) {
                cand[k] = cand[k - 1];
            }
            cand[j] = i;
            if (n < SPIN_SET_MAX) {
                n++;
            }
        }
    }
    for (j = 0; j < n; j++) {
        covered += spin_seen[cand[j]].visits;
    }

    /*
     * Require the set to dominate the window.  Probes that clip the edge of
     * the loop see one of its blocks for a few hundred visits; committing to
     * that gives a partial set which, again, never accumulates enough
     * consecutive iterations to sleep.  Waiting for a probe that lands inside
     * the loop costs a few seconds and yields the whole set at once.
     */
    if (n && spin_probe_visits) {
        fprintf(stderr, "spin_auto: probe %llu cand=%d cover=%u%%:",
                xemu_spin_probes, n, 100 * covered / spin_probe_visits);
        for (j = 0; j < n; j++) {
            fprintf(stderr, " 0x%" VADDR_PRIx "=%d/%d",
                    spin_seen[cand[j]].pc, spin_seen[cand[j]].run,
                    spin_seen[cand[j]].visits);
        }
        fprintf(stderr, "\n");
    }

    /*
     * 8%, not 25%.  Coverage is measured in dispatcher visits, but the loop
     * costs time: the gameplay wait is about a third of the frame's duration
     * and only an eighth of its blocks, because the code it is waiting on
     * executes far more instructions per block than the two the loop spins
     * through.  A quarter-of-the-window bar rejected exactly the loop worth
     * finding.
     */
    if (!n || !spin_probe_visits || covered * 100 < spin_probe_visits * 8) {
        spin_probes_fruitless++;
        return;
    }

    /*
     * Accumulate.  The first loop found here is the one the game idles in,
     * which is hot between frames but barely runs during combat; the loop that
     * costs real time in gameplay only shows up in a later probe.  Replacing
     * the set each time would keep trading one for the other, so add instead,
     * and only stop probing once the set is full.
     */
    {
        int added = 0;

        for (j = 0; j < n && spin_set_n < SPIN_SET_MAX; j++) {
            vaddr pc = spin_seen[cand[j]].pc;

            if (xemu_spin_member(pc)) {
                continue;
            }
            spin_set[spin_set_n++] = pc;
            added++;
        }
        if (!added) {
            spin_probes_fruitless++;
            return;               /* same loop as last time; nothing new */
        }
        spin_probes_fruitless = 0;
    }
    xemu_spin_found++;

    fprintf(stderr, "spin_auto: +wait loop, %d blocks, %u%% of probe (set now %d):",
            n, 100 * covered / spin_probe_visits, spin_set_n);
    for (j = 0; j < n; j++) {
        fprintf(stderr, " 0x%" VADDR_PRIx "(%d)",
                spin_seen[cand[j]].pc, spin_seen[cand[j]].visits);
    }
    fprintf(stderr, "\n");
}

/* Is this block part of a confirmed wait loop (or the manual range)? */
bool xemu_spin_member(vaddr pc)
{
    int i;

    if (g_spin_lo) {
        return pc >= g_spin_lo && pc <= g_spin_hi;
    }
    if (!g_spin_auto) {
        return false;     /* keep the learned set, but stop acting on it */
    }
    for (i = 0; i < spin_set_n; i++) {
        if (spin_set[i] == pc) {
            return true;
        }
    }
    return false;
}

static void xemu_spin_maybe_sleep(vaddr pc)
{
    static unsigned consec;

    if (!xemu_spin_member(pc)) {
        consec = 0;
        return;
    }

    xemu_spin_hits++;
    if (++consec < (unsigned)g_spin_thresh) {
        return;
    }
    consec = 0;
    {
        struct timespec ts = { 0, g_spin_us * 1000L };

        nanosleep(&ts, NULL);
        xemu_spin_sleeps++;
        xemu_spin_us_total += g_spin_us;
    }
}
#endif

static bool tb_lookup_cmp(const void *p, const void *d)
{
    const TranslationBlock *tb = p;
    const struct tb_desc *desc = d;

    if ((tb_cflags(tb) & CF_PCREL || tb->pc == desc->s.pc) &&
        tb_page_addr0(tb) == desc->page_addr0 &&
        tb->cs_base == desc->s.cs_base &&
        tb->flags == desc->s.flags &&
        (tb_cflags(tb) & ~CF_INVALID) == desc->s.cflags) {
        /* check next page if needed */
        tb_page_addr_t tb_phys_page1 = tb_page_addr1(tb);
        if (tb_phys_page1 == -1) {
            return true;
        } else {
            tb_page_addr_t phys_page1;
            vaddr virt_page1;

            /*
             * We know that the first page matched, and an otherwise valid TB
             * encountered an incomplete instruction at the end of that page,
             * therefore we know that generating a new TB from the current PC
             * must also require reading from the next page -- even if the
             * second pages do not match, and therefore the resulting insn
             * is different for the new TB.  Therefore any exception raised
             * here by the faulting lookup is not premature.
             */
            virt_page1 = TARGET_PAGE_ALIGN(desc->s.pc);
            phys_page1 = get_page_addr_code(desc->env, virt_page1);
            if (tb_phys_page1 == phys_page1) {
                return true;
            }
        }
    }
    return false;
}

static TranslationBlock *
tb_htable_lookup_common(CPUState *cpu, TCGTBCPUState s, const struct qht *ht,
                        qht_lookup_func_t func)
{
    tb_page_addr_t phys_pc;
    struct tb_desc desc;
    uint32_t h;

    desc.s = s;
    desc.env = cpu_env(cpu);
    phys_pc = get_page_addr_code(desc.env, s.pc);
    if (phys_pc == -1) {
        return NULL;
    }
    desc.page_addr0 = phys_pc;
    h = tb_hash_func(phys_pc, (s.cflags & CF_PCREL ? 0 : s.pc),
                     s.flags, s.cs_base, s.cflags);
    return qht_lookup_custom(ht, &desc, h, func);
}

static TranslationBlock *tb_htable_lookup(CPUState *cpu, TCGTBCPUState s)
{
    return tb_htable_lookup_common(cpu, s, &tb_ctx.htable, tb_lookup_cmp);
}

static bool inv_tb_lookup_cmp(const void *p, const void *d)
{
    const TranslationBlock *tb = p;
    const struct tb_desc *desc = d;

    return tb_lookup_cmp(p, d) &&
           tb->ihash == tb_code_hash_func(desc->env, desc->s.pc, tb->size);
}

TranslationBlock *inv_tb_htable_lookup(CPUState *cpu, TCGTBCPUState s)
{
    return tb_htable_lookup_common(cpu, s, &tb_ctx.inv_htable, inv_tb_lookup_cmp);
}

/**
 * tb_lookup:
 * @cpu: CPU that will execute the returned translation block
 * @pc: guest PC
 * @cs_base: arch-specific value associated with translation block
 * @flags: arch-specific translation block flags
 * @cflags: CF_* flags
 *
 * Look up a translation block inside the QHT using @pc, @cs_base, @flags and
 * @cflags. Uses @cpu's tb_jmp_cache. Might cause an exception, so have a
 * longjmp destination ready.
 *
 * Returns: an existing translation block or NULL.
 */
static inline TranslationBlock *tb_lookup(CPUState *cpu, TCGTBCPUState s)
{
    TranslationBlock *tb;
    CPUJumpCache *jc;
    uint32_t hash;

    /* we should never be trying to look up an INVALID tb */
    tcg_debug_assert(!(s.cflags & CF_INVALID));

    hash = tb_jmp_cache_hash_func(s.pc);
    jc = cpu->tb_jmp_cache;

    tb = qatomic_read(&jc->array[hash].tb);
    if (likely(tb &&
               jc->array[hash].pc == s.pc &&
               tb->cs_base == s.cs_base &&
               tb->flags == s.flags &&
               tb_cflags(tb) == s.cflags)) {
        goto hit;
    }

    tb = tb_htable_lookup(cpu, s);
    if (tb == NULL) {
        return NULL;
    }

    jc->array[hash].pc = s.pc;
    qatomic_set(&jc->array[hash].tb, tb);

hit:
    /*
     * As long as tb is not NULL, the contents are consistent.  Therefore,
     * the virtual PC has to match for non-CF_PCREL translations.
     */
    assert((tb_cflags(tb) & CF_PCREL) || tb->pc == s.pc);
    return tb;
}

static void log_cpu_exec(vaddr pc, CPUState *cpu,
                         const TranslationBlock *tb)
{
    if (qemu_log_in_addr_range(pc)) {
        qemu_log_mask(CPU_LOG_EXEC,
                      "Trace %d: %p [%08" PRIx64
                      "/%016" VADDR_PRIx "/%08x/%08x] %s\n",
                      cpu->cpu_index, tb->tc.ptr, tb->cs_base, pc,
                      tb->flags, tb->cflags, lookup_symbol(pc));

        if (qemu_loglevel_mask(CPU_LOG_TB_CPU)) {
            FILE *logfile = qemu_log_trylock();
            if (logfile) {
                int flags = CPU_DUMP_CCOP;

                if (qemu_loglevel_mask(CPU_LOG_TB_FPU)) {
                    flags |= CPU_DUMP_FPU;
                }
                if (qemu_loglevel_mask(CPU_LOG_TB_VPU)) {
                    flags |= CPU_DUMP_VPU;
                }
                cpu_dump_state(cpu, logfile, flags);
                qemu_log_unlock(logfile);
            }
        }
    }
}

static bool check_for_breakpoints_slow(CPUState *cpu, vaddr pc,
                                       uint32_t *cflags)
{
    CPUBreakpoint *bp;
    bool match_page = false;

    /*
     * Singlestep overrides breakpoints.
     * This requirement is visible in the record-replay tests, where
     * we would fail to make forward progress in reverse-continue.
     *
     * TODO: gdb singlestep should only override gdb breakpoints,
     * so that one could (gdb) singlestep into the guest kernel's
     * architectural breakpoint handler.
     */
    if (cpu->singlestep_enabled) {
        return false;
    }

    QTAILQ_FOREACH(bp, &cpu->breakpoints, entry) {
        /*
         * If we have an exact pc match, trigger the breakpoint.
         * Otherwise, note matches within the page.
         */
        if (pc == bp->pc) {
            bool match_bp = false;

            if (bp->flags & BP_GDB) {
                match_bp = true;
            } else if (bp->flags & BP_CPU) {
#ifdef CONFIG_USER_ONLY
                g_assert_not_reached();
#else
                const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;
                assert(tcg_ops->debug_check_breakpoint);
                match_bp = tcg_ops->debug_check_breakpoint(cpu);
#endif
            }

            if (match_bp) {
                cpu->exception_index = EXCP_DEBUG;
                return true;
            }
        } else if (((pc ^ bp->pc) & TARGET_PAGE_MASK) == 0) {
            match_page = true;
        }
    }

    /*
     * Within the same page as a breakpoint, single-step,
     * returning to helper_lookup_tb_ptr after each insn looking
     * for the actual breakpoint.
     *
     * TODO: Perhaps better to record all of the TBs associated
     * with a given virtual page that contains a breakpoint, and
     * then invalidate them when a new overlapping breakpoint is
     * set on the page.  Non-overlapping TBs would not be
     * invalidated, nor would any TB need to be invalidated as
     * breakpoints are removed.
     */
    if (match_page) {
        *cflags = (*cflags & ~CF_COUNT_MASK) | CF_NO_GOTO_TB | CF_BP_PAGE | 1;
    }
    return false;
}

static inline bool check_for_breakpoints(CPUState *cpu, vaddr pc,
                                         uint32_t *cflags)
{
    return unlikely(!QTAILQ_EMPTY(&cpu->breakpoints)) &&
        check_for_breakpoints_slow(cpu, pc, cflags);
}

/**
 * helper_lookup_tb_ptr: quick check for next tb
 * @env: current cpu state
 *
 * Look for an existing TB matching the current cpu state.
 * If found, return the code pointer.  If not found, return
 * the tcg epilogue so that we return into cpu_tb_exec.
 */
const void *HELPER(lookup_tb_ptr)(CPUArchState *env)
{
    CPUState *cpu = env_cpu(env);
    TranslationBlock *tb;

    /*
     * By definition we've just finished a TB, so I/O is OK.
     * Avoid the possibility of calling cpu_io_recompile() if
     * a page table walk triggered by tb_lookup() calling
     * probe_access_internal() happens to touch an MMIO device.
     * The next TB, if we chain to it, will clear the flag again.
     */
    cpu->neg.can_do_io = true;

    TCGTBCPUState s = cpu->cc->tcg_ops->get_tb_cpu_state(cpu);
    s.cflags = curr_cflags(cpu);

    if (check_for_breakpoints(cpu, s.pc, &s.cflags)) {
        cpu_loop_exit(cpu);
    }

    tb = tb_lookup(cpu, s);
    if (tb == NULL) {
        return tcg_code_gen_epilogue;
    }

    if (qemu_loglevel_mask(CPU_LOG_TB_CPU | CPU_LOG_EXEC)) {
        log_cpu_exec(s.pc, cpu, tb);
    }

    return tb->tc.ptr;
}

/* Return the current PC from CPU, which may be cached in TB. */
static vaddr log_pc(CPUState *cpu, const TranslationBlock *tb)
{
    if (tb_cflags(tb) & CF_PCREL) {
        return cpu->cc->get_pc(cpu);
    } else {
        return tb->pc;
    }
}

/* Execute a TB, and fix up the CPU state afterwards if necessary */
/*
 * Disable CFI checks.
 * TCG creates binary blobs at runtime, with the transformed code.
 * A TB is a blob of binary code, created at runtime and called with an
 * indirect function call. Since such function did not exist at compile time,
 * the CFI runtime has no way to verify its signature and would fail.
 * TCG is not considered a security-sensitive part of QEMU so this does not
 * affect the impact of CFI in environment with high security requirements
 */
static inline TranslationBlock * QEMU_DISABLE_CFI
cpu_tb_exec(CPUState *cpu, TranslationBlock *itb, int *tb_exit)
{
    uintptr_t ret;
    TranslationBlock *last_tb;
    const void *tb_ptr = itb->tc.ptr;

    if (qemu_loglevel_mask(CPU_LOG_TB_CPU | CPU_LOG_EXEC)) {
        log_cpu_exec(log_pc(cpu, itb), cpu, itb);
    }

    qemu_thread_jit_execute();
    ret = tcg_qemu_tb_exec(cpu_env(cpu), tb_ptr);
    cpu->neg.can_do_io = true;
    qemu_plugin_disable_mem_helpers(cpu);
    /*
     * TODO: Delay swapping back to the read-write region of the TB
     * until we actually need to modify the TB.  The read-only copy,
     * coming from the rx region, shares the same host TLB entry as
     * the code that executed the exit_tb opcode that arrived here.
     * If we insist on touching both the RX and the RW pages, we
     * double the host TLB pressure.
     */
    last_tb = tcg_splitwx_to_rw((void *)(ret & ~TB_EXIT_MASK));
    *tb_exit = ret & TB_EXIT_MASK;

    trace_exec_tb_exit(last_tb, *tb_exit);

    if (*tb_exit > TB_EXIT_IDX1) {
        /* We didn't start executing this TB (eg because the instruction
         * counter hit zero); we must restore the guest PC to the address
         * of the start of the TB.
         */
        CPUClass *cc = cpu->cc;
        const TCGCPUOps *tcg_ops = cc->tcg_ops;

        if (tcg_ops->synchronize_from_tb) {
            tcg_ops->synchronize_from_tb(cpu, last_tb);
        } else {
            tcg_debug_assert(!(tb_cflags(last_tb) & CF_PCREL));
            assert(cc->set_pc);
            cc->set_pc(cpu, last_tb->pc);
        }
        if (qemu_loglevel_mask(CPU_LOG_EXEC)) {
            vaddr pc = log_pc(cpu, last_tb);
            if (qemu_log_in_addr_range(pc)) {
                qemu_log("Stopped execution of TB chain before %p [%016"
                         VADDR_PRIx "] %s\n",
                         last_tb->tc.ptr, pc, lookup_symbol(pc));
            }
        }
    }

    /*
     * If gdb single-step, and we haven't raised another exception,
     * raise a debug exception.  Single-step with another exception
     * is handled in cpu_handle_exception.
     */
    if (unlikely(cpu->singlestep_enabled) && cpu->exception_index == -1) {
        cpu->exception_index = EXCP_DEBUG;
        cpu_loop_exit(cpu);
    }

    return last_tb;
}


static void cpu_exec_enter(CPUState *cpu)
{
    const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;

    if (tcg_ops->cpu_exec_enter) {
        tcg_ops->cpu_exec_enter(cpu);
    }
}

static void cpu_exec_exit(CPUState *cpu)
{
    const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;

    if (tcg_ops->cpu_exec_exit) {
        tcg_ops->cpu_exec_exit(cpu);
    }
}

static void cpu_exec_longjmp_cleanup(CPUState *cpu)
{
    /* Non-buggy compilers preserve this; assert the correct value. */
    g_assert(cpu == current_cpu);

#ifdef CONFIG_USER_ONLY
    clear_helper_retaddr();
    if (have_mmap_lock()) {
        mmap_unlock();
    }
#else
    /*
     * For softmmu, a tlb_fill fault during translation will land here,
     * and we need to release any page locks held.  In system mode we
     * have one tcg_ctx per thread, so we know it was this cpu doing
     * the translation.
     *
     * Alternative 1: Install a cleanup to be called via an exception
     * handling safe longjmp.  It seems plausible that all our hosts
     * support such a thing.  We'd have to properly register unwind info
     * for the JIT for EH, rather that just for GDB.
     *
     * Alternative 2: Set and restore cpu->jmp_env in tb_gen_code to
     * capture the cpu_loop_exit longjmp, perform the cleanup, and
     * jump again to arrive here.
     */
    if (tcg_ctx->gen_tb) {
        tb_unlock_pages(tcg_ctx->gen_tb);
        tcg_ctx->gen_tb = NULL;
    }
#endif
    if (bql_locked()) {
        bql_unlock();
    }
    assert_no_pages_locked();
}

void cpu_exec_step_atomic(CPUState *cpu)
{
    TranslationBlock *tb;
    int tb_exit;

    if (sigsetjmp(cpu->jmp_env, 0) == 0) {
        start_exclusive();
        g_assert(cpu == current_cpu);
        g_assert(!cpu->running);
        cpu->running = true;

        TCGTBCPUState s = cpu->cc->tcg_ops->get_tb_cpu_state(cpu);
        s.cflags = curr_cflags(cpu);

        /* Execute in a serial context. */
        s.cflags &= ~CF_PARALLEL;
        /* After 1 insn, return and release the exclusive lock. */
        s.cflags |= CF_NO_GOTO_TB | CF_NO_GOTO_PTR | 1;
        /*
         * No need to check_for_breakpoints here.
         * We only arrive in cpu_exec_step_atomic after beginning execution
         * of an insn that includes an atomic operation we can't handle.
         * Any breakpoint for this insn will have been recognized earlier.
         */

        tb = tb_lookup(cpu, s);
        if (tb == NULL) {
            mmap_lock();
            tb = tb_gen_code(cpu, s);
            mmap_unlock();
        }

        cpu_exec_enter(cpu);
        /* execute the generated code */
        trace_exec_tb(tb, s.pc);
        cpu_tb_exec(cpu, tb, &tb_exit);
        cpu_exec_exit(cpu);
    } else {
        cpu_exec_longjmp_cleanup(cpu);
    }

    /*
     * As we start the exclusive region before codegen we must still
     * be in the region if we longjump out of either the codegen or
     * the execution.
     */
    g_assert(cpu_in_exclusive_context(cpu));
    cpu->running = false;
    end_exclusive();
}

void tb_set_jmp_target(TranslationBlock *tb, int n, uintptr_t addr)
{
    /*
     * Get the rx view of the structure, from which we find the
     * executable code address, and tb_target_set_jmp_target can
     * produce a pc-relative displacement to jmp_target_addr[n].
     */
    const TranslationBlock *c_tb = tcg_splitwx_to_rx(tb);
    uintptr_t offset = tb->jmp_insn_offset[n];
    uintptr_t jmp_rx = (uintptr_t)tb->tc.ptr + offset;
    uintptr_t jmp_rw = jmp_rx - tcg_splitwx_diff;

    tb->jmp_target_addr[n] = addr;
    tb_target_set_jmp_target(c_tb, n, jmp_rx, jmp_rw);
}

static inline void tb_add_jump(TranslationBlock *tb, int n,
                               TranslationBlock *tb_next)
{
    uintptr_t old;

    qemu_thread_jit_write();
    assert(n < ARRAY_SIZE(tb->jmp_list_next));
    qemu_spin_lock(&tb_next->jmp_lock);

    /* make sure the destination TB is valid */
    if (tb_next->cflags & CF_INVALID) {
        goto out_unlock_next;
    }
    /* Atomically claim the jump destination slot only if it was NULL */
    old = qatomic_cmpxchg(&tb->jmp_dest[n], (uintptr_t)NULL,
                          (uintptr_t)tb_next);
    if (old) {
        goto out_unlock_next;
    }

    /* patch the native jump address */
    tb_set_jmp_target(tb, n, (uintptr_t)tb_next->tc.ptr);

    /* add in TB jmp list */
    tb->jmp_list_next[n] = tb_next->jmp_list_head;
    tb_next->jmp_list_head = (uintptr_t)tb | n;

    qemu_spin_unlock(&tb_next->jmp_lock);

    qemu_log_mask(CPU_LOG_EXEC, "Linking TBs %p index %d -> %p\n",
                  tb->tc.ptr, n, tb_next->tc.ptr);
    return;

 out_unlock_next:
    qemu_spin_unlock(&tb_next->jmp_lock);
}

static inline bool cpu_handle_halt(CPUState *cpu)
{
#ifndef CONFIG_USER_ONLY
    if (cpu->halted) {
        const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;
        bool leave_halt = tcg_ops->cpu_exec_halt(cpu);

        if (!leave_halt) {
            return true;
        }

        cpu->halted = 0;
    }
#endif /* !CONFIG_USER_ONLY */

    return false;
}

static inline void cpu_handle_debug_exception(CPUState *cpu)
{
    const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;
    CPUWatchpoint *wp;

    if (!cpu->watchpoint_hit) {
        QTAILQ_FOREACH(wp, &cpu->watchpoints, entry) {
            wp->flags &= ~BP_WATCHPOINT_HIT;
        }
    }

    if (tcg_ops->debug_excp_handler) {
        tcg_ops->debug_excp_handler(cpu);
    }
}

static inline bool cpu_handle_exception(CPUState *cpu, int *ret)
{
    if (cpu->exception_index < 0) {
#ifndef CONFIG_USER_ONLY
        if (replay_has_exception()
            && cpu->neg.icount_decr.u16.low + cpu->icount_extra == 0) {
            /* Execute just one insn to trigger exception pending in the log */
            cpu->cflags_next_tb = (curr_cflags(cpu) & ~CF_USE_ICOUNT)
                | CF_NOIRQ | 1;
        }
#endif
        return false;
    }

    if (cpu->exception_index >= EXCP_INTERRUPT) {
        /* exit request from the cpu execution loop */
        *ret = cpu->exception_index;
        if (*ret == EXCP_DEBUG) {
            cpu_handle_debug_exception(cpu);
        }
        cpu->exception_index = -1;
        return true;
    }

#if defined(CONFIG_USER_ONLY)
    /*
     * If user mode only, we simulate a fake exception which will be
     * handled outside the cpu execution loop.
     */
    const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;
    if (tcg_ops->fake_user_interrupt) {
        tcg_ops->fake_user_interrupt(cpu);
    }
    *ret = cpu->exception_index;
    cpu->exception_index = -1;
    return true;
#else
    if (replay_exception()) {
        const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;

        bql_lock();
        tcg_ops->do_interrupt(cpu);
        bql_unlock();
        cpu->exception_index = -1;

        if (unlikely(cpu->singlestep_enabled)) {
            /*
             * After processing the exception, ensure an EXCP_DEBUG is
             * raised when single-stepping so that GDB doesn't miss the
             * next instruction.
             */
            *ret = EXCP_DEBUG;
            cpu_handle_debug_exception(cpu);
            return true;
        }
    } else if (!replay_has_interrupt()) {
        /* give a chance to iothread in replay mode */
        *ret = EXCP_INTERRUPT;
        return true;
    }
#endif

    return false;
}

void tcg_kick_vcpu_thread(CPUState *cpu)
{
#ifndef CONFIG_USER_ONLY
    /*
     * Ensure cpu_exec will see the reason why the exit request was set.
     * FIXME: this is not always needed.  Other accelerators instead
     * read interrupt_request and set exit_request on demand from the
     * CPU thread; see kvm_arch_pre_run() for example.
     */
    qatomic_store_release(&cpu->exit_request, true);
#endif

    /* Ensure cpu_exec will see the exit request after TCG has exited.  */
    qatomic_store_release(&cpu->neg.icount_decr.u16.high, -1);
}

static inline bool icount_exit_request(CPUState *cpu)
{
    if (!icount_enabled()) {
        return false;
    }
    if (cpu->cflags_next_tb != -1 && !(cpu->cflags_next_tb & CF_USE_ICOUNT)) {
        return false;
    }
    return cpu->neg.icount_decr.u16.low + cpu->icount_extra == 0;
}

static inline bool cpu_handle_interrupt(CPUState *cpu,
                                        TranslationBlock **last_tb)
{
    /*
     * If we have requested custom cflags with CF_NOIRQ we should
     * skip checking here. Any pending interrupts will get picked up
     * by the next TB we execute under normal cflags.
     */
    if (cpu->cflags_next_tb != -1 && cpu->cflags_next_tb & CF_NOIRQ) {
        return false;
    }

    /* Clear the interrupt flag now since we're processing
     * cpu->interrupt_request and cpu->exit_request.
     * Ensure zeroing happens before reading cpu->exit_request or
     * cpu->interrupt_request (see also store-release in
     * tcg_kick_vcpu_thread())
     */
    qatomic_set_mb(&cpu->neg.icount_decr.u16.high, 0);

#ifdef CONFIG_USER_ONLY
    assert(!cpu_test_interrupt(cpu, ~0));
#else
    if (unlikely(cpu_test_interrupt(cpu, ~0))) {
        bql_lock();
        if (cpu_test_interrupt(cpu, CPU_INTERRUPT_DEBUG)) {
            cpu_reset_interrupt(cpu, CPU_INTERRUPT_DEBUG);
            cpu->exception_index = EXCP_DEBUG;
            bql_unlock();
            return true;
        }
        if (replay_mode == REPLAY_MODE_PLAY && !replay_has_interrupt()) {
            /* Do nothing */
        } else if (cpu_test_interrupt(cpu, CPU_INTERRUPT_HALT)) {
            replay_interrupt();
            cpu_reset_interrupt(cpu, CPU_INTERRUPT_HALT);
            cpu->halted = 1;
            cpu->exception_index = EXCP_HLT;
            bql_unlock();
            return true;
        } else {
            const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;
            int interrupt_request = cpu->interrupt_request;

            if (cpu_test_interrupt(cpu, CPU_INTERRUPT_RESET)) {
                replay_interrupt();
                tcg_ops->cpu_exec_reset(cpu);
                bql_unlock();
                return true;
            }

            if (unlikely(cpu->singlestep_enabled & SSTEP_NOIRQ)) {
                /* Mask out external interrupts for this step. */
                interrupt_request &= ~CPU_INTERRUPT_SSTEP_MASK;
            }

            /*
             * The target hook has 3 exit conditions:
             * False when the interrupt isn't processed,
             * True when it is, and we should restart on a new TB,
             * and via longjmp via cpu_loop_exit.
             */
            if (tcg_ops->cpu_exec_interrupt(cpu, interrupt_request)) {
                if (!tcg_ops->need_replay_interrupt ||
                    tcg_ops->need_replay_interrupt(interrupt_request)) {
                    replay_interrupt();
                }
                /*
                 * After processing the interrupt, ensure an EXCP_DEBUG is
                 * raised when single-stepping so that GDB doesn't miss the
                 * next instruction.
                 */
                if (unlikely(cpu->singlestep_enabled)) {
                    cpu->exception_index = EXCP_DEBUG;
                    bql_unlock();
                    return true;
                }
                cpu->exception_index = -1;
                *last_tb = NULL;
            }
        }
        if (cpu_test_interrupt(cpu, CPU_INTERRUPT_EXITTB)) {
            cpu_reset_interrupt(cpu, CPU_INTERRUPT_EXITTB);
            /* ensure that no TB jump will be modified as
               the program flow was changed */
            *last_tb = NULL;
        }

        /* If we exit via cpu_loop_exit/longjmp it is reset in cpu_exec */
        bql_unlock();
    }
#endif /* !CONFIG_USER_ONLY */

    /*
     * Finally, check if we need to exit to the main loop.
     * The corresponding store-release is in cpu_exit.
     */
    if (unlikely(qatomic_load_acquire(&cpu->exit_request)) || icount_exit_request(cpu)) {
        if (cpu->exception_index == -1) {
            cpu->exception_index = EXCP_INTERRUPT;
        }
        return true;
    }

    return false;
}

static inline void cpu_loop_exec_tb(CPUState *cpu, TranslationBlock *tb,
                                    vaddr pc, TranslationBlock **last_tb,
                                    int *tb_exit)
{
    trace_exec_tb(tb, pc);
    tb = cpu_tb_exec(cpu, tb, tb_exit);
    if (*tb_exit != TB_EXIT_REQUESTED) {
        *last_tb = tb;
        return;
    }

    *last_tb = NULL;
    if (cpu_loop_exit_requested(cpu)) {
        /* Something asked us to stop executing chained TBs; just
         * continue round the main loop. Whatever requested the exit
         * will also have set something else (eg exit_request or
         * interrupt_request) which will be handled by
         * cpu_handle_interrupt.  cpu_handle_interrupt will also
         * clear cpu->icount_decr.u16.high.
         */
        return;
    }

    /* Instruction counter expired.  */
    assert(icount_enabled());
#ifndef CONFIG_USER_ONLY
    /* Ensure global icount has gone forward */
    icount_update(cpu);
    /* Refill decrementer and continue execution.  */
    int32_t insns_left = MIN(0xffff, cpu->icount_budget);
    cpu->neg.icount_decr.u16.low = insns_left;
    cpu->icount_extra = cpu->icount_budget - insns_left;

    /*
     * If the next tb has more instructions than we have left to
     * execute we need to ensure we find/generate a TB with exactly
     * insns_left instructions in it.
     */
    if (insns_left > 0 && insns_left < tb->icount)  {
        assert(insns_left <= CF_COUNT_MASK);
        assert(cpu->icount_extra == 0);
        cpu->cflags_next_tb = (tb->cflags & ~CF_COUNT_MASK) | insns_left;
    }
#endif
}

/* main execution loop */

static int __attribute__((noinline))
cpu_exec_loop(CPUState *cpu, SyncClocks *sc)
{
    int ret;

    /* if an exception is pending, we execute it here */
    while (!cpu_handle_exception(cpu, &ret)) {
        TranslationBlock *last_tb = NULL;
        int tb_exit = 0;

        while (!cpu_handle_interrupt(cpu, &last_tb)) {
            TranslationBlock *tb;
            TCGTBCPUState s = cpu->cc->tcg_ops->get_tb_cpu_state(cpu);
            s.cflags = cpu->cflags_next_tb;

            /*
             * When requested, use an exact setting for cflags for the next
             * execution.  This is used for icount, precise smc, and stop-
             * after-access watchpoints.  Since this request should never
             * have CF_INVALID set, -1 is a convenient invalid value that
             * does not require tcg headers for cpu_common_reset.
             */
            if (s.cflags == -1) {
                s.cflags = curr_cflags(cpu);
            } else {
#if defined(__ANDROID__) || defined(ANDROID)
                /* A one-shot cflags request (icount, precise SMC, or
                 * cpu_io_recompile) means the next TB is keyed differently
                 * from what any inline-cache slot recorded.  Strand them. */
                {
                    extern uint32_t xemu_ic_generation;
                    xemu_ic_generation++;
                }
#endif
                cpu->cflags_next_tb = -1;
            }

#if defined(__ANDROID__) || defined(ANDROID)
            /* Unchain the spin range so every iteration reaches the
             * dispatcher, then sleep once it is clearly spinning. */
            if (unlikely(g_nochain)) {
                s.cflags |= CF_NO_GOTO_TB | CF_NO_GOTO_PTR;
            } else if (unlikely(g_spin_lo || spin_set_n ||
                                spin_probe_left > 0)) {
                if (spin_probe_left > 0 || xemu_spin_member(s.pc)) {
                    s.cflags |= CF_NO_GOTO_TB | CF_NO_GOTO_PTR;
                }
            }
            xemu_spin_probe(s.pc);
            xemu_spin_maybe_sleep(s.pc);
#endif

            if (check_for_breakpoints(cpu, s.pc, &s.cflags)) {
                break;
            }

            tb = tb_lookup(cpu, s);
            if (tb == NULL) {
                CPUJumpCache *jc;
                uint32_t h;

                mmap_lock();
                tb = tb_gen_code(cpu, s);
                mmap_unlock();

                /*
                 * We add the TB in the virtual pc hash table
                 * for the fast lookup
                 */
                h = tb_jmp_cache_hash_func(s.pc);
                jc = cpu->tb_jmp_cache;
                jc->array[h].pc = s.pc;
                qatomic_set(&jc->array[h].tb, tb);
            }

#ifndef CONFIG_USER_ONLY
            /*
             * We don't take care of direct jumps when address mapping
             * changes in system emulation.  So it's not safe to make a
             * direct jump to a TB spanning two pages because the mapping
             * for the second page can change.
             */
            if (tb_page_addr1(tb) != -1) {
                last_tb = NULL;
            }
#endif
            /* See if we can patch the calling TB. */
            if (last_tb) {
                tb_add_jump(last_tb, tb_exit, tb);
            }

#if defined(__ANDROID__) || defined(ANDROID)
            xemu_tb_exec_count++;
            xemu_guest_insn_count += tb->icount;
#endif
            cpu_loop_exec_tb(cpu, tb, s.pc, &last_tb, &tb_exit);

            /* Try to align the host and virtual clocks
               if the guest is in advance */
            align_clocks(sc, cpu);
        }
    }
    return ret;
}

static int cpu_exec_setjmp(CPUState *cpu, SyncClocks *sc)
{
    /* Prepare setjmp context for exception handling. */
    if (unlikely(sigsetjmp(cpu->jmp_env, 0) != 0)) {
        cpu_exec_longjmp_cleanup(cpu);
    }

    return cpu_exec_loop(cpu, sc);
}

int cpu_exec(CPUState *cpu)
{
    int ret;
    SyncClocks sc = { 0 };

#if defined(__ANDROID__) || defined(ANDROID)
    if (unlikely(!xemu_vcpu_tid)) {
        extern void vcpu_affinity_refresh(void);

        xemu_vcpu_tid = (int)syscall(__NR_gettid);
        /* Apply core affinity for normal play, not only during benchmarks. */
        vcpu_affinity_refresh();
    }
#endif

    /* replay_interrupt may need current_cpu */
    current_cpu = cpu;

    if (cpu_handle_halt(cpu)) {
        return EXCP_HALTED;
    }

    RCU_READ_LOCK_GUARD();
    cpu_exec_enter(cpu);

    /*
     * Calculate difference between guest clock and host clock.
     * This delay includes the delay of the last cycle, so
     * what we have to do is sleep until it is 0. As for the
     * advance/delay we gain here, we try to fix it next time.
     */
    init_delay_params(&sc, cpu);

    ret = cpu_exec_setjmp(cpu, &sc);

    cpu_exec_exit(cpu);
    return ret;
}

bool tcg_exec_realizefn(CPUState *cpu, Error **errp)
{
    static bool tcg_target_initialized;

    if (!tcg_target_initialized) {
        /* Check mandatory TCGCPUOps handlers */
        const TCGCPUOps *tcg_ops = cpu->cc->tcg_ops;
#ifndef CONFIG_USER_ONLY
        assert(tcg_ops->cpu_exec_halt);
        assert(tcg_ops->cpu_exec_interrupt);
        assert(tcg_ops->cpu_exec_reset);
        assert(tcg_ops->pointer_wrap);
#endif /* !CONFIG_USER_ONLY */
        assert(tcg_ops->translate_code);
        assert(tcg_ops->get_tb_cpu_state);
        assert(tcg_ops->mmu_index);
        tcg_ops->initialize();
        tcg_target_initialized = true;
    }

    cpu->tb_jmp_cache = g_new0(CPUJumpCache, 1);
    tlb_init(cpu);
#ifndef CONFIG_USER_ONLY
    tcg_iommu_init_notifier_list(cpu);
#endif /* !CONFIG_USER_ONLY */
    /* qemu_plugin_vcpu_init_hook delayed until cpu_index assigned. */

    return true;
}

/* undo the initializations in reverse order */
void tcg_exec_unrealizefn(CPUState *cpu)
{
#ifndef CONFIG_USER_ONLY
    tcg_iommu_free_notifier_list(cpu);
#endif /* !CONFIG_USER_ONLY */

    tlb_destroy(cpu);
    g_free_rcu(cpu->tb_jmp_cache, rcu);
}

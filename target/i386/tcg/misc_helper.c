/*
 *  x86 misc helpers
 *
 *  Copyright (c) 2003 Fabrice Bellard
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
#include "qemu/log.h"
#include "cpu.h"
#include "exec/helper-proto.h"
#include "exec/cputlb.h"
#include "helper-tcg.h"
#include "qemu/timer.h"
#include "qemu/atomic.h"
#include "accel/tcg/cpu-ldst.h"
#include "exec/watchpoint.h"
#include "tcg/insn-start-words.h"
#include <sys/system_properties.h>
#include "system/address-spaces.h"
#include "system/memory.h"
#include <dirent.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <linux/hw_breakpoint.h>
#include <linux/perf_event.h>

/*
 * NOTE: the translator must set DisasContext.cc_op to CC_OP_EFLAGS
 * after generating a call to a helper that uses this.
 */
void cpu_load_eflags(CPUX86State *env, int eflags, int update_mask)
{
    CC_SRC = eflags & (CC_O | CC_S | CC_Z | CC_A | CC_P | CC_C);
    CC_OP = CC_OP_EFLAGS;
    env->df = 1 - (2 * ((eflags >> 10) & 1));
    env->eflags = (env->eflags & ~update_mask) |
        (eflags & update_mask) | 0x2;
}

/*
 * Measurement only: total rep-string iterations executed.  QEMU emits x86
 * string ops as a per-element loop, so a 4 KB `rep movsd` is 1024 iterations
 * at ~20-30 host instructions each.  This counts the iterations so the size
 * of a memcpy fast path can be judged before building one.  Called once per
 * rep instruction with ECX, not once per iteration.
 */
unsigned long long xemu_rep_iters, xemu_rep_execs;

void helper_xemu_count_rep(target_ulong ecx)
{
    xemu_rep_iters += ecx;
    xemu_rep_execs++;
}

void helper_into(CPUX86State *env, int next_eip_addend)
{
    int eflags;

    eflags = cpu_cc_compute_all(env);
    if (eflags & CC_O) {
        raise_interrupt(env, EXCP04_INTO, next_eip_addend);
    }
}

void helper_cpuid(CPUX86State *env)
{
    uint32_t eax, ebx, ecx, edx;

    cpu_svm_check_intercept_param(env, SVM_EXIT_CPUID, 0, GETPC());

    cpu_x86_cpuid(env, (uint32_t)env->regs[R_EAX], (uint32_t)env->regs[R_ECX],
                  &eax, &ebx, &ecx, &edx);
    env->regs[R_EAX] = eax;
    env->regs[R_EBX] = ebx;
    env->regs[R_ECX] = ecx;
    env->regs[R_EDX] = edx;
}

void helper_rdtsc(CPUX86State *env)
{
    uint64_t val;

    if ((env->cr[4] & CR4_TSD_MASK) && ((env->hflags & HF_CPL_MASK) != 0)) {
        raise_exception_ra(env, EXCP0D_GPF, GETPC());
    }
    cpu_svm_check_intercept_param(env, SVM_EXIT_RDTSC, 0, GETPC());

    val = cpu_get_tsc(env) + env->tsc_offset;
    env->regs[R_EAX] = (uint32_t)(val);
    env->regs[R_EDX] = (uint32_t)(val >> 32);
}

G_NORETURN void helper_rdpmc(CPUX86State *env)
{
    if (((env->cr[4] & CR4_PCE_MASK) == 0 ) &&
        ((env->hflags & HF_CPL_MASK) != 0)) {
        raise_exception_ra(env, EXCP0D_GPF, GETPC());
    }
    cpu_svm_check_intercept_param(env, SVM_EXIT_RDPMC, 0, GETPC());

    /* currently unimplemented */
    qemu_log_mask(LOG_UNIMP, "x86: unimplemented rdpmc\n");
    raise_exception_err(env, EXCP06_ILLOP, 0);
}

G_NORETURN void helper_pause(CPUX86State *env)
{
    CPUState *cs = env_cpu(env);

    /* Do gen_eob() tasks before going back to the main loop.  */
    do_end_instruction(env);
    helper_rechecking_single_step(env);

    /* Just let another CPU run.  */
    cs->exception_index = EXCP_INTERRUPT;
    cpu_loop_exit(cs);
}

uint64_t helper_rdpkru(CPUX86State *env, uint32_t ecx)
{
    if ((env->cr[4] & CR4_PKE_MASK) == 0) {
        raise_exception_err_ra(env, EXCP06_ILLOP, 0, GETPC());
    }
    if (ecx != 0) {
        raise_exception_err_ra(env, EXCP0D_GPF, 0, GETPC());
    }

    return env->pkru;
}

void helper_wrpkru(CPUX86State *env, uint32_t ecx, uint64_t val)
{
    CPUState *cs = env_cpu(env);

    if ((env->cr[4] & CR4_PKE_MASK) == 0) {
        raise_exception_err_ra(env, EXCP06_ILLOP, 0, GETPC());
    }
    if (ecx != 0 || (val & 0xFFFFFFFF00000000ull)) {
        raise_exception_err_ra(env, EXCP0D_GPF, 0, GETPC());
    }

    env->pkru = val;
    tlb_flush(cs);
}

target_ulong HELPER(rdpid)(CPUX86State *env)
{
#if !defined CONFIG_USER_ONLY
    return env->tsc_aux;
#elif defined CONFIG_LINUX && defined CONFIG_GETCPU
    unsigned cpu, node;
    getcpu(&cpu, &node);
    return (node << 12) | (cpu & 0xfff);
#elif defined CONFIG_SCHED_GETCPU
    return sched_getcpu();
#else
    return 0;
#endif
}

/*
 * `sub eax,1; jnz $-3` -- the Xbox kernel's KeStallExecutionProcessor loop.
 *
 * The kernel expresses a delay in loop iterations (367 per microsecond, the
 * value at 0x8003aff0 in the Complex 4627 kernel), trusting a Pentium III at
 * 733 MHz to take 2 cycles per iteration.  Translated, an iteration costs
 * whatever the TB loop costs on the host, so the guest's delays stretch or
 * shrink with emulation speed.  It was 72% of generated-code time during
 * Halo's startup.
 *
 * The translator calls this before the idiom's `sub` (see translate.c).  It
 * consumes up to 1 ms of iterations at a time -- waiting for as long as the
 * real CPU would have taken in mode 1, not at all in mode 2 (an upper bound
 * for measurement only) -- and always leaves EAX >= 1, so the guest's own
 * sub/jnz still produce the exit and the flags.  Chunking keeps interrupt
 * latency at <= 1 ms: the loop returns to the TB start between chunks.
 */
int g_xemu_stall_mode;                     /* debug.xemu.stall_hle */
unsigned long long xemu_stall_iters_elided, xemu_stall_ns_waited;

#define STALL_ITERS_PER_MS 366667          /* 733.33 MHz / 2 cycles, per ms */

void helper_xemu_stall(CPUX86State *env)
{
    uint32_t n = env->regs[R_EAX];
    uint32_t chunk;

    if (n <= 1 || g_xemu_stall_mode == 0) {
        return;
    }
    chunk = MIN(n - 1, g_xemu_stall_mode == 2 ? n - 1 : STALL_ITERS_PER_MS);
    if (g_xemu_stall_mode == 1) {
        int64_t ns = (int64_t)chunk * 1000000 / STALL_ITERS_PER_MS;
        int64_t end = get_clock() + ns;
        if (ns > 200000) {
            g_usleep((ns - 100000) / 1000);    /* sleep most, spin the tail */
        }
        while (get_clock() < end) {
            /* spin: sub-100 us precision matters for device timing */
        }
        qatomic_add(&xemu_stall_ns_waited, ns);
    }
    qatomic_add(&xemu_stall_iters_elided, chunk);
    env->regs[R_EAX] = n - chunk;
}

/*
 * Kernel idle loop -> hlt (FINDINGS BQ).
 *
 * The Xbox kernel idles in KiIdleLoop:
 *
 *     sti; nop; nop; cli
 *     cmp ebp, [ebp]          ; DPC queue (ebp = &Prcb->DpcListHead) empty?
 *     je  ...                 ; else retire DPCs
 *     cmp dword [ebx+2Ch], 0  ; Prcb->NextThread?
 *     je  <sti>               ; nothing to run: spin again
 *
 * It never executes hlt, so the vCPU spins a host core at 100% -- through the
 * dispatcher, since sti ends a TB -- for as long as the guest is idle: 5-9 ms
 * of a normal 33 ms Halo frame, up to ~24 ms of a slow one (BP).
 *
 * The translator calls this at the first nop, inside the sti's interrupt
 * shadow, which is exactly where `sti; hlt` waits on real hardware.  If the
 * loop's own two tests say there is nothing to do, halt as hlt does.  On the
 * single-CPU Xbox only an interrupt can queue a DPC or ready a thread, and an
 * interrupt is what ends the halt: it is delivered with the return address
 * at this nop, its handler runs, and the loop re-tests.  An interrupt already
 * pending is taken at once (cpu_handle_halt checks cpu_has_work).
 *
 * debug.xemu.idle_halt: 0 off, 1 halt (default); diagnostics 2 = sleep
 * 100 us instead of halting, 4 = do the reads only.  Re-read about once a
 * second so A/B can toggle it inside one process.
 *
 * ON by default.  Two bugs it exposed are fixed: Jade Empire's boot crash
 * was an nv2a.c hack writing 1 to NextThread (BR), and MGS2's movie stall
 * was lost PIT ticks, which a halted vCPU loses more of (i8259.c, BT).
 */
int g_xemu_idle_halt = 1;
unsigned long long xemu_idle_halts;
uint64_t xemu_idle_halt_t0;       /* cntvct at the halt, 0 when running */

/*
 * DIAGNOSTIC (debug.xemu.kprcb_watch=1): log every guest store to
 * Prcb->CurrentThread / NextThread (KPRCB 0x80035bdc +0x28/+0x2c) and to the
 * same physical bytes through the 0xF0000000 alias, without trapping.  Each
 * store records the writing instruction, all GPRs (the stored value is
 * normally one of them), the top of the stack (the caller), IRQL and the
 * fields' values just before the store.  xemu_x86_dump_stuck prints the ring
 * when the guest has halted with IF clear -- the end of a KeBugCheckEx.
 * Device DMA does not pass through here: a field whose value at one store
 * differs from what the previous store wrote was changed by something else.
 */
#define XEMU_BP_LOG 0x1000
#define XEMU_KW_N   256
extern void (*xemu_wp_log_hook)(CPUState *cpu, vaddr addr, vaddr len,
                                uintptr_t ra);
extern unsigned xemu_int_head;
static struct xemu_kw {
    uint64_t t;
    uint32_t addr, len, eip, d0, eflags, cur, next, irql, ints;
    uint32_t regs[8], stk[6];
} xemu_kw_ring[XEMU_KW_N];
static unsigned xemu_kw_head;
static unsigned xemu_hw_count(void);

/*
 * debug.xemu.watch_va=<hex>: one more log-only write watch, 4 bytes, on any
 * guest address -- e.g. a semaphore's SignalState, to see who releases it.
 * Its own ring, with the writer's PC, current thread and 24 stack words,
 * printed by the thread dump.
 */
#define XEMU_VA_N 16
static struct xemu_va {
    uint64_t t;
    uint32_t pc, cur, old, regs[8], stk[64];
} xemu_va_ring[XEMU_VA_N];
static unsigned xemu_va_head;
static uint32_t xemu_watch_va;

/*
 * Per guest thread: where it last switched out.  At the store to
 * CurrentThread, ESP still belongs to the outgoing thread, so a thread that
 * is blocked now has its whole wait call chain at that ESP.  Dumped on
 * demand (setprop debug.xemu.thread_dump <new value>) with each thread's
 * KTHREAD, first wait block and the object it waits on.
 */
#define XEMU_THR_N 64
static struct xemu_thr {
    uint32_t thr, esp, pc, n;
    uint64_t t;
} xemu_thr_tab[XEMU_THR_N];

static void xemu_thr_note(uint32_t thr, uint32_t esp, uint32_t pc,
                          uint64_t t)
{
    int i, free_i = -1;

    for (i = 0; i < XEMU_THR_N; i++) {
        if (xemu_thr_tab[i].thr == thr) {
            break;
        }
        if (!xemu_thr_tab[i].thr && free_i < 0) {
            free_i = i;
        }
    }
    if (i == XEMU_THR_N) {
        if (free_i < 0) {
            return;
        }
        i = free_i;
        xemu_thr_tab[i].thr = thr;
    }
    xemu_thr_tab[i].esp = esp;
    xemu_thr_tab[i].pc = pc;
    xemu_thr_tab[i].t = t;
    xemu_thr_tab[i].n++;
}

static void xemu_dump_words(CPUState *cs, const char *tag, uint32_t va,
                            int words)
{
    uint32_t w[8];
    int i;

    for (i = 0; i < words; i += 8) {
        memset(w, 0xee, sizeof(w));
        cpu_memory_rw_debug(cs, va + i * 4, (uint8_t *)w, sizeof(w), 0);
        fprintf(stderr, "thread-dump:   %s %08x: %08x %08x %08x %08x %08x "
                "%08x %08x %08x\n", tag, va + i * 4, w[0], w[1], w[2], w[3],
                w[4], w[5], w[6], w[7]);
        g_usleep(1000);                /* logcat drops lines in a burst */
    }
}

static void xemu_thread_dump(CPUState *cs)
{
    uint64_t now, freq;
    uint32_t cur = 0;
    int i;

    asm volatile("mrs %0, cntvct_el0" : "=r"(now));
    asm volatile("mrs %0, cntfrq_el0" : "=r"(freq));
    cpu_memory_rw_debug(cs, 0x80035c04, (uint8_t *)&cur, 4, 0);
    fprintf(stderr, "thread-dump: current %08x\n", cur);
    for (i = 0; i < XEMU_THR_N; i++) {
        struct xemu_thr *t = &xemu_thr_tab[i];
        uint32_t wb = 0, obj = 0;

        if (!t->thr) {
            continue;
        }
        fprintf(stderr, "thread-dump: thread %08x switched out %u times, "
                "last %.3f s ago at pc %08x esp %08x\n", t->thr, t->n,
                (double)(now - t->t) / freq, t->pc, t->esp);
        xemu_dump_words(cs, "kthread", t->thr, 40);
        cpu_memory_rw_debug(cs, t->thr + 0x58, (uint8_t *)&wb, 4, 0);
        if (wb >= 0x80000000u) {
            xemu_dump_words(cs, "waitblk", wb, 8);
            cpu_memory_rw_debug(cs, wb + 0xc, (uint8_t *)&obj, 4, 0);
            if (obj >= 0x80000000u) {
                xemu_dump_words(cs, "object ", obj, 8);
            }
        }
        xemu_dump_words(cs, "stack  ", t->esp, 64);
    }
    {
        char v[PROP_VALUE_MAX] = { 0 };
        uint32_t va, len;

        char *p = v;
        int used;

        /* "va,len[;va,len...]"; a va of *addr dumps from the pointer there */
        __system_property_get("debug.xemu.dump_mem", v);
        while (*p) {
            bool deref = *p == '*';
            if (sscanf(p + deref, "%x,%x%n", &va, &len, &used) != 2 ||
                len > 0x4000) {
                break;
            }
            if (deref) {
                uint32_t q = 0;
                cpu_memory_rw_debug(cs, va, (uint8_t *)&q, 4, 0);
                va = q;
            }
            fprintf(stderr, "thread-dump: mem %08x len %x\n", va, len);
            xemu_dump_words(cs, "mem", va, (len + 31) / 32 * 8);
            p += deref + used;
            if (*p != ';') {
                break;
            }
            p++;
        }
    }
    {
        /* debug.xemu.dump_ring=<ctl>: walk a game stream ring (MGS2's at
         * 0x64d5f8: +8 base, +0xc size, +0x14 read, +0x20 write) */
        char v[PROP_VALUE_MAX] = { 0 };
        uint32_t ctl, c[10], pos, hdr[2];
        int k;

        if (__system_property_get("debug.xemu.dump_ring", v) > 0 &&
            (ctl = strtoul(v, NULL, 16)) != 0 &&
            cpu_memory_rw_debug(cs, ctl, (uint8_t *)c, sizeof(c), 0) == 0) {
            fprintf(stderr, "thread-dump: ring %08x base %08x size %x end "
                    "%08x read %08x write %08x +24 %08x\n", ctl, c[2], c[3],
                    c[4], c[5], c[8], c[9]);
            pos = c[5];
            for (k = 0; k < 64 && pos != c[8]; k++) {
                cpu_memory_rw_debug(cs, pos, (uint8_t *)hdr, 8, 0);
                fprintf(stderr, "thread-dump: ring pkt %08x type %08x size "
                        "%x\n", pos, hdr[0], hdr[1]);
                g_usleep(1000);
                if (hdr[0] == 0xff) {
                    pos = c[2];
                } else if (hdr[1] == 0 || hdr[1] > c[3]) {
                    break;
                } else {
                    pos += hdr[1];
                    if (pos >= c[2] + c[3]) {
                        pos = c[2];
                    }
                }
            }
        }
    }
    if (xemu_watch_va) {
        unsigned n = MIN(xemu_va_head, XEMU_VA_N), k;

        fprintf(stderr, "thread-dump: watch_va %08x: %u stores, last %u\n",
                xemu_watch_va, xemu_va_head, n);
        for (k = n; k > 0; k--) {
            struct xemu_va *e = &xemu_va_ring[(xemu_va_head - k) % XEMU_VA_N];
            int j;

            fprintf(stderr, "thread-dump: va -%02u %.3f s ago pc=%08x thread "
                    "%08x old=%08x | eax=%08x ecx=%08x edx=%08x ebx=%08x "
                    "esp=%08x ebp=%08x esi=%08x edi=%08x\n", k - 1,
                    (double)(now - e->t) / freq, e->pc, e->cur, e->old,
                    e->regs[0], e->regs[1], e->regs[2], e->regs[3],
                    e->regs[4], e->regs[5], e->regs[6], e->regs[7]);
            for (j = 0; j < 64; j += 8) {
                fprintf(stderr, "thread-dump:   va-stk %08x %08x %08x %08x "
                        "%08x %08x %08x %08x\n", e->stk[j], e->stk[j + 1],
                        e->stk[j + 2], e->stk[j + 3], e->stk[j + 4],
                        e->stk[j + 5], e->stk[j + 6], e->stk[j + 7]);
            }
        }
    }
}

static void xemu_thread_dump_poll(CPUState *cs)
{
    static unsigned calls;
    static uint64_t last;
    static char armed[PROP_VALUE_MAX];
    static bool init;
    char v[PROP_VALUE_MAX] = { 0 };
    uint64_t now;

    if (++calls & 255) {
        return;
    }
    asm volatile("mrs %0, cntvct_el0" : "=r"(now));
    if (now - last < 19200000ull / 2) {          /* ~0.5 s */
        return;
    }
    last = now;
    __system_property_get("debug.xemu.thread_dump", v);
    if (!init) {                     /* a value set before boot: ignore */
        init = true;
        strcpy(armed, v);
        return;
    }
    if (strcmp(v, armed)) {
        strcpy(armed, v);
        if (v[0] && strcmp(v, "0")) {
            xemu_thread_dump(cs);
        }
    }
}


static void xemu_va_log(CPUState *cs, uint32_t pc, uint64_t t)
{
    CPUX86State *env = cpu_env(cs);
    struct xemu_va *e = &xemu_va_ring[xemu_va_head++ % XEMU_VA_N];
    int i;

    e->t = t;
    e->pc = pc;
    cpu_memory_rw_debug(cs, 0x80035c04, (uint8_t *)&e->cur, 4, 0);
    cpu_memory_rw_debug(cs, xemu_watch_va, (uint8_t *)&e->old, 4, 0);
    for (i = 0; i < 8; i++) {
        e->regs[i] = env->regs[i];
    }
    memset(e->stk, 0xee, sizeof(e->stk));
    cpu_memory_rw_debug(cs, env->regs[R_ESP], (uint8_t *)e->stk,
                        sizeof(e->stk), 0);
}

static void xemu_kprcb_wp_log(CPUState *cs, vaddr addr, vaddr len,
                              uintptr_t ra)
{
    if (xemu_watch_va && addr >= xemu_watch_va &&
        addr < xemu_watch_va + 4) {
        CPUX86State *env = cpu_env(cs);
        uint64_t data[INSN_START_WORDS] = { 0 }, t;
        uint32_t d0 = cpu_unwind_state_data(cs, ra, data) ?
                      (uint32_t)data[0] : ~0u;

        asm volatile("mrs %0, cntvct_el0" : "=r"(t));
        xemu_va_log(cs, d0 < 0x1000 ? ((env->eip & ~0xfffu) | d0) : d0, t);
        return;
    }

    CPUX86State *env = cpu_env(cs);
    struct xemu_kw *e = &xemu_kw_ring[xemu_kw_head++ % XEMU_KW_N];
    uint64_t data[INSN_START_WORDS] = { 0 };
    uint32_t f[2] = { 0, 0 };
    uint8_t irql = 0xff;
    int i;

    asm volatile("mrs %0, cntvct_el0" : "=r"(e->t));
    e->addr = addr;
    e->len = len;
    e->eip = env->eip;
    e->d0 = cpu_unwind_state_data(cs, ra, data) ? (uint32_t)data[0] : ~0u;
    e->eflags = env->eflags;
    for (i = 0; i < 8; i++) {
        e->regs[i] = env->regs[i];
    }
    cpu_memory_rw_debug(cs, 0x80035c04, (uint8_t *)f, sizeof(f), 0);
    cpu_memory_rw_debug(cs, 0x80035bd8, &irql, 1, 0);
    memset(e->stk, 0xee, sizeof(e->stk));
    cpu_memory_rw_debug(cs, env->regs[R_ESP], (uint8_t *)e->stk,
                        sizeof(e->stk), 0);
    e->cur = f[0];
    e->next = f[1];
    e->irql = irql;
    e->ints = xemu_int_head;
    if (addr == 0x80035c04 && f[0]) {      /* switching away from f[0] */
        xemu_thr_note(f[0], env->regs[R_ESP], e->d0 < 0x1000 ?
                      ((e->eip & ~0xfffu) | e->d0) : e->d0, e->t);
    }
    if ((xemu_kw_head & 0x3fff) == 1) {      /* proof of life */
        fprintf(stderr, "kprcb-watch: %u stores, last pc d0=%08x eip=%08x "
                "st %08x cur=%08x next=%08x | host wp: %u sample bytes\n",
                xemu_kw_head, e->d0, e->eip, e->addr, e->cur, e->next,
                xemu_hw_count());
    }
}

/*
 * The same field watched by the host CPU: an ARM hardware watchpoint
 * (perf_event breakpoint) on the host RAM backing Prcb->NextThread, in every
 * other thread of the process, inherited by threads they create later.  It
 * sees stores the TCG watchpoint cannot -- device DMA and host threads.
 * Each hit is a perf sample (tid, PC, user call chain, registers) in a
 * per-thread ring buffer, read when the crash is reported; no signals, since
 * QEMU threads block them all.  The vCPU thread is left out: its stores are
 * what the TCG watchpoint logs.
 */
#define XEMU_HW_MAX   128
#define XEMU_HW_PAGES 8
static struct { int fd, tid; void *map; } xemu_hw_ev[XEMU_HW_MAX];
static int xemu_hw_nev;
static volatile uint32_t *xemu_hw_addr;

static int xemu_hw_attach(int tid, int inherit)
{
    struct perf_event_attr pa;

    memset(&pa, 0, sizeof(pa));
    pa.type = PERF_TYPE_BREAKPOINT;
    pa.size = sizeof(pa);
    pa.bp_type = HW_BREAKPOINT_W;
    pa.bp_addr = (uintptr_t)xemu_hw_addr;
    pa.bp_len = HW_BREAKPOINT_LEN_4;
    pa.sample_period = 1;
    pa.sample_type = PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_TIME |
                     PERF_SAMPLE_CALLCHAIN | PERF_SAMPLE_REGS_USER;
    pa.sample_regs_user = (1ull << 33) - 1;        /* x0-x30, sp, pc */
    pa.exclude_kernel = 1;
    pa.exclude_hv = 1;
    pa.exclude_callchain_kernel = 1;
    pa.inherit = inherit;
    pa.use_clockid = 1;
    pa.clockid = CLOCK_MONOTONIC;
    return syscall(__NR_perf_event_open, &pa, tid, -1, -1, 0);
}

static void xemu_hw_arm(void)
{
    MemoryRegion *mr;
    hwaddr xlat, l = 4;
    DIR *d;
    struct dirent *de;
    int vcpu = gettid(), bad = 0, err = 0, inh = 0;
    size_t sz = (XEMU_HW_PAGES + 1) * 4096;

    mr = address_space_translate(&address_space_memory, 0x35c08, &xlat, &l,
                                 true, MEMTXATTRS_UNSPECIFIED);
    if (!memory_region_is_ram(mr)) {
        fprintf(stderr, "kprcb-hwwp: 0x35c08 is not RAM\n");
        return;
    }
    xemu_hw_addr = (uint32_t *)((uint8_t *)memory_region_get_ram_ptr(mr) +
                                xlat);
    d = opendir("/proc/self/task");
    while (d && (de = readdir(d)) && xemu_hw_nev < XEMU_HW_MAX) {
        int tid = atoi(de->d_name), fd;
        void *m = MAP_FAILED;

        if (tid <= 0 || tid == vcpu) {
            continue;
        }
        fd = xemu_hw_attach(tid, 1);
        if (fd >= 0) {
            m = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            if (m == MAP_FAILED) {
                close(fd);
                fd = -1;
            } else {
                inh++;
            }
        }
        if (fd < 0) {                  /* inherited events may not mmap */
            fd = xemu_hw_attach(tid, 0);
            if (fd >= 0) {
                m = mmap(NULL, sz, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
            }
        }
        if (fd < 0 || m == MAP_FAILED) {
            bad++;
            err = errno;
            continue;
        }
        xemu_hw_ev[xemu_hw_nev].fd = fd;
        xemu_hw_ev[xemu_hw_nev].tid = tid;
        xemu_hw_ev[xemu_hw_nev].map = m;
        xemu_hw_nev++;
    }
    if (d) {
        closedir(d);
    }
    fprintf(stderr, "kprcb-hwwp: host %p (guest phys 0x35c08) armed in %d "
            "threads (%d inheriting), %d failed (errno %d), vCPU %d skipped\n",
            (void *)xemu_hw_addr, xemu_hw_nev, inh, bad, err, vcpu);
}

static unsigned xemu_hw_count(void)
{
    unsigned n = 0;
    int i;

    for (i = 0; i < xemu_hw_nev; i++) {
        struct perf_event_mmap_page *mp = xemu_hw_ev[i].map;
        n += (unsigned)(__atomic_load_n(&mp->data_head, __ATOMIC_ACQUIRE) -
                        mp->data_tail);
    }
    return n;
}

static void xemu_hw_dump(void)
{
    int i;

    fprintf(stderr, "kprcb-hwwp: %u bytes of samples in %d rings; now %08x\n",
            xemu_hw_count(), xemu_hw_nev, xemu_hw_addr ? *xemu_hw_addr : 0);
    for (i = 0; i < xemu_hw_nev; i++) {
        struct perf_event_mmap_page *mp = xemu_hw_ev[i].map;
        uint8_t *data = (uint8_t *)mp + 4096;
        uint64_t size = XEMU_HW_PAGES * 4096;
        uint64_t head = __atomic_load_n(&mp->data_head, __ATOMIC_ACQUIRE);
        uint64_t pos = mp->data_tail;
        int shown = 0;

        while (pos < head && shown < 16) {
            uint64_t rec[512];
            struct perf_event_header *h;
            uint64_t k, *q;
            char comm[32] = "?", path[64];
            FILE *f;

            /* copy out one record, handling ring wrap */
            for (k = 0; k < sizeof(struct perf_event_header); k++) {
                ((uint8_t *)rec)[k] = data[(pos + k) % size];
            }
            h = (struct perf_event_header *)rec;
            if (h->size < sizeof(*h) || h->size > sizeof(rec)) {
                break;
            }
            for (k = 0; k < h->size; k++) {
                ((uint8_t *)rec)[k] = data[(pos + k) % size];
            }
            pos += h->size;
            if (h->type != PERF_RECORD_SAMPLE) {
                fprintf(stderr, "kprcb-hwwp: ring %d record type %u\n",
                        xemu_hw_ev[i].tid, h->type);
                continue;
            }
            shown++;
            q = (uint64_t *)(h + 1);
            {
                uint64_t ip = q[0];
                uint32_t pid = q[1], tid = q[1] >> 32;
                uint64_t t = q[2], nr = q[3], *ips = &q[4], *regs, abi;
                uint32_t insn = 0, rt;
                Dl_info di;
                uint64_t j;

                regs = &ips[nr];
                abi = regs[0];
                regs++;
                snprintf(path, sizeof(path), "/proc/self/task/%u/comm", tid);
                f = fopen(path, "r");
                if (f) {
                    if (fgets(comm, sizeof(comm), f)) {
                        comm[strcspn(comm, "\n")] = 0;
                    }
                    fclose(f);
                }
                if (dladdr((void *)ip, &di)) {
                    insn = *(uint32_t *)ip;
                }
                rt = insn & 0x1f;
                fprintf(stderr, "kprcb-hwwp: t=%llu pid %u tid %u (%s) "
                        "pc=%llx insn=%08x rt=x%u val=%016llx abi=%llu\n",
                        (unsigned long long)t, pid, tid, comm,
                        (unsigned long long)ip, insn, rt,
                        (unsigned long long)(abi && rt < 31 ? regs[rt] : 0),
                        (unsigned long long)abi);
                for (j = 0; j < nr && j < 14; j++) {
                    if (ips[j] >= (uint64_t)-4096) {   /* context marker */
                        continue;
                    }
                    if (dladdr((void *)ips[j], &di) && di.dli_fname) {
                        fprintf(stderr, "kprcb-hwwp:     %s+0x%llx %s\n",
                                strrchr(di.dli_fname, '/') ?
                                strrchr(di.dli_fname, '/') + 1 : di.dli_fname,
                                (unsigned long long)(ips[j] -
                                                     (uintptr_t)di.dli_fbase),
                                di.dli_sname ? di.dli_sname : "");
                    } else {
                        fprintf(stderr, "kprcb-hwwp:     %llx\n",
                                (unsigned long long)ips[j]);
                    }
                }
            }
        }
    }
}

static void xemu_kprcb_watch_arm(CPUState *cs)
{
    static bool done;
    char v[PROP_VALUE_MAX] = { 0 };

    if (done) {
        return;
    }
    done = true;
    if (__system_property_get("debug.xemu.kprcb_watch", v) <= 0 ||
        (v[0] != '1' && v[0] != '2')) {
        return;
    }
    xemu_wp_log_hook = xemu_kprcb_wp_log;
    cpu_watchpoint_insert(cs, 0x80035c04, 8, BP_MEM_WRITE | XEMU_BP_LOG,
                          NULL);
    cpu_watchpoint_insert(cs, 0xf0035c04, 8, BP_MEM_WRITE | XEMU_BP_LOG,
                          NULL);
    fprintf(stderr, "kprcb-watch: armed on CurrentThread/NextThread\n");
    if (v[0] == '1') {
        xemu_hw_arm();
    }
    if (__system_property_get("debug.xemu.watch_va", v) > 0) {
        xemu_watch_va = strtoul(v, NULL, 16);
        if (xemu_watch_va) {
            cpu_watchpoint_insert(cs, xemu_watch_va, 4,
                                  BP_MEM_WRITE | XEMU_BP_LOG, NULL);
            fprintf(stderr, "kprcb-watch: also watching %08x\n",
                    xemu_watch_va);
        }
    }
}

void xemu_kprcb_watch_dump(void);
void xemu_kprcb_watch_dump(void)
{
    unsigned n = MIN(xemu_kw_head, XEMU_KW_N), i;

    xemu_hw_dump();
    fprintf(stderr, "kprcb-watch: %u stores logged, last %u (oldest first):\n",
            xemu_kw_head, n);
    for (i = n; i > 0; i--) {
        struct xemu_kw *e = &xemu_kw_ring[(xemu_kw_head - i) % XEMU_KW_N];
        uint32_t pc = e->d0 < 0x1000 ? ((e->eip & ~0xfffu) | e->d0) : e->d0;

        fprintf(stderr, "kprcb-watch: -%03u t=%llu ints=%u pc=%08x "
                "(eip=%08x) st %08x/%u cur=%08x next=%08x irql=%02x "
                "if=%d | eax=%08x ecx=%08x edx=%08x ebx=%08x esp=%08x "
                "ebp=%08x esi=%08x edi=%08x | stk %08x %08x %08x %08x "
                "%08x %08x\n", i - 1, (unsigned long long)e->t, e->ints, pc,
                e->eip, e->addr, e->len, e->cur, e->next, e->irql,
                !!(e->eflags & IF_MASK), e->regs[0], e->regs[1], e->regs[2],
                e->regs[3], e->regs[4], e->regs[5], e->regs[6], e->regs[7],
                e->stk[0], e->stk[1], e->stk[2], e->stk[3], e->stk[4],
                e->stk[5]);
    }
}

/*
 * Guest clock wait -> hlt (OPEN-WORK C14).  Halo's frame limiter spins
 *
 *     mov eax,[ctr]; cmp [ctr+4],esi; jl loop; jg done; cmp eax,edi; jb loop
 *
 * until a 64-bit counter that only a timer interrupt advances reaches
 * ESI:EDI (FINDINGS M/O).  In about 40% of runs Halo waits this way instead
 * of blocking in the kernel: ~725k iterations and ~7 ms of vCPU per frame,
 * the CPU ~15 C hotter.  Since only an interrupt can end the wait, halting
 * until the next one is exact: the interrupt returns to the mov and the loop
 * re-tests.  The translator matches the instruction pattern, not an address.
 * debug.xemu.spin_halt=0 turns it off (re-read about once a second).
 */
int g_xemu_spin_halt = 1;
unsigned long long xemu_spin_halts;

void helper_xemu_spin_wait(CPUX86State *env, uint32_t ctr)
{
    CPUState *cs;
    uint32_t lo, tlo;
    int32_t hi, thi;

    if (!g_xemu_spin_halt || !(env->eflags & IF_MASK) ||
        (env->hflags & HF_INHIBIT_IRQ_MASK)) {
        return;
    }
    lo = cpu_ldl_data_ra(env, ctr, GETPC());
    hi = (int32_t)cpu_ldl_data_ra(env, ctr + 4, GETPC());
    thi = (int32_t)env->regs[R_ESI];
    tlo = (uint32_t)env->regs[R_EDI];
    if (hi > thi || (hi == thi && lo >= tlo)) {
        return;                         /* reached: the loop exits */
    }
    xemu_spin_halts++;
    xemu_idle_halts++;
    asm volatile("mrs %0, cntvct_el0" : "=r"(xemu_idle_halt_t0));
    cs = env_cpu(env);
    cs->halted = 1;
    cs->exception_index = EXCP_HLT;
    cpu_loop_exit(cs);
}

void helper_xemu_idle(CPUX86State *env)
{
    xemu_kprcb_watch_arm(env_cpu(env));
    if (xemu_wp_log_hook) {
        xemu_thread_dump_poll(env_cpu(env));
    }
    uint32_t ebp = env->regs[R_EBP], ebx = env->regs[R_EBX];
    CPUState *cs;

    if (!g_xemu_idle_halt || !(env->eflags & IF_MASK)) {
        return;
    }
    if (cpu_ldl_data_ra(env, ebp, GETPC()) != ebp ||
        cpu_ldl_data_ra(env, ebx + 0x2c, GETPC()) != 0) {
        return;                    /* a DPC or a thread is waiting: run it */
    }
    if (g_xemu_idle_halt == 4) {      /* diagnostic: the reads only */
        return;
    }
    if (g_xemu_idle_halt == 2) {      /* diagnostic: delay, no halt */
        g_usleep(100);
        return;
    }
    xemu_idle_halts++;
    asm volatile("mrs %0, cntvct_el0" : "=r"(xemu_idle_halt_t0));
    cs = env_cpu(env);
    do_end_instruction(env);
    cs->halted = 1;
    cs->exception_index = EXCP_HLT;
    cpu_loop_exit(cs);
}

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
 * debug.xemu.idle_halt: 0 off (default), 1 halt; diagnostics 2 = sleep
 * 100 us instead of halting, 4 = do the reads only.  Re-read about once a
 * second so A/B can toggle it inside one process.
 *
 * OFF BY DEFAULT: with it on, Jade Empire's boot bugchecks (0x0A, corrupt
 * Prcb->NextThread) in ~60% of launches at the same loading step -- and so
 * does a plain 100 us delay here, so the cause is a timing race elsewhere
 * that a slower idle loop exposes, not the halt itself (FINDINGS BQ).
 */
int g_xemu_idle_halt;             /* OFF: crashes Jade Empire's boot (BQ) */
unsigned long long xemu_idle_halts;
uint64_t xemu_idle_halt_t0;       /* cntvct at the halt, 0 when running */

void helper_xemu_idle(CPUX86State *env)
{
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

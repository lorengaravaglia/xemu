/*
 * AArch64 assembly-based coroutine switcher for Android.
 *
 * Replaces coroutine-sigaltstack.c because bionic's sigsetjmp/siglongjmp on
 * Android 12+ (API 31+) signs the saved return address using SP as the
 * authentication modifier. When siglongjmp restores a context onto a different
 * coroutine stack (different SP), the AUTIA authentication fails and produces
 * an invalid address, crashing at qemu_coroutine_switch+N.
 *
 * This implementation saves/restores callee-saved AArch64 integer registers
 * directly via assembly, with no pointer authentication. The code is compiled
 * with -mbranch-protection=none so the compiler also adds no PAC instructions.
 *
 * AArch64 callee-saved registers: x19-x28, x29 (fp), x30 (lr), sp.
 */

#include "qemu/osdep.h"
#include "qemu/coroutine_int.h"
#include <unistd.h>
#include <android/log.h>

/* -------------------------------------------------------------------------
 * Data structures
 * ---------------------------------------------------------------------- */

/*
 * Saved register context. Field order MUST match the assembly offsets below.
 *   offset  0: x19
 *   offset  8: x20
 *   offset 16: x21
 *   offset 24: x22
 *   offset 32: x23
 *   offset 40: x24
 *   offset 48: x25
 *   offset 56: x26
 *   offset 64: x27
 *   offset 72: x28
 *   offset 80: x29 (fp)
 *   offset 88: x30 (lr)
 *   offset 96: sp
 */
typedef struct {
    uint64_t x19, x20, x21, x22, x23, x24, x25, x26, x27, x28;
    uint64_t fp;   /* x29 */
    uint64_t lr;   /* x30 — return address */
    uint64_t sp;   /* stack pointer */
} AArch64Context;

typedef struct {
    Coroutine base;
    void *stack;
    size_t stack_size;
    AArch64Context ctx;
    CoroutineAction pending_action; /* action delivered to this coroutine on next resume */
} CoroutineAndroid;

/* Per-thread state */
static __thread CoroutineAndroid t_leader;  /* leader (main-thread) coroutine */
static __thread Coroutine       *t_current; /* currently running coroutine */

/* Diagnostic log (ERROR level so it always shows regardless of logcat filter) */
#define CORO_ERR(...) __android_log_print(ANDROID_LOG_ERROR, "xemu-coro-asm", __VA_ARGS__)

/* -------------------------------------------------------------------------
 * Assembly context switch
 *
 * coroutine_asm_switch(from, to):
 *   Save callee-saved integer registers + SP to *from.
 *   Restore callee-saved integer registers + SP from *to.
 *   Branch to the LR loaded from *to  (plain RET, no RETAA).
 *
 * Declared __attribute__((naked)) so the compiler emits no prologue/epilogue.
 * Compiled with -mbranch-protection=none so no PACIASP/AUTIASP are added.
 * ---------------------------------------------------------------------- */
__attribute__((naked)) static void
coroutine_asm_switch(AArch64Context *from, AArch64Context *to)
{
    __asm__(
        /* --- Save caller-saved registers to *from (x0) --- */
        "stp x19, x20, [x0,  #0]\n"
        "stp x21, x22, [x0, #16]\n"
        "stp x23, x24, [x0, #32]\n"
        "stp x25, x26, [x0, #48]\n"
        "stp x27, x28, [x0, #64]\n"
        "stp x29, x30, [x0, #80]\n"   /* fp, lr */
        "mov x9,  sp\n"
        "str x9,  [x0, #96]\n"         /* sp */

        /* --- Restore registers from *to (x1) --- */
        "ldp x19, x20, [x1,  #0]\n"
        "ldp x21, x22, [x1, #16]\n"
        "ldp x23, x24, [x1, #32]\n"
        "ldp x25, x26, [x1, #48]\n"
        "ldp x27, x28, [x1, #64]\n"
        "ldp x29, x30, [x1, #80]\n"   /* fp, lr */
        "ldr x9,  [x1, #96]\n"         /* sp */
        "mov sp,  x9\n"

        /* Branch to restored LR — plain RET, no pointer authentication */
        "ret\n"
    );
}

/* -------------------------------------------------------------------------
 * Coroutine trampoline
 *
 * Entered when a newly created coroutine is first switched to.
 * t_current is already set to the new coroutine by qemu_coroutine_switch().
 * ---------------------------------------------------------------------- */
static void coroutine_trampoline(void)
{
    CoroutineAndroid *self = (CoroutineAndroid *)t_current;
    Coroutine *co = &self->base;

    while (true) {
        co->entry(co->entry_arg);
        qemu_coroutine_switch(co, co->caller, COROUTINE_TERMINATE);
    }
}

/* -------------------------------------------------------------------------
 * Public coroutine API
 * ---------------------------------------------------------------------- */

Coroutine *qemu_coroutine_new(void)
{
    CoroutineAndroid *co = g_new0(CoroutineAndroid, 1);

    co->stack_size = COROUTINE_STACK_SIZE;
    co->stack = qemu_alloc_stack(&co->stack_size);

    /*
     * Set up the initial saved context so that the first switch to this
     * coroutine lands in coroutine_trampoline() with a fresh stack.
     *   lr = address of coroutine_trampoline
     *   sp = top of allocated stack, 16-byte aligned (stacks grow downward)
     *   fp = 0  (signals end of call chain in stack traces)
     *   x19-x28 = 0  (caller-saved by our switch, don't matter for first entry)
     */
    uint64_t stack_top = ((uint64_t)co->stack + co->stack_size) & ~(uint64_t)15;

    co->ctx.lr = (uint64_t)coroutine_trampoline;
    co->ctx.sp = stack_top;
    co->ctx.fp = 0;

    QSIMPLEQ_INIT(&co->base.co_queue_wakeup);

    return &co->base;
}

void qemu_coroutine_delete(Coroutine *co_)
{
    CoroutineAndroid *co = DO_UPCAST(CoroutineAndroid, base, co_);
    qemu_free_stack(co->stack, co->stack_size);
    g_free(co);
}

CoroutineAction qemu_coroutine_switch(Coroutine *from_, Coroutine *to_,
                                      CoroutineAction action)
{
    CoroutineAndroid *from = DO_UPCAST(CoroutineAndroid, base, from_);
    CoroutineAndroid *to   = DO_UPCAST(CoroutineAndroid, base, to_);

    /*
     * Validate pointers before any dereference. A garbage to_ pointer
     * (e.g. 0x100000067) means a corrupted coroutine queue entry reached
     * us — crash here with diagnostic context rather than a silent SIGSEGV
     * deep inside coroutine_asm_switch.
     */
    if ((uintptr_t)from_ < 0x10000000ULL || !from_) {
        CORO_ERR("qemu_coroutine_switch: INVALID from_=%p to_=%p action=%d tid=%d",
                 (void *)from_, (void *)to_, (int)action, gettid());
        abort();
    }
    if ((uintptr_t)to_ < 0x10000000ULL || !to_) {
        CORO_ERR("qemu_coroutine_switch: INVALID to_=%p from_=%p action=%d tid=%d "
                 "from->caller=%p from->entry=%p",
                 (void *)to_, (void *)from_, (int)action, gettid(),
                 (void *)from_->caller, (void *)from_->entry);
        abort();
    }

    /*
     * Set t_current before the switch so that coroutine_trampoline() (if
     * this is the first entry into 'to') sees the right coroutine pointer.
     * Also store 'action' so the resumed coroutine can return it.
     */
    t_current = to_;

    /*
     * Store the action in the DESTINATION coroutine's struct.
     * After the switch, we read it from the SELF (from) coroutine's struct.
     * This avoids TLS: no compiler register-caching hazard since the address
     * is a simple struct field pointer, not a TLS computation.
     */
    to->pending_action = action;

    coroutine_asm_switch(&from->ctx, &to->ctx);

    /*
     * When we arrive back here, from->pending_action has been set by whoever
     * switched back to us. Read it directly from the struct — no TLS, no
     * callee-saved-register aliasing issues.
     */
    return from->pending_action;
}

Coroutine *qemu_coroutine_self(void)
{
    if (!t_current) {
        QSIMPLEQ_INIT(&t_leader.base.co_queue_wakeup);
        t_current = &t_leader.base;
    }
    /*
     * Defensive: if co_queue_wakeup.sqh_last is NULL the queue was never
     * initialized (or was zeroed by a use-after-free / struct reuse bug).
     * Reinitializing here prevents the NULL-dereference crash in aio_co_enter
     * that writes through sqh_last in QSIMPLEQ_INSERT_TAIL.
     */
    if ((uintptr_t)t_current < 0x10000000ULL) {
        CORO_ERR("qemu_coroutine_self: t_current is GARBAGE=%p on tid=%d — aborting",
                 (void *)t_current, gettid());
        abort();
    }
    if (t_current->co_queue_wakeup.sqh_last == NULL) {
        CORO_ERR("qemu_coroutine_self: co=%p sqh_last=NULL on tid=%d — reinitializing",
                 (void *)t_current, gettid());
        QSIMPLEQ_INIT(&t_current->co_queue_wakeup);
    }
    return t_current;
}

bool qemu_in_coroutine(void)
{
    return t_current && t_current->caller;
}

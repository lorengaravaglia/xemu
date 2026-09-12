# 03 — Making the guest execute LESS code

STATUS: complete (analysis + measurement plan; **nothing measured on device** —
the brief forbade touching the device, so every number below is either read out
of the source or inferred and labelled as such)
Date: 2026-09-11

Angle: every attempt to reduce cost-per-guest-instruction has hit a wall. This
file looks at reducing the ~4.4-6M guest instructions executed per frame.
Xbox-only shortcuts are acceptable in this fork.

---

## RANKED CONCLUSIONS

### 0. Do this first: one instrumentation run, ~60 lines, answers everything below
None of the leads here should be built before **M0 + M3 + M4** exist (details in
Q1.d). Together they are ~30 lines riding on instrumentation that is already in
the tree, and they produce: does the guest ever HLT; how many MMIO accesses per
frame and to which registers; and **whether the polling happens in over-budget
frames or only in slack time**. That last one is the hinge — see conclusion 1.
Add M2's per-TB histogram (~60 lines, needs the existing `debug.xemu.nochain=1`)
and the same run also settles 00-FINDINGS leads 2 and 3.

### 1. Busy-wait elimination — highest ceiling, but its value is NOT YET ESTABLISHED and one measurement decides it
VERIFIED: the guest essentially never halts (a halted vCPU would park in
`rr_wait_io_event`, `tcg-accel-ops-rr.c:108-117`, and consume no CPU; measured
utilisation is 96-98% at a locked 30 fps). INFERRED: in a light scene ~64% of
vCPU time is spin. The mechanism is traced in Q1.b — `NV097_FLIP_STALL` parks the
pusher, the pushbuffer fills, and the CPU polls the GPU's `DMA_GET` pointer.

**But**: spin removal raises frame rate only if the spin happens *inside frames
that are over budget*. If the guest only spins when it is ahead of schedule, the
payoff is power/thermal (real here — ~8% thermal drift per session, same size as
the effects being chased) plus reduced BQL/`pfifo.lock` pressure on the 20.4% GPU
thread, and **not fps**. M4 decides this in one run. Do not build anything first.

**This also puts a crack in 00-FINDINGS section F.** "Heavy frames run 1.5x more
code" rests on host instructions retired, and that metric **cannot tell game work
from spin iterations** — a spin iteration is real, high-IPC retired work. M4
tests both claims at once.

### 2. MMIO fast path — small, cheap, low-risk, and QEMU already has the switch
Every guest MMIO access takes **two** mutexes: the **BQL**, unconditionally, in
`do_ld_mmio_beN` (`cputlb.c:2024`), plus the device's own lock (`pgraph.c:53`,
`pfifo.c:47`, `user.c:32`). `pgraph_write` takes three. QEMU already has
`MemoryRegion::lockless_io` and the **port-I/O** path honours it
(`physmem.c:3322-3335`) — the TCG softmmu MMIO path simply does not. ~10 lines to
fix, ~15 more for an atomic read fast path in the NV2A read handlers (whose
`default:` arms are single word loads, and which never touch the IRQ path).
Estimated 250-500 cycles -> 80-150 per access. **Worth it only if M3 shows more
than ~2,000-4,000 NV2A accesses per frame** (that is what 1% of a frame costs).

### 3. `rep movs` / `rep stos` -> host `memcpy` — the one HLE-shaped win that is real and self-contained
VERIFIED: QEMU emits string instructions as a per-element in-TB loop
(`translate.c:1564-1680`); `rep movsd` of 4 KB is 1,024 iterations at ~20-30 host
instructions each. Replacing the bulk case with a real `memcpy` under
`probe_access` is ~150 lines and is an observable-equivalence change inside a
single guest instruction — not a kernel-API hook, so no per-title fragility.
**Falsify it in 15 lines first**: one counting helper call per `rep` execution
passing ECX. Kill if under ~200k iterations/frame.

### 4. Emulated MMIO is ~7x FASTER than real hardware, which inflates the guest's own polling loops
A real NV2A register read is a ~1 us bus transaction; ours is ~250-500 cycles. A
wait loop that spun 1,000 times on real hardware spins ~7,000 times here. This is
why conclusion 1 has a bigger prize than "real hardware did this too" suggests.
Counter-example worth knowing: the ACPI PM timer (the Xbox performance counter) is
slow on real hardware too and is *already* BQL-free here (`hw/acpi/core.c:558`) —
do not chase it.

### 5. NEGATIVE — interrupts and timers are not where the money is
~1,250 guest interrupts/s (60 Hz vblank + 187.5 Hz APU + ~1 kHz PIT) = ~42 per
frame. Even at a generous 30,000 host instructions each that is <1% of a frame.
The only emulator-only excess found is the **two OHCI controllers' 1 kHz SOF
timers** (~66 main-loop BQL acquisitions per frame with no hardware analogue), and
that lands on the main-loop thread, not the vCPU. **Deprioritise Q3.**

### 6. NEGATIVE — D3D8-runtime HLE is not viable here
The D3D8 runtime is statically linked per title with no fixed address and no
export table; hooking it means per-XDK-version signature scanning (the
Cxbx-Reloaded approach), a permanently-maintained per-title-fragile effort that
would put xemu's correctness advantage at risk. Kernel HLE *is* tractable (fixed
base, same binary every title, precedent at `nv2a.c:218-261`) — **but do not pick
targets before M1/M2 show whether kernel PCs are even hot.**

---

## Checklist
- [x] Q1 busy-waiting  - [x] Q2 MMIO  - [x] Q3 interrupts  - [x] Q4 HLE  - [x] Q5 other

---

## Detail


### Raw material gathered (VERIFIED by reading code, not yet measured)

**Every guest MMIO access takes TWO mutexes, not one.**
- `accel/tcg/cputlb.c:2024` — `do_ld_mmio_beN()` does `BQL_LOCK_GUARD()` before
  `memory_region_dispatch_read()`. Same for the store side
  (`cputlb.c:2563`, `:2583`). So the **BQL** is taken/released on *every*
  guest MMIO read and write, from the vCPU thread.
- Then the device handler takes its own lock:
  `hw/xbox/nv2a/pgraph/pgraph.c:53` `qemu_mutex_lock(&pg->lock)` in
  `pgraph_read`; `hw/xbox/nv2a/pfifo.c:47` `qemu_mutex_lock(&d->pfifo.lock)` in
  `pfifo_read`; `hw/xbox/nv2a/user.c:32` same in `user_read`.
- `pgraph_write` (`pgraph.c:96-97`) takes **pfifo.lock AND pg->lock**, i.e.
  three mutexes counting the BQL.
- These are the *contended* locks: `pg->lock` and `pfifo.lock` are held by the
  NV2A pfifo thread, which 00-FINDINGS section A measures at 20.4% of all CPU.

**There is already an in-tree guest-spin-loop detector.**
`hw/xbox/nv2a/nv2a.c:217-261`: on every 6th vblank the Android build samples
`CS.base + EIP` on the vCPU and compares it to the literal constant
`0x8001b02f`, an Xbox-kernel spin-wait. If the guest has sat there >= 2 s with
`[EBX+0x2C] == 0` (EBX == 0x80035bdc) it *writes 1 into guest memory* to
unblock it. This proves (a) the guest demonstrably spins at a fixed kernel PC,
and (b) sampling the vCPU PC from another thread is already an accepted,
working technique in this tree — the Q1 measurement can reuse it verbatim.

**PAUSE is a full cpu_loop_exit.** `target/i386/tcg/misc_helper.c:91-102`
`helper_pause()` sets `exception_index = EXCP_INTERRUPT` and calls
`cpu_loop_exit()`; codegen at `target/i386/tcg/emit.c.inc:2818-2824` ends the
TB (`DISAS_NORETURN`). So if the guest spin loops contained `rep nop`, each
iteration would cost a longjmp to the outer dispatch loop. (Whether Xbox code
uses PAUSE at all is unknown — it is a uniprocessor machine, so probably rare.)

---

## Q1 — Busy-waiting

### Q1.a The guest essentially never halts (VERIFIED, code + existing measurement)

- If the guest executed `HLT`, `cpu_handle_halt()` (`accel/tcg/cpu-exec.c:696-712`)
  returns `EXCP_HALTED`, the rr loop stops running the CPU and parks in
  `rr_wait_io_event()` -> `qemu_cond_wait_bql(first_cpu->halt_cond)`
  (`accel/tcg/tcg-accel-ops-rr.c:108-117`). A halted vCPU therefore consumes
  **no** CPU.
- Measured vCPU utilisation is **96-98% while frame rate is locked at 30 fps**,
  in two scenes whose per-frame work differs by 2.8x (recorded in the comment at
  `ui/xemu.c:1598-1604`).
- Those two facts together mean the guest is burning host cycles during the time
  it is *ahead of schedule*, i.e. it spins rather than halting.
  In the light scene roughly `1 - 1/2.8 = 64%` of vCPU time must be spin.
  (INFER, from the 2.8x + pinned-utilisation pair; not directly counted.)

### Q1.b What it most likely spins on (INFER, mechanism traced in code)

The NV2A flip handshake makes a CPU-side spin structurally necessary:
1. The game submits `NV097_FLIP_STALL` into the pushbuffer.
2. The **pusher stalls**, not the CPU: `pfifo_stall_for_flip()`
   (`hw/xbox/nv2a/pfifo.c:126-155`) parks the pfifo thread while
   `pg->waiting_for_flip` and `READ_3D == WRITE_3D`.
3. The stall clears only when the guest's vblank ISR writes
   `NV_PGRAPH_INCREMENT` with `READ_3D` (`hw/xbox/nv2a/pgraph/pgraph.c:125-137`).
4. Between (1) and (3) the game keeps writing commands, the pushbuffer fills, and
   the CPU must wait for space — which on Xbox is done by **polling the GPU's
   `DMA_GET` pointer**, i.e. a memory-mapped read of
   `NV_PFIFO_CACHE1_DMA_GET` (`nv2a_regs.h:166`, block PFIFO at BAR0+0x2000) or
   the channel alias `NV_USER_DMA_GET` (`nv2a_regs.h:770`, BAR0+0x800000+0x44).
   The pusher advances that register at `pfifo.c:476` (`*dma_get = dma_get_v;`).

So the expected spin is `mov eax,[0xFD003244]; cmp; jne` — a 3-4 instruction TB
whose every iteration is a **full MMIO trap** (cost in Q2).

There is a second, already-proven spin: the Xbox kernel wait at guest PC
`0x8001b02f`, hard-coded in `hw/xbox/nv2a/nv2a.c:218-261`.

### Q1.c THE CAVEAT THAT DECIDES WHETHER THIS IS WORTH ANYTHING

Spin removal only raises frame rate if the spin happens **inside frames that are
over budget**. If the guest spins only when it is ahead of schedule, removing it
buys zero fps and only buys power/thermal headroom (non-trivial here — the
project already measures ~8% thermal drift per session, comparable to the
effects being chased, and `bench_cpu_temp_c()` already exists) plus reduced
BQL/pfifo.lock pressure on the 20.4% GPU thread.

**This also puts a crack in 00-FINDINGS section F.** That section concludes
"heavy frames genuinely run ~1.5x more code" from host instructions retired.
Host instructions retired **cannot distinguish game work from spin iterations** —
a spin iteration is real, high-IPC, retired host instructions. If heavy frames
spin more (e.g. more GPU work -> pushbuffer stays full longer -> more DMA_GET
polling), section F's "the game is just doing more" reading is incomplete.
Bucketing spin by frame weight tests both claims at once.

### Q1.d HOW TO MEASURE THE SPIN FRACTION — four probes, cheapest first

**M0 — "does the guest ever halt?" (2 lines, zero risk).**
One counter incremented at `accel/tcg/cpu-exec.c:858` (`cpu->halted = 1`) and
one in `helper_pause` (`target/i386/tcg/misc_helper.c:91`); print absolute
values plus per-frame rate in the existing bench report (`ui/xemu.c:1735`).
Print the ABSOLUTE value, per CLAUDE.md rule 1 — a plausible zero here is
exactly the kind of broken probe that has bitten this project five times.
*Kills or confirms Q1.a outright.* If HLT/frame turns out to be large, the
96-98% number means something else and the whole question changes.

**M1 — PC histogram by external sampling (~40 lines, zero vCPU overhead).**
Reuse the technique already working at `hw/xbox/nv2a/nv2a.c:230-236`: from the
vblank thread (or a new 1 kHz sampler thread) read
`cpu_env(first_cpu)->segs[R_CS].base + eip` into a small open-addressed
histogram; dump the top 32 at benchmark end.
Caveats to state up front: `env->eip` is only synced at TB boundaries and before
helpers, so this is biased toward TB-entry PCs and is a *locator*, not a
quantifier. That bias is harmless for the question being asked — a spin loop is
a tiny TB that re-syncs `eip` every few instructions, so it is over- not
under-represented. **Purpose: find the addresses.** Then disassemble those
guest PCs from a save state / `cpu_memory_rw_debug` dump to confirm the loop
shape.

**M2 — exact per-TB execution histogram (the decisive one, ~60 lines).**
`debug.xemu.nochain=1` already exists (branch `android-codegen-measure`) and
makes every TB exit return to the dispatcher, at which point
`accel/tcg/cpu-exec.c:1054` sees *every* TB execution. Add an open-addressed
hash keyed on `tb->pc` accumulating `{exec_count, icount}`; dump the top 40 with
`pc`, exec count, icount, and share of total guest instructions.
- A spin loop is unmistakable: `icount` of 2-5, an exec count orders of
  magnitude above everything else, and `tb->pc` inside its own guest page.
- Bias direction is SAFE: nochain makes the host much slower, so the guest is
  further behind schedule and spins *less*. The measured spin fraction is a
  **lower bound**. If even the lower bound is large, it is real.
- Free side benefit: the same dump answers 00-FINDINGS lead 2/3 (TB exit
  reasons, guest-insns-per-TB-entry) in the same run.

**M3 — per-register MMIO counters (~25 lines, essentially free, keep it on).**
`counts[addr >> 2]++` at the top of `pgraph_read` (`pgraph.c:48`),
`pfifo_read` (`pfifo.c:43`), `user_read` (`user.c:25`), `pmc_read`, `ptimer_read`,
plus the write side and `helper_inl`/`helper_inb` keyed by port. Dump the top 20
per frame. Directly answers Q2 volume AND names the polled status register,
confirming or refuting the DMA_GET hypothesis in Q1.b without any disassembly.

**M4 — the decision measurement: spin per frame bucket.**
`bench_tick()` (`ui/xemu.c:1648-1690`) already splits frames into cheap
(<= 1.0x budget) and expensive (> 1.2x budget) and accumulates per-bucket host
instructions. Add the M3 MMIO-read counter to the same two accumulators.
- If expensive frames poll *proportionally more* -> spin is inside the frames
  that matter, and Q1 is the top lead.
- If expensive frames poll ~zero -> spin lives only in slack time, Q1 is worth
  only power/thermal, and it should be dropped to a footnote.
**One run decides it.** Do M0+M3+M4 together; they are ~30 lines total and all
three ride on instrumentation that already exists.

### Q1.e If it is significant: how to skip, and what breaks

Two mechanisms, in increasing order of risk:

**(1) Sleep inside the MMIO read handler (localized, ~80 lines).**
In `pfifo_read`/`user_read`, when the same address is read N times consecutively
(N ~ 64) with no intervening guest write to any NV2A register and no pending
interrupt, wait on `d->pfifo.fifo_idle_cond`-style condvar with a short absolute
timeout instead of returning immediately. The pusher already broadcasts
`fifo_idle_cond` (`pfifo.c:525`) and `fifo_cond`, so the wake-up edge exists.
*Correctness risks, all real:*
- The vCPU holds the **BQL** at that point (`cputlb.c:2024`). It must drop it
  before sleeping or the whole machine deadlocks (main loop, vblank thread and
  the APU thread all need it). Dropping and re-taking the BQL mid-MMIO is
  exactly the kind of thing that produces once-an-hour hangs.
- Must bail out the instant an interrupt is pending, or vblank/audio IRQ latency
  regresses and audio glitches.
- Must never sleep past the next QEMU timer deadline.
- A false positive on a register whose value is changed by the *guest's own*
  subsequent writes cannot occur (no writes between reads, by construction), but
  one whose value changes only via a host thread that is itself blocked on the
  guest would hang. `waiting_for_flip` is precisely such a state — the pusher is
  parked waiting for the guest's vblank ISR. **Never sleep with interrupts
  disabled in the guest.**

**(2) Generic spin-TB detection at translation time (broader, ~200 lines).**
Mark a TB as a spin candidate when it is short, contains no stores, and ends in a
conditional branch back to its own `pc`. At runtime, if such a TB executes more
than K times consecutively with no interrupt taken and no MMIO write, set
`cpu->halted = 1` until the next interrupt. This is the standard
idle-skip used by Dolphin, PCSX2 and RPCS3.
*Risk:* a false positive is a hang, not a glitch. Mitigation is a hard cap: never
sleep past the next timer deadline, so a mistake costs at most one timer tick of
latency and can never deadlock. With that cap the risk is acceptable; without it
it is not.

Precedent in this tree for guest-specific intervention: `nv2a.c:218-261` already
pokes guest memory to break a kernel spin, so mechanism (2) is not a new class of
hack here.

---

## Q2 — MMIO volume and cost

### Q2.a What one NV2A register read costs (VERIFIED path, INFERRED cycle count)

Guest `mov eax,[0xFD00xxxx]` from generated code:
1. Inline TLB check fails (MMIO pages carry `TLB_MMIO` in the comparator), so the
   slow-path ld helper is called.
2. `mmu_lookup` -> flags & TLB_MMIO -> `do_ld_mmio_beN` (`cputlb.c:2010`).
3. `io_prepare` (`cputlb.c:1301`) — `iotlb_to_section`, `cpu->mem_io_pc` store,
   `can_do_io` check.
4. **`BQL_LOCK_GUARD()` (`cputlb.c:2024`) — a full BQL lock+unlock.**
5. `memory_region_dispatch_read` (`memory.c:1466`): alias check,
   `memory_region_access_valid`, then `access_with_adjusted_size`
   (`memory.c:515`) which also sets and clears the **reentrancy guard**
   (`memory.c:545-555`, a store into `mr->dev`), then
   `memory_region_read_accessor`, then `adjust_endianness`.
   NV2A's `MemoryRegionOps` set only `.read`/`.write` (`nv2a.c:112-136`), so
   `impl.max_access_size` defaults to 4 — one iteration, fine.
6. `pgraph_read`/`pfifo_read`/`user_read` — **a second mutex**
   (`pgraph.c:53`, `pfifo.c:47`, `user.c:32`) — plus a trace hook.

**Estimate: 250-500 host cycles per NV2A register access**, versus ~4-8 for an
ordinary guest load. Calibration: at 2.94 GHz, 98 Mcycles/frame budget, **1% of a
frame is only ~2,000-4,000 MMIO accesses.** That is the number M3 has to beat for
any of this to matter.

### Q2.b The BQL on the MMIO path is avoidable, and QEMU already has the switch

`MemoryRegion::lockless_io` exists (`include/system/memory.h:836`,
`memory_region_enable_lockless_io()` at `system/memory.c:2632`). The **port-I/O**
path honours it (`prepare_mmio_access`, `system/physmem.c:3322-3335`), and the
ACPI PM timer already uses it (`hw/acpi/core.c:558`).
**The TCG softmmu MMIO path does not**: `do_ld_mmio_beN` (`cputlb.c:2024`) and
the store equivalents (`cputlb.c:2563`, `:2583`) take `BQL_LOCK_GUARD()`
unconditionally. Teaching them to respect `mr->lockless_io` is ~10 lines.

Safety for NV2A specifically: the three read handlers `pgraph_read`,
`pfifo_read`, `user_read` do their own locking and **never call
`nv2a_update_irq`** (verified by reading all three) — so nothing on the read path
needs the BQL. The *write* handlers do (`pgraph_write` -> `nv2a_update_irq` ->
`pci_irq_assert`), so writes must keep it. `lockless_io` is per-region, not
per-direction, so either add a read-only variant of the flag or have the write
handlers take the BQL themselves.

### Q2.c Fast paths for a polled status register, cheapest first

**(1) Drop the device mutex on the read fast path (~15 lines, low risk).**
The `default:` arm of `pfifo_read` is `r = d->pfifo.regs[addr]` and of
`pgraph_read` is `r = pgraph_reg_r(pg, addr)` — single word loads. The codebase
already reads these registers without the lock elsewhere: `can_fifo_access()`
(`pfifo.c:100-103`) does `qatomic_read(&d->pgraph.regs_[NV_PGRAPH_FIFO])`, and
`pfifo_stall_for_flip` reads `waiting_for_flip` with `qatomic_read`
(`pfifo.c:134`). So an atomic-read fast path for the plain-register cases is
consistent with existing practice.

**(2) Combine with Q2.b to remove both mutexes.** Result: an NV2A status poll
costs the MMIO dispatch only, maybe 80-150 cycles instead of 250-500.

**(3) The real fix: stop trapping at all — map the poll target as RAM
(~100 lines, medium risk).**
`memory_region_init_rom_device()` gives a region whose **reads come from a RAM
backing store** (so the softmmu TLB maps it as ordinary RAM — no trap, ~5 cycles)
while **writes still call `ops->write`**. The pusher would publish `DMA_GET` into
that backing store with a release store at `pfifo.c:476` instead of only into
`d->pfifo.regs[]`.
Constraint: the softmmu TLB is page-granular, so the overlay must cover a whole
4 KB page. `NV_PFIFO_CACHE1_DMA_GET` is at PFIFO+0x1244 (`nv2a_regs.h:166`), so
the page PFIFO+0x1000..0x1FFF (all the CACHE1_* registers) would have to be
mirrored correctly — every register on that page, not just DMA_GET, since all
reads would then bypass `pfifo_read`. That is the risk: one register mirrored
wrong is a subtle hang, not a visible glitch.
The `NV_USER` alias (BAR0+0x800000, `user.c:25-70`) is a much easier target — the
whole 64 KB channel page holds only DMA_PUT/DMA_GET/REF — **but only helps if the
guest actually polls through the USER alias rather than the PFIFO one.** M3
settles which.

**All three are gated on M3.** If NV2A MMIO reads per frame come in under ~2,000,
none of this is worth building.

---

## Q3 — Interrupt and timer rates

Sources and rates (all VERIFIED from code):

| source | rate | where |
|---|---|---|
| NV2A vblank | **60.0 Hz** (`vblank_interval_ns = 16666666`) | `ui/xemu.c:565`, fired by `vblank_timer_thread` `ui/xemu.c:1289-1330` -> `nv2a_vga_gfx_update` `hw/xbox/nv2a/nv2a.c:199-214` -> `NV_PCRTC_INTR_0_VBLANK` -> `nv2a_update_irq` |
| MCPx APU frame | **187.5 Hz** (`EP_FRAME_US 5333`) | `hw/xbox/mcpx/apu/apu_regs.h:363`, `apu.c:257-300` |
| OHCI SOF x **2 controllers** | **1000 Hz each = 2000/s** | `usb_frame_time = NANOSECONDS_PER_SECOND / 1000` `hw/usb/hcd-ohci.c:1910`; two controllers at `hw/xbox/xbox.c:317-323` |
| i8254 PIT | whatever the guest programs (Xbox kernel ~1 kHz) | `hw/xbox/xbox.c:285` |

**Assessment — nothing here is obviously wrong, and one thing is:**

- vblank at 60.0 Hz vs a real NTSC Xbox's 59.94 Hz. Correct to 0.1%; irrelevant.
- APU at 187.5 Hz is the hardware's own 256-sample EP frame. Correct.
- **The OHCI pair is emulator-only overhead with no hardware analogue.**
  2,000 `ohci_frame_boundary` callbacks per second = **~66 per guest frame**, each
  on the main-loop thread **holding the BQL**, each doing a guest-memory HCCA read
  plus an ED list walk (`hcd-ohci.c:1215-1260`). On real hardware the HC does this
  in silicon and costs the CPU nothing. These do not add *guest* instructions, but
  they add ~66 BQL acquisitions per frame directly contending with the vCPU's MMIO
  BQL acquisitions from Q2.
  *Cheap measurement:* count `ohci_frame_boundary` entries and total time in it per
  frame, and count BQL contention (`bql_lock` trylock failures) from the vCPU.
  *Cheap mitigation if it shows up:* the second controller has no devices on it in
  a single-pad session — gate its `eof_timer` when no device is attached and no
  list is enabled.
- **Guest-side interrupt cost is bounded and small.** ~1,250 interrupts/s total
  (60 + 187 + ~1,000 PIT) is ~42 per frame. Even at a generous 30,000 host
  instructions per interrupt (emulator entry + guest ISR + DPC), that is ~1.3 M of
  ~164 M host instructions per frame = **<1%**. *I do not think Q3 is where the
  money is*, and I would not spend effort here before M0-M4.
- Interrupt *delivery* also breaks TB chaining (`cpu_interrupt` ->
  `icount_decr.u16.high`), forcing a dispatcher round trip. At ~42/frame this is
  noise. NOTE the separate path flagged in 00-FINDINGS section F:
  `async_safe_run_on_cpu` from the GPU thread calls `cpu_exit()`
  (`cpu-common.c:141`); that one is *not* rate-limited by anything and is worth
  counting in the same probe.

---

## Q4 — High-level emulation

### Q4.a What xemu intercepts today: NOTHING (VERIFIED)

Grepping the whole tree for `xboxkrnl`/`HLE`/kernel-export hooks returns only
unrelated x86 `hle` CPU-feature strings in `target/i386/cpu.c`. xemu is pure LLE:
it boots the real MCPX/Xbox BIOS and runs the real kernel. The single exception is
the guest-memory poke at `hw/xbox/nv2a/nv2a.c:218-261`.

### Q4.b The one HLE-shaped win I can justify without more data: `rep movs`/`rep stos`

**VERIFIED:** QEMU emits string instructions as an **in-TB per-element loop**, not
a bulk operation. `do_gen_rep` (`target/i386/tcg/translate.c:1564-1680`) emits
`loop:` { one element via `fn`, `mov ECX,cx_next`, `gen_update_cc_op`, condition,
`brcond` back to `loop` } with `REP_MAX 65535` (`translate.c:1564`). `gen_MOVS`
(`emit.c.inc:2624`) and `gen_STOS` (`emit.c.inc:4121`) both route through it.
So `rep movsd` of 4 KB = **1024 iterations**, each a guest load + guest store +
register writeback + cc bookkeeping — roughly 20-30 host instructions per dword,
against ~0.5 for a host `memcpy`.

**The change:** in the `REP` path, when `ECX` is above a threshold, call a helper
that resolves both ranges with `probe_access` and does a real `memcpy`/`memset`
over the host pointers, falling back to the loop on any complication.
*Correctness conditions* (all checkable in the helper): both ranges fully inside
RAM (not MMIO), no watchpoints, DF handled, overlap handled (`memmove`), page
crossings handled by chunking per page, and dirty tracking done **once per range**
via `memory_region_set_dirty` rather than per byte — which is strictly cheaper
than today, and matters here because the whole 64 MB has NV2A dirty logging armed
(`nv2a.c:288-289`). Interruptibility: chunk at, say, 4 KB and write `ECX` back
between chunks so an interrupt can still be taken at a restartable point.

**Size:** ~150 lines in `translate.c` + a helper.
**Falsifying measurement (~15 lines, do this first):** in `do_gen_rep`, emit one
call to a counting helper *before* the loop, passing `ECX`. That is one helper
call per `rep` *instruction execution*, not per iteration — negligible cost — and
gives the exact total iterations per frame. **Kill threshold: if total
rep-string iterations per frame are under ~200,000, drop the idea** (200k x ~25
host insns = 5 M of 164 M = 3%; below that it is not worth the risk).

### Q4.c Kernel HLE: do not design it before M1/M2

The Xbox kernel is at a fixed guest base (0x8001xxxx-0x8003xxxx — the hard-coded
address at `nv2a.c:220` confirms the range) and is the *same binary for every
title*, so hooking kernel exports is technically tractable: scan the export table
at boot, patch entry points with a trapping opcode, service it natively.
**But there is no evidence yet that kernel code is hot.** M1/M2 answer this
directly and for free: kernel PCs are trivially distinguishable from game code by
address. Do not pick HLE targets before that histogram exists.

### Q4.d D3D8 runtime HLE: realistically out of scope

The D3D8 runtime is **statically linked into each title**, so there is no fixed
address and no export table — it requires per-XDK-version signature scanning.
That is what Cxbx-Reloaded does, and it is a large, permanently-maintained,
per-title-fragile effort. I cannot justify it from anything measured here, and it
would put xemu's main correctness advantage at risk. **Recommend: no.**

---

## Q5 — Other reasons the guest runs more code than on real hardware

**1. Our MMIO is ~7x FASTER than real hardware, which makes polling loops
iterate ~7x more times. (INFER, but the arithmetic is solid.)**
A real NV2A register read is an AGP/bus transaction, order ~1 us, so a real Xbox
can poll `DMA_GET` at best ~1 M times/s. At the Q2.a estimate of 250-500 cycles on
a 2.94 GHz core we can poll ~6-12 M times/s. A "wait for pushbuffer space" loop
that would spin 1,000 times on real hardware spins ~7,000 times here.
**This is the cleanest statement of why Q1 exists**: the emulator does not just
fail to skip the idle, it *amplifies* it. It also means the instruction-count
prize from spin removal is larger than a naive "real hardware did this too"
intuition suggests.
Note the reverse case for completeness: the ACPI PM timer (the Xbox performance
counter, `hw/acpi/core.c:497-521`, with an `#ifdef XBOX` full-32-bit read) is
genuinely slow on real hardware too, and in xemu it is already BQL-free
(`core.c:558`) — so timer polling is *not* amplified. Do not chase it.

**2. The GPU thread and the vCPU serialise on the same two mutexes, and the vCPU
holds one of them while spinning.**
`pfifo_run_puller` drops `pfifo.lock` and takes `pgraph.lock` around **every
method** (`pfifo.c:204-206` and `:239-241`, `:248-250`). A vCPU spinning in
`pfifo_read` re-acquires `pfifo.lock` at ~6 M/s, competing with the puller's
re-acquisition on every single method. bionic's `pthread_mutex` is not fair, so a
spinner that re-locks immediately after releasing can starve the puller. The
convoy is self-reinforcing: slower puller -> pushbuffer drains slower -> guest
spins longer -> puller slower still.
*Cheap measurement:* replace the lock with `trylock`-then-`lock` in `pfifo_read`
and `pgraph_read` and count the failures; and count the puller's own failed
`pfifo.lock` acquisitions. Two counters. If both are near zero the convoy is
imaginary and this dies in one run.
(This is consistent with 00-FINDINGS section A "the vCPU does not block on the GPU
thread" — that observation was about *futex/kernel* time, and a short-hold
uncontended-fast-path mutex convoy shows up as wasted user cycles, not kernel
time. But it also means the effect is bounded; do not over-invest.)

**3. NV2A dirty-bit clearing re-arms `TLB_NOTDIRTY` on the guest's working set.**
Chain, all verified: the GPU thread calls
`memory_region_test_and_clear_dirty(d->vram, ...)` per texture / vertex range /
surface (`gl/texture.c:85`, `gl/vertex.c:47`, `gl/surface.c:877,1276`, and the vk
equivalents) -> `physical_memory_test_and_clear_dirty` (`physmem.c:1215`) ->
`physical_memory_dirty_bits_cleared` (`physmem.c:1007`) -> `tlb_reset_dirty_range_all`
-> `tlb_reset_dirty` (`cputlb.c:941-963`), which re-sets `TLB_NOTDIRTY` on every
matching vCPU TLB entry (`cputlb.c:922`). The guest's next store to each such page
then takes the `notdirty_write` slow path (`cputlb.c:1364-1386`).
Since `memory_region_set_log` is armed over the **whole 64 MB** (`nv2a.c:288-289`),
this covers all guest RAM. A 1 MB vertex buffer rewritten every frame is 256
re-trapped pages per frame on its own.
This *extends* 00-FINDINGS lead 4 with a second, distinct cost: lead 4 counted the
O(5,808-entry) scan (0.39% of vCPU); the **guest-side re-trapping** is unmeasured.
*Cheap measurement:* one counter in `notdirty_write` and one in `tlb_reset_dirty`,
printed per frame. ~4 lines. I do not expect this to be large (guess: a few
thousand traps/frame at ~400 cycles = <1%), but it is nearly free to check while
the other counters are being added.

**4. Frame-rate quantisation is not a bug but changes what a win looks like.**
vblank is a hard 60 Hz wall clock independent of emulator speed
(`ui/xemu.c:565,1289-1330`). A guest frame at 34 ms misses its slot and waits for
the *next* vblank -> 50 ms wall. So a 6% emulator speedup that moves 34 ms to
32 ms produces a 1.5x fps jump, and a 5% speedup produces none. This is already
implicit in the project's memory ("never optimize against average fps again") but
it is the reason the vCPU-ms/frame metric in `bench_tick` must stay the yardstick.

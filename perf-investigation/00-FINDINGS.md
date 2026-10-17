# xemu Android performance investigation — running findings

Master document. Each agent appends to its own file in this directory; this
file holds the compiled, cross-checked result. Survives job cleanup.

Measured baseline: combat ~32.5 ms/frame against a 33.3 ms budget. ~65% of
frames fit; ~35% cost ~150% of budget. Shipped so far: native x87 (-26%),
inline branch cache (-3.5%), targeted jump-cache invalidation (-2.6%).

---

## A. Established facts (measured on device, high confidence)

| fact | value |
|---|---|
| vCPU cycles in JIT-generated code | 84% |
| ... in libxemu.so (helpers/runtime) | 14% |
| ... kernel + libc | 2% |
| Within JIT: guest memory | 71.0% (qemu_ld 54.0, qemu_st 17.0) |
| Within JIT: alu/other | 14.2% |
| Within JIT: env loads / stores | 5.8% / 3.3% |
| Within JIT: branches/exits | 3.1% |
| Within JIT: helper calls | 2.7% |
| Top helpers | cc_compute_all 31%, lookup_tb_ptr 14%, qht_lookup 8% |
| Host IPC | **1.67-1.70** (was 1.80-1.85; section V) |
| Branch mispredicts | 0.10% of instructions |
| Cache misses | **L1D 216k + last-level 281-286k/frame; LL alone ~32% of frame** (section V) |
| Guest instructions/frame | **3.56M** (exact, nochain; section V) |
| Emitted per guest instruction | 82 bytes / 20.5 host instructions |
| Executed per guest instruction | **45 host instructions** (was ~33; section V) |
| Average TB | 6.5 guest instructions / 533 bytes |
| softmmu TLB | 256 entries, 0.06% miss rate |

### Thread breakdown (verified 2026-09-11 from perf capture)
- vCPU 67.7%; **NV2A GPU thread 20.4%**; third thread 4.0%; render 1.6%.
- The 20.4% thread is identified: `pfifo_thread`, `pgraph_method`,
  `pgraph_vk_bind_textures`, 26% inside `vulkan.adreno.so`, `memcpy_opt`
  7.7%, `memcmp` 2.3%, `memset` 1.5%, CAS atomics 6.7%.
- **The vCPU does not block on it.** No futex/mutex/wait symbols in the vCPU
  thread's top 35; kernel time 1.26%. Runs genuinely in parallel.

### Why fastmem was neutral (model, consistent with all data)
164M instructions / IPC 1.85 = 88.6M cycles = ~31.6ms vs ~32.5ms measured:
the model closes with no hidden stall term. The load bucket is ~18% of
instructions consuming 71% of cycles = 4x CPI premium, i.e. stall cycles on
guest *data*, with the sample landing on the load. Cortex-X3 is 6-wide with a
320-entry ROB and 3 AGUs, so the 8 TLB-check instructions -- independent of
guest dataflow, hitting L1 99.94% -- are absorbed free.

**"Guest memory is 71%" does NOT mean "translation overhead is 71%."** It
means translation is already free and the residue is the guest's own cache
behaviour.

**Operational rule:** removing instructions from the high-IPC bulk pays ~1:1;
removing them from the load sequence pays ZERO. Proven twice (barriers,
fastmem).

> **CORRECTED (section V).** The two A/B results stand, but the model above is
> circular: IPC is defined as instructions/cycles, so instructions/IPC = cycles
> closes for any workload regardless of its stall content, and it cannot show
> there is "no hidden stall term". Re-measured, it closes around a 56% backend
> stall. The right reading is that we are stalled fetching generated code and
> neither change shrank the footprint.

---

## B. Calibration against other translators (sourced)

| system | host/guest insns | mode |
|---|---|---|
| QEMU TCG x86_64 integer | 10.25 | user |
| QEMU TCG x86_64 all | 14.06 | user |
| **xemu (us)** | **20.5 emitted / ~33 executed** | **system** |
| Rosetta 2 | ~1.64 | user, AOT, private silicon |

20.5 is QEMU-normal for system mode, not an xemu defect. Published ceiling for
improving system-mode TCG: **1.15x on real applications** (CGO'24 learned
translation rules). All 2-5x results are user-mode or need EL2 virtualization
unavailable to an Android app. Hangover dropped its QEMU backend in 11.0 as
"by far the slowest option". No fast console emulator runs in system mode.

Sources: ahmedkrmn.github.io/TCG-Continuous-Benchmarking, dougallj on Rosetta 2,
arXiv:2402.09688 (CGO'24), USENIX ATC'19 Captive, FEX-Emu wiki/2212 release,
box86.org, Phoronix Hangover 11.0, Cortex-X3 optimization guide.

---

## C. Ranked leads (updated as agents report)

### 1. x86 flag emulation — CORRECTED 2026-09-11, mostly dead as an ARM-flags idea
My original entry here claimed `AXFLAG` would collapse the 6-instruction
FP-compare sequence. **That was wrong.** `AXFLAG` gives `Z = Z OR V`,
`C = C AND NOT V`. Against the four FCMP outcomes x86 needs `ZF = Z` and
`CF = NOT C`, but `PF = Z AND NOT C`, which is not a single AArch64 condition
(the available ones are EQ/NE/CS/CC/MI/PL/VS/VC/HI/LS/GE/LT/GT/LE; `Z AND !C`
is none of them). So you still need three CSETs. Best AXFLAG sequence is 7
instructions; the current code (tcg-target.c.inc:3300-3311) is also 7. **Win:
zero.** The "all six FlagM instructions grep to 0" observation was true; the
conclusion drawn from it was not.

**A FEX/Rosetta-style NZCV-resident design cannot be ported, and the reason is
structural** (verified): the softmmu TLB check emits `tcg_out_cmp` + `B_C`
(tcg-target.c.inc:1849-1853) on *every* guest memory access — roughly one per
2-3 guest instructions. **NZCV is clobbered constantly, so flags physically
cannot survive from producer to consumer.** FEX and Rosetta are user-mode with
no such check; removing it is fastmem, already dead.

**The hot integer pattern is already optimal:** `cmp eax,ebx; jl` inside a TB
compiles to exactly `CMP` + `B.LT` via the CCPrepare fusion
(translate.c:1290-1337); `test eax,eax; jz` becomes `CBZ`; single-bit tests
become `TBNZ`. That is what hand-written ARM emits.

**Where the flag cost actually leaks (both structural, both fixed by lead 2):**
- `cc_op` resets to `CC_OP_DYNAMIC` at every TB entry (translate.c:4501), and
  from DYNAMIC 6 of 8 Jcc groups call the full helper.
- The last flag producer in each TB always pays ~3 env stores, because globals
  are `TS_DEAD|TS_MEM` at TB end (tcg/tcg.c:3930-3948) so the stores survive
  even when nothing reads them. *Inside* a TB, dead-flag elision already works.
Both are consequences of the 6.5-instruction TB. **This is lead 2, not a flags
project.**

Total flag cost estimated **7-9% of vCPU** (4.3% measured in `cc_compute_all`,
~1.7% inferred in per-TB stores, rest in consume-side ops). Lower half is
inference. Note the cc helpers are `TCG_CALL_NO_RWG_SE` (helper.h:1-3), so they
do NOT force a global spill wave.

**Still worth doing, frontend-only, ~200 lines, nothing to do with ARM flags:**
extend `gen_prepare_cc` fast paths beyond `CC_OP_SUB*`/`LOGIC*` to
ADD/INC/DEC/SHL (today every `add; jbe` calls the helper), and stop
`helper_cc_compute_all` computing PF and AF when only four flags were wanted.
**Est. 1.5-3% of vCPU.** Full analysis and a 4-part sizing measurement (M0-M3)
in `02-lazy-flags.md`.

### 2. Superblocks / multiblock TBs — PROMOTED: also fixes the flag leaks
Boundary tax = env loads 5.8 + env stores 3.3 + branches/exits 3.1 = 12.2% of
JIT cycles, plus lookup_tb_ptr/qht ~3% of vCPU. **~13% ceiling, realistic
5-8%.** High cost: TB formation is load-bearing in QEMU, and bigger TBs worsen
SMC invalidation.
Now carries the two flag leaks above as well, so its pool is larger than the
12.2% boundary tax alone.
**Decisive cheap test: histogram TB exit reasons + guest-insns-per-TB-entry.**
If most TBs end on a direct branch within the same guest page, superblocks are
available; if on indirect branches or page boundaries, they buy nothing.
This one measurement decides leads 2 and 3 together.

### 3. Static register allocation across TBs — depends entirely on #2
TCG allocation is block-local; guest regs live in env and sync at every exit.
~25 host registers free; our guest is 32-bit x86 with 8 GPRs. FEX reports
"upwards of 20%" on 32-bit guests from SRA. Targets the 9.1% env traffic.
**Worthless without #2** (pointless to pin across boundaries you hit every 6.5
instructions). Cheap test: split env-load/store attribution into mid-TB vs
at-boundary.

### 1b. DONE AND DEAD: inlining the CC_OP_DYNAMIC flag path
**BUILT AND MEASURED 2026-09-12.  The prediction was confirmed exactly and it
bought nothing.**

| cc_inline | CC helper calls/frame | ms/frame |
|---|---|---|
| off | 621k, 676k, 612k, 674k | 33.04, 32.68, 33.00, 32.56 |
| **on** | **40k, 38k (-94%)** | 33.04, 32.64 |

The 94% prediction was exact, and the calls that remain are 96% LOGICB -- the
other op, untouched by design.  Frame time did not move: ~32.82 vs ~32.84,
inside the ~1.5% run-to-run spread.

Removing ~620k helper calls per frame is worth nothing on this core.  That is
the same lesson as the barriers and fastmem: the Cortex-X3's 320-entry window
absorbs work that is not on the critical path, and a predicted call whose
operands are already in registers costs far less than its instruction count
suggests.  **Five separate attempts to remove work have now measured zero.**

Kept, default ON, behind `debug.xemu.cc_inline` -- it is bit-identical and
harmless, and it is the record of what was tested.

**Correctness note:** validated with `debug.xemu.cc_validate=1`, which
recomputes every result with the reference helper and compares:
**52,216,487 checks, 0 mismatches.**  Getting there took two real fixes --
the first version had the helper's operands backwards (the second argument is
the SUBTRAHEND and the minuend is `dst + src2`), and the validator itself
compared against its own output, because `gen_compute_eflags` passes
`cpu_cc_src` as the destination so the computation overwrites its own input.
Without the validation harness the operand bug would have shipped.

Original entry: BEST REMAINING LEAD: specialise the CC_OP_DYNAMIC flag path
**Measured 2026-09-12.** `helper_cc_compute_all` is called **~678,000 times
per frame**, and there are ~740k TB executions per frame -- **0.92 calls per
TB, i.e. almost exactly one per block.** The op distribution is
**SUBL 94%, LOGICB 5%**, everything else ~0%.

That pins the mechanism exactly: `cc_op` resets to `CC_OP_DYNAMIC` at every TB
entry (target/i386/tcg/translate.c:4501), so the FIRST flag consumer in each
TB cannot use the existing `gen_prepare_cc` fast paths and calls the helper --
even though the runtime value is a plain 32-bit compare 94% of the time.
Inside a TB, dead-flag elision and the fast paths already work.

**This inverts the fix proposed in 02-lazy-flags.md.** Adding fast paths for
ADD/INC/DEC/SHL is pointless: those are ~0% of helper calls. The ops that
dominate (SUB, LOGIC) ALREADY have fast paths that simply cannot fire from
DYNAMIC.

**Proposed fix, self-contained, no cross-TB machinery:** at a DYNAMIC flag
consumer, emit an inline check -- load `cc_op`, and if it equals `CC_OP_SUBL`
compute the flags inline from cc_dst/cc_src, else fall back to the helper.
A 94% hit rate on a well-predicted branch.
**Estimated 3-7% of frame** (678k calls x ~10-15 cycles of call overhead,
less ~3-4 cycles for the check). Would be the largest win since the native
x87 path.
**Falsifiable first:** the 0.92-calls-per-TB ratio already predicts the
saving; if an implementation does not move the helper call count down by
~94%, the theory is wrong.

**NOTE: the cc_fastpath A/B below was INVALID** -- its property block was
anchored on text that exists only on another branch, so the replace silently
did nothing and both arms ran with the fast path on.  Re-wired since, with the
switch state now printed in the benchmark output so this cannot recur.

**PARTIAL RESULT 2026-09-12 — the dispatch is NOT the cost.**  Tested the
cheap increment first: `if (op == CC_OP_SUBL) return compute_all_subl(...)`
ahead of the switch in the helper (`debug.xemu.cc_fastpath`, default on).
Interleaved A/B, warmup discarded: **on 32.50/32.50, off 32.58/32.42 —
zero difference.**  The compiler's jump table was already cheap.

This *narrows* the remaining opportunity rather than killing it: whatever the
helper costs is the CALL plus the computation, not the dispatch.  Only
inlining into generated code removes the call.  Revised estimate for the full
inline version: **2-4%** (678k calls x ~5-8 cycles of net call overhead after
paying ~3 cycles for the runtime cc_op check), down from the 3-7% estimated
before this measurement, and with two real risks: hand-written eflags
computation in TCG is a correctness hazard on the path that decides every
branch, and the code growth at each DYNAMIC site works against an already
measured 112k iTLB misses/frame.

**Harness note — RETRACTED.**  Two benchmark sessions died at signal 9 on
both processes about 4 minutes in, and I attributed it to Android's idle
manager.  **Wrong: the user was killing the process manually**, having seen
the emulator apparently sitting idle.  `dumpsys deviceidle disable` did not
fix anything; the next run simply was not killed.  No Android power-management
change is needed, and `FLAG_KEEP_SCREEN_ON` (EmulationActivity.kt:292) was
doing its job all along.

**And the harness was the real culprit.**  A 200-frame benchmark takes about
**7 seconds**.  My scripts slept a fixed 55 s after each one, so the emulator
genuinely sat idle on a static scene for ~48 s between measurements -- which
is exactly what looked like a hang.  With the heartbeat in place the runs can
be polled instead:

```sh
adb shell am broadcast -a com.xemu.action.BENCHMARK --ei frames 200
for i in $(seq 1 40); do sleep 3
  adb logcat -d -s xemu-android:I | grep -q "bench: RESULT" && break
done
```

That makes an 8-run interleaved A/B take about 1.5 minutes instead of 8, and
cuts the thermal drift a session is exposed to by the same factor.  Use
polling, not fixed sleeps.

The secondary lesson is a coordination one: **an unattended benchmark looks exactly
like a hung emulator** -- a static scene, no input, no visible progress, for
tens of seconds at a time.  Before concluding an automated run has stalled,
check `adb logcat -s xemu-android:I | grep bench:` for RESULT lines, or
`adb shell pidof com.xemu:EmulationProcess`.  Signal 9 on *both* processes at
once is a manual stop or `am force-stop`; a real crash shows signal 6 or 11
on one.

### 4. DEAD: `rep movs`/`rep stos` -> host memcpy
**MEASURED 2026-09-12: 65,686-105,173 iterations/frame over ~3,000-4,600 rep
instructions — an UPPER BOUND (the counter adds ECX at entry, but repnz exits
early). At ~20-30 host instructions each that is <=0.8-1.9% of frame, below
the 200k threshold set in advance. Not worth a memcpy fast path.**
Original reasoning: QEMU emits x86 string ops as a per-element in-TB loop
(target/i386/tcg/translate.c:1564-1680), so a `rep movsd` of 4 KB is 1,024
iterations at ~20-30 host instructions each. Halo streams and decompresses
assets, so this may be a real slice of the ~4.4-6M guest instructions/frame.
Self-contained: no kernel-API hook, no per-title signature fragility.
**Falsify in ~15 lines:** one counting helper per `rep` execution, passing
ECX; if under ~200k iterations/frame, drop it. Currently the most promising
untested lead.

### 5. SETTLED: the memory hierarchy, measured properly
**2026-09-12.  Three counters that had never been read settle what "memory
bound" actually means here.**

| per frame | count |
|---|---|
| L1D read misses | 136k-202k |
| **last-level misses** | **148k-194k** |
| dTLB *walks* (raw ARM 0x34) | **~4,800** |
| iTLB walks (raw 0x35) | ~2,000 |
| dTLB refills (L1D_TLB_REFILL) | 276k-364k |

**CORRECTION 2026-09-12: point 1 below was measured data-side only and is
wrong as stated.**  With the instruction stream counted, the hierarchy does
filter properly: ~800k L1 misses/frame -> 240k L2 refills -> 105k last-level.
L2 catches about 70%, L3 over half of what is left.

**THE DOMINANT CACHE EVENT IS INSTRUCTION FETCH, and it had never been
measured.**

| per frame | count |
|---|---|
| **L1I misses** | **695k-757k** |
| L1D misses | 93k-104k |
| L2 refills (both sides) | 211k-240k |
| last-level misses | 96k-108k |

Instruction-side misses are ~7x the data side.  Two things follow.

*It is not code SIZE.*  `cc_inline` adds ~26 instructions at roughly one site
per TB -- about +20% on a 533-byte average block -- and L1I misses did not
move (717k/656k with it against 702k/672k without; frame time likewise).  So
marginal code growth is free, which also means the earlier "reduce emitted
code size" dead end stays dead for this mechanism too.

*It is TB ENTRY SCATTER.*  ~700k L1I misses against ~740k TB executions is
**0.95 misses per block entered** -- essentially one miss every time control
jumps to a new block, which is what a 533-byte average block scattered through
the code buffer predicts.

**Bound on the whole instruction-side opportunity:** eliminating every L1I
miss at ~12 cycles each would be ~8.4M cycles, **under 9% of frame**, and
realistically far less because frontend misses overlap with execution far
better than dependent data misses do.  A TB layout scheme that packed chained
blocks adjacently is the only idea that addresses it, for maybe 4% at high
effort against QEMU's allocation-order code cache.

**1. (superseded, data-side only) Nearly every L1 miss reaches DRAM.** Last-level misses are 96-98% of L1D
read misses, so L2 and L3 absorb almost nothing.  At ~110 cycles each that is
**17-23% of the frame** -- the original "23%" estimate was right, and the
worry that the generic counter was really measuring L1 refills was unfounded.

**2. Huge pages are NOT the missed opportunity.**  A dTLB *refill* is not a
page-table walk -- almost all refills are served by the 2048-entry L2 TLB.
Actual walks are ~4,800/frame, **3% of the last-level misses**.  2 MB pages
would remove a rounding error, so **rooting the device for THP is not worth
doing**.  That closes the largest item previously listed as untested upside.

**3. The L1D write-miss counter reads exactly 0** and is almost certainly
unsupported on this PMU.  Do not read anything into it.

**GPU L3 contention: TESTED 2026-09-12, not supported.**  Raising the internal
surface scale from 1x to 3x multiplies GPU-side memory traffic roughly ninefold
while leaving guest work unchanged (the game issues the same draws).  vCPU
last-level misses went from 157,848 to 148,444 per frame -- slightly DOWN, the
wrong direction for eviction.  Normalising by vCPU time, since at 3x the GPU
becomes the bottleneck and the vCPU idles (util 98% -> 86%), gives 4,890 vs
5,183 misses/ms: a 6% rise, inside the spread.  Combined with the earlier
flat-IPC result, there is no evidence the GPU thread is competing for cache in
a way that matters.  Switchable with `debug.xemu.surface_scale`.

**So the remaining answer is the dull one:** L2 and L3 absorb almost nothing
because the guest's working set genuinely dwarfs them.  Halo streams textures, geometry and game state through 64 MB of guest RAM
against an 8 MB shared L3; ~150-190k of those accesses per frame reach DRAM at
~110 cycles each, which is the 17-23% of frame time, and it is the game's own
access pattern rather than anything the emulator adds.

### 6. DIAGNOSTIC (no lever): host dTLB pressure
334k-364k host dTLB refills/frame, exceeding total cache misses. Guest RAM is
64 MB over 16,384 4 KB pages against an L2 TLB covering ~8 MB. Huge pages
would fix it; THP is `[never]` on this device and root-only. See
`04-host-memory.md`. Upper bound, not a walk count (ARM L1D_TLB_REFILL).

### 7. OPEN (reduced): tlb_reset_dirty's O(whole TLB) scan
From the heavy-frames agent, not yet measured:
- `tlb_reset_dirty` is O(whole TLB), not O(range): 22 mmu indexes x (256+8)
  entries = 5,808 entries / ~400 KB touched per call, larger than the X3's L1D.
  It is called per dirty page from the GPU thread, writing into the vCPU's
  hottest structure. Xbox is UMA so this is armed over all 64 MB.
  Measured at 2.8% of the vCPU's libxemu cycles (= 0.39% of vCPU) directly,
  but the cache-pollution cost is unmeasured.

### 8. ~~L3 contention from the GPU thread~~ — see section 5, NOT fully dead

---

## D. Dead ends (do not re-propose)

| idea | why it died |
|---|---|
| fastmem / removing softmmu TLB check | NEUTRAL, interleaved in-process 2026-09-11 |
| Suppressing per-access memory barriers | zero, 3.6M DMBs/frame eliminated |
| Enlarging/shrinking softmmu TLB | moot, 0.06% miss rate |
| vCPU core pinning | 0.4% |
| Enlarging the TB jump cache | made things worse |
| Reducing emitted code size as a goal | bytes are a poor proxy for cycles |
| Return-address-stack prediction | our mispredict rate is 0.10%; X3 already solves it |
| Hardware-assisted guest MMU (Captive) | requires EL2, unavailable to an Android app |
| Xbox HLE "constant offset" shortcut | reduces to fastmem, already neutral |
| Exempting stack loads from LDAPR (C3) | LDAPR costs the same as LDR on this core (section BI); only ~1 cycle of ADD left to win, not worth a correctness argument |
| APU `voice_lock` contention at game startup | **MEASURED 2026-09-26:** the vCPU spends 19% of Halo's startup blocked on `d->lock` in `voice_lock`, but removing *all* voice-lock locking (unsafe ceiling build) moved tap-to-logo only 7.4 -> 7.3 s (3 launches each, interleaved). Off the critical path. A lock-free-unlock variant also failed to reach the logo once in 3 launches. Reverted. |
| ADPF performance hint session | **MEASURED 2026-09-23, section AO:** wins 1 of 4 interleaved pairs; the vCPU core and NV2A cluster are already at max clock without it. Kept off, `debug.xemu.adpf`. |
| Out-of-lining memory access as built | -23% code, -19.5% L1I, but +15% instructions cancels it — section AC |
| Dead-flag elimination (hakuX port) | **UNSAFE, OFF (section AV):** neutral on speed (section J) and it caused ~1-in-7 boot bugchecks by leaving cc_op=EFLAGS over an operand in cc_src. |
| Eliminating CC helper calls | 97% removed, 1.1% fewer host insns, ZERO cycles saved — section W |
| Trusting absolute PMU figures from before section U | counters were multiplexed to 9-29%; understated 3-11x |
| Dynamic spin detection (auto) | detects correctly, but the benchmark has no spin worth eliding; 0.2-0.6 ms/frame worse — section R |
| Lower panel as a heat/perf lever (D3) | Off saves 0.13 W (~2%) and nothing else: fps, vCPU ms/frame and CPU temperature do not move — section AX |
| Slot 7 dip as a CPU/codegen problem | The guest never misses a frame there; the dip was display pacing (render-loop pickup + fixed 16 ms sleep) -- section AZ |
| 60 Hz instead of 120 Hz during play (A8) | Nothing to save on the Thor: the panels never leave 60 Hz -- section BA |
| Trace JIT / LLVM backend (HQEMU, Instrew) | user-mode results; system-mode ceiling 1.15x |
| GPU thread stealing vCPU time by blocking | vCPU shows no wait symbols, 1.26% kernel |
| Shrinking the JIT translation buffer (iTLB locality) | **MEASURED 2026-09-12: tb-size 256 vs 32 MB = 32.55 vs 32.55 ms/frame.** iTLB misses rose with the smaller buffer. Switchable via `debug.xemu.tb_size`, default 256. |
| Huge pages for guest RAM | **MEASURED 2026-09-12: actual page-table walks are ~4,800/frame against ~148k last-level misses, about 3%.** Nearly all dTLB refills are served by the L2 TLB and never become walks. 2 MB pages would remove a rounding error, so rooting the device for THP is not worth doing. (THP is also `[never]` here and root-only; QEMU already issues MADV_HUGEPAGE at physmem.c:2421.) |
| ~~Guest busy-wait / spin elimination~~ **THIS ROW WAS WRONG — see sections M and N.** The spin is real and large: ~23% of generated-code time in a 64-bit clock wait at guest 0x000bb0df. It reads ORDINARY MEMORY, so the MMIO-based test below could not have detected it, and the conclusion drawn from that test was unjustified. It remains *not worth doing for frame rate* — section N shows it is 14.2% of on-time frames against 1.8% of dropped ones — but that is a different and better-supported reason. Original (MMIO-only) reasoning, kept as the record of the mistake: **MEASURED 2026-09-11.** MMIO is only ~730 accesses/frame, and it is FLAT across frame classes (x1.06, x1.09) while host work rises x1.48. Cheap frames sit at 93 Mcyc against a 98 Mcyc budget. The 96-98% utilisation figure reflects real work, not polling. |
| MMIO lockless_io fast path (skipping BQL + device lock) | Same run: ~730 MMIO accesses/frame. Even at a generous ~1000 cycles each that is <1% of a 93 Mcyc frame. The ~10-line change is real and QEMU already ships the switch, but there is nothing to win. |
| L3 contention from the GPU thread evicting the vCPU's data | **MEASURED 2026-09-11: IPC is FLAT across frame weights** (cheap vs expensive: 1.69/1.67, 1.85/1.90, 1.86/1.90). If GPU memory traffic were evicting us, heavy frames — which have more GPU work — would show *lower* IPC. They do not. My hypothesis, killed by my own measurement. |
| Superblocks through jmp/call (lead C2) | **PRICED 2026-10-15 (BV):** only 15% of block ends are jmp/call; ~1.5-2.5% ceiling, fails the 3% gate. Through jcc: bounded by K's ~3% sync ceiling. |
| Return-address stack for TB lookup | **BUILT AND MEASURED 2026-10-15 (BW):** ret IC misses 30% -> 4%, lookup helper share halved, but vCPU -0.18 +- 0.18 ms (12 paired rounds) and slow frames unchanged. `debug.xemu.ras`, off. |
| ARM native-flag (NZCV/FEAT_FlagM2) mapping | AXFLAG needs 7 instructions where the current code needs 7 (PF = Z AND NOT C is not an AArch64 condition). NZCV-resident flags are impossible in system mode: the softmmu TLB check emits CMP+B.NE on every guest memory access and clobbers them. The hot `cmp; jcc` pattern already compiles to CMP+B.LT. |
| NV2A surface churn forcing TLB + jump-cache flushes | **MEASURED 2026-09-11: 0.00 full flushes/frame, and full=0 ABSOLUTE since boot** (part=337, elide=7615 prove the counter works). The code path is real (vk/surface.c:582-601 -> physmem.c:891 async_safe_run_on_cpu + tlb_flush_all_cpus_synced, and cputlb.c:392 wipes the jump cache too) but it never fires in steady-state gameplay: `expire_old_surfaces` only evicts surfaces unused for 5+ frames, and a combat scene reuses its surfaces every frame. |

---

## E. Agent queue

1. [x] heavy-frames — DONE. Killed the surface-churn lead; found the
       reentry_insns contamination (section F); left leads 4 open.
2. [x] lazy-flags — DONE. Corrected lead 1 (my AXFLAG claim was wrong);
       promoted lead 2; surfaced a ~200-line gen_prepare_cc win worth 1.5-3%.
3. [x] guest-work — DONE. Killed spin-elimination and the MMIO fast path by
       measurement; surfaced `rep movs` as the best remaining lead.
4. [x] host-memory — DONE directly (agent was killed by a session limit
       before writing anything). Found host dTLB pressure is real and large
       but has no lever without root; killed the JIT-buffer-size idea. — full cost of x86 flag emulation, NZCV mapping
5. [x] prior-art — DONE, folded into sections B/C/D above
6. [x] threads — DONE by direct measurement, folded into section A


---

## F. CORRECTION: the "heavy frames are just heavier game frames" conclusion is NOT established

Previously reported as settled: *"expensive frames run 1.6x more guest code
(ratios match cycles), so they are genuinely heavier game frames, not emulator
stalls."* That rests on `reentry_insns`, and **that metric cannot support it.**

`xemu_guest_insn_count += tb->icount` sits in the outer dispatch loop at
`accel/tcg/cpu-exec.c:1054`, **after** `tb_add_jump`. Chained TBs branch
directly into one another and never return there, so the counter sees only
chain breaks -- confirmed by the nochain experiment, where the same counter
went from ~1.5M to ~475M per 100 frames (a ~300x difference).

Worse, it is contaminated by the very thing being investigated:
`queue_work_on_cpu` calls `cpu_exit(cpu)` (`cpu-common.c:141`), so every
`async_safe_run_on_cpu` -- including the GPU thread's -- forces a chain break
and inflates the count independently of guest work. The metric used to
exonerate the emulator is incremented by emulator behaviour.

The code comment at `ui/xemu.c:1689-1692` already says the counter is "useless
as an absolute instruction rate"; the error was then using its *ratio* as a
work proxy anyway.

**RESOLVED 2026-09-11 — (a), the game, confirmed with an honest metric.**
Replaced the contaminated counter with **host instructions retired per frame**,
read from the hardware PMU on the vCPU thread: exact, sees chained code, and
cannot be inflated by `cpu_exit()`. Three runs:

| run | cycles ratio | host-work ratio | IPC cheap -> expensive |
|---|---|---|---|
| 1 | x1.62 | x1.60 | 1.69 -> 1.67 |
| 2 | x1.43 | x1.47 | 1.85 -> 1.90 |
| 3 | x1.47 | x1.51 | 1.86 -> 1.90 |

**Second challenge, also answered.** Host instructions retired cannot by
itself separate real game work from spin iterations (a spin iteration is real
retired work). So MMIO accesses per frame were added and split the same way:
x1.06 and x1.09 across frame classes against host work x1.48, on ~730
accesses/frame. Heavy frames are not polling more. Both objections are now
closed.

Host work tracks cycles almost exactly and IPC is flat (slightly higher on
expensive frames). Heavy frames genuinely run ~1.5x more code; the emulator
behaves identically on them. **There is no emulator-side heavy-frame effect to
fix.** The original conclusion was correct but had been resting on bad
evidence; it is now properly established, and the same run killed the
L3-contention hypothesis.

**The fix is cheap and available:** re-run the per-frame cheap/expensive
bucketing with `debug.xemu.nochain=1` (branch android-codegen-measure), where
every TB exit returns to the dispatcher and the counter becomes an EXACT guest
instruction count. Then the cheap-vs-expensive work ratio is real. Better
still, use NV2A draw-call counters (`hw/xbox/nv2a/debug.h:71-134`) as an
independent, uncontaminated denominator.

---

## G. THE REFRAME (2026-09-12): it is dependency chains, not instruction count

Measured `STALL_FRONTEND` (raw 0x23) and `STALL_BACKEND` (raw 0x24) on the
vCPU thread -- the diagnostic that explains why IPC sits at 1.85 on a 6-wide
core, and which had never been run.

| | Mcyc/frame | share |
|---|---|---|
| frontend stall (starved of instructions) | 3 | **4%** |
| backend stall (cannot issue) | 16 | **18%** |
| actually issuing | ~74 | 78% |

**Two conclusions, and the second one changes the strategy.**

**1. The frontend is fine.**  Despite ~700k L1I misses/frame, only 4% of
cycles are spent starved of instructions -- the fetch machinery hides them.
So TB layout/packing is worth at most ~4%, and that lead should be dropped.

**2. We are not stall-bound; we are ISSUE-bound at low ILP.**  78% of cycles
are issuing, but at ~2.2 instructions per cycle against a 6-wide core -- about
37% of the machine's width.  The generated code does not contain enough
independent work to fill it.

**This explains all six null results at once.**  Barriers, fastmem, the TLB
check, the flag helper, the dispatch switch -- every one removed *independent*
instructions, which were riding in spare issue slots and cost nothing.  The
binding constraint is the **length of dependency chains** in generated code,
not the number of instructions in it.

**What shortens dependency chains** (and is therefore worth trying, unlike
everything tried so far):
- **Static register allocation** -- guest registers pinned in host registers
  instead of round-tripping through `env`.  Every env store followed by an env
  load is a store-to-load forwarding dependency.  FEX reports ~20% on 32-bit
  guests, and our guest is 32-bit x86 with 8 GPRs against ~25 free host regs.
- **Dead flag elimination by dataflow** -- not the peephole we tried, but a
  real backward pass removing cc_* writes never read.  Each one is a chain.
- **Superblocks** -- previously rejected on trace-formation cost, but their
  value here is exposing ILP across block boundaries, not saving the exit.

**A sibling project has already built two of these.**  See section H.

---

## H. PRIOR ART ON DISK: hakuX and x1box (2026-09-12)

`/Users/lorengaravaglia/projects/hakuX` (github.com/rfandango/hakuX) and
`/Users/lorengaravaglia/projects/x1box/xemu` are **separate Xbox-on-Android
projects of the same lineage**, not forks of ours.  Both carry substantial TCG
work we do not have -- identical line counts, so they share a patched
ancestor:

| file | lines they have that we do not |
|---|---|
| `tcg/aarch64/tcg-target.c.inc` | **+734** |
| `accel/tcg/cpu-exec.c` | +368 / +374 |
| `target/i386/tcg/translate.c` | +203 / +232 |
| `accel/tcg/tb-maint.c` | +44 / +61 |

**Files that do not exist in our tree at all:**

- **`tcg/tier1-opt.c` (378 lines)** -- a two-tier JIT.  Blocks compiled with
  `CF_TIER1` get a tier-1 pass doing **dead flag elimination by backward
  dataflow** over `cc_op`/`cc_dst`/`cc_src`/`cc_src2`, removing writes whose
  results are overwritten before being read.  Runs after `tcg_optimize()` and
  before liveness.  **This is exactly the dependency-chain problem section G
  identifies, solved structurally.**
- **`accel/tcg/tb-cache-hints.c` (713 lines)** -- persistent TB cache hints.
  Records which blocks are generated during gameplay, saves them to disk, and
  pre-translates them on the next launch to eliminate JIT stutter.  Carries
  hotness metadata for tiered recompilation.
- **Xbox-specific reserved registers**: `TCG_REG_X27` pinned to
  `&xbox_ram_fp` and `TCG_REG_X26` as a host base, with a `CBZ` guard on the
  fast path -- a different and cheaper approach to guest memory than our
  4 GB-window fastmem (which measured neutral).
- Their own AArch64 scalar FP encodings, independent of our x87 work.

**hakuX is already installed on the test device as `com.rfandango.haku_x`**,
so a head-to-head on the same hardware and scene is possible and is the
cheapest way to find out whether any of this actually delivers.  Their
CHANGELOG makes no performance claims, so treat the techniques as unproven
until measured -- the same standard applied to everything else here.

**Recommended order:**
1. Head-to-head hakuX vs ours, same scene, same device.  Decides everything.
2. If they win: port the tier-1 dead-flag pass first (it targets dependency
   chains, which section G says is the binding constraint).
3. TB cache hints are worth taking regardless -- they address first-minutes
   JIT stutter, which is a real user-visible problem our benchmark (which
   runs after a warmup) cannot see.

---

## I. HEAD-TO-HEAD HARNESS vs hakuX (2026-09-12)

hakuX is installed (`com.rfandango.haku_x`, v0.3.1) and **configured with the
same game** -- Halo CE (USA) (Rev 2), 3.4 GB.  It is NOT debuggable, so
`run-as` and `simpleperf --app` are unavailable; measurement must come from
outside the app.

**`perf-investigation/neutral-bench.sh`** does that.  It reports process and
per-thread CPU from `/proc` and `top -H`, which work on any app.  Validated
against our own in-process benchmark: it reports vCPU thread 96-100% where the
benchmark reports 98% util and 32.56 ms/frame.

**SurfaceFlinger frame timing was tried and rejected.**  `dumpsys
SurfaceFlinger --latency <BLAST layer>` does yield real timestamps (filter the
INT64_MAX sentinel for un-presented frames), but it counts **panel
presentations** -- ~60/s, each 30 fps guest frame shown twice -- not emulated
frames.  It cannot discriminate between emulators and must not be used as an
fps source.  Emulated fps has to come from each app's own overlay.

**Metric:** `vcpu ms/frame = vCPU_thread_pct / 100 * 1000 / overlay_fps`.
Both emulators are guest-paced at 30 fps, so in a light scene the fps ties and
**CPU% is the efficiency signal**; in combat, where we run over budget, fps
itself discriminates.

**Blocked on:** hakuX needs to be driven to a combat scene comparable to our
save slot 5.  Its emulation activity is `exported="false"` so it cannot be
started from adb, and D-pad navigation of its library proved unreliable.  This
is a 30-second manual step for the user, after which the measurement is
automated.

**HEAD-TO-HEAD BLOCKED 2026-09-12: hakuX crashes loading Halo on this device.**
SIGSEGV in the block-I/O coroutine path --
`qcow2_co_decompress` -> `bdrv_co_preadv_part` -> `thread_pool_submit_co` ->
`qemu_coroutine_delete` -- with return addresses carrying non-canonical high
bits (`0x44a17a9f85021c`, `si_addr=0x8ba7a9f8500c4`) where valid text on this
device is `0x7a9f......`.  Those high bits are pointer-authentication
signatures left in place across a coroutine stack switch.

**This is the exact failure CLAUDE.md documents and we fixed.**  We build with
`-mbranch-protection=none -fno-sanitize=shadow-call-stack`
(android/app/src/main/cpp/CMakeLists.txt:121-122) and additionally replaced
the coroutine backend with hand-written AArch64 assembly
(`android/app/src/main/cpp/coroutine_android_asm.c`).  hakuX has neither: no
`mbranch-protection` anywhere in its tree, and only the stock QEMU
ucontext/sigaltstack backends.

**Consequence for the comparison:** their optimisations are **unproven on this
hardware** -- the emulator does not survive game load.  So the plan changes
from "measure them, then port what wins" to "port the technique into ours and
measure it with our own harness", which is stronger anyway: the harness has
caught eight wrong answers, and correctness can be validated the way the
inline flag path was (52M checks, 0 mismatches).

---

## J. PORTED: hakuX's dead-flag elimination (2026-09-12)

Ported `tier1_dead_flag_elimination` from hakuX into `tcg/tcg.c` (same
GPL-2.0-or-later licence), hooked after `tcg_optimize()`, gated on
`debug.xemu.dfe`.  Backward liveness over cc_op/cc_dst/cc_src/cc_src2;
conservative at labels, block ends, conditional branches and calls.

**It works, and QEMU's own liveness does NOT already do this:**
**1.68-1.71 ops removed per TB, 2.5% of all TCG ops seen.**  The counters
freeze when the switch is off and climb when on, so the effect is real.

**But frame time does not move:** dfe=1 gives 32.92/32.60, dfe=0 gives
33.16/32.68/32.92/32.64 -- 32.76 against 32.85, inside the spread.

**The arithmetic says that was predictable:** 1.68 ops/TB x ~740k TB
executions = ~1.2M ops per frame against 164M host instructions, so the
ceiling on this optimisation is **~0.8%** -- below our ~1% resolution.  Their
cross-TB variant (`tier1_compute_cc_defines_first`, which lets a successor's
defines-before-use kill a predecessor's trailing env stores, something QEMU
cannot do because globals are `TS_DEAD|TS_MEM` at TB end) would add perhaps
3 stores per TB, ~1.3% -- also below resolution.

**Kept, default on.**  It is correct, conservative, costs only translation
time, and removes real work.  But flag elimination as a direction caps out
around 1-2% and is not the lever.

**Why it does not help, in terms of section G:** these are *stores*.  Removing
a store does not shorten the dependency chain feeding later work.  The stall
data says we are issue-bound at ~2.2 IPC on a 6-wide core, and what would
change that is keeping guest registers in host registers so ALU work chains
register-to-register instead of through `env` -- static register allocation,
which FEX reports at ~20% for 32-bit guests.  **That is the one remaining
idea whose mechanism matches the measured constraint.**

---

## K. SRA PROTOTYPE: measured the ceiling instead of building it (2026-09-12)

Static register allocation would remove the global sync/reload at TB
boundaries.  Rather than implement it -- a deep change needing separate TB
entry points for chained versus dispatcher entry, plus a helper ABI -- the
cost of a boundary was measured directly.

`debug.xemu.one_insn_tb=2` puts one guest instruction in each TB **while
keeping chaining** (stock `one_insn_per_tb` also forces `CF_NO_GOTO_TB`, so it
multiplies dispatcher round-trips too and measures the wrong thing -- it came
out at ~160 cycles/boundary and 300 ms/frame, all dispatch).  With chaining
preserved, only the sync frequency changes.

| | ms/frame | TB boundaries/frame |
|---|---|---|
| baseline, 6.5 insn/TB | **31.80** | ~738k |
| 1 insn/TB, chained | **34.73** | 4.8M |

2.93 ms over 4.06M extra boundaries = **~2.1 cycles per TB boundary**, so at
baseline the whole sync cost is ~1.6M cycles/frame, **1.7% of frame**.

**Confound, stated honestly:** a 1-instruction TB has fewer dirty registers at
its boundary than a 6.5-instruction one, so this understates a real boundary.
Scaling 2-3x for that gives a realistic **SRA ceiling of 2-4%**.

**Either way it is far below the 7.6% the cycle attribution suggested**, which
counted all env traffic including mid-TB spills that SRA would not remove.
Against a ~1% measurement floor and deep TCG surgery, **SRA is not worth
building.**  This is the last idea whose mechanism matched the measured
constraint, and it is now bounded.

---

## L. WHY hakuX LOOKS SLIGHTLY FASTER (2026-09-12)

User ran hakuX (after our coroutine fix let it boot) and reports it may be
slightly faster **but with many graphical glitches**.  Their changelog says
what that is:

> `nv2a: lazy surface eviction downloads — skip when VRAM data is never read`
> `nv2a: texture zero-upload detection + disable texture replace sync`

They skip surface downloads and texture syncs.  That produces exactly those
glitches, and it is a correctness-for-speed trade rather than a better
emulator core.

**Could we take the same trade?  No -- there is nothing there for us.**
Skipping surface downloads also skips re-arming `TLB_NOTDIRTY` on VRAM pages,
which is the only way that GPU-side choice reaches our vCPU.  Measured:
**10-17 notdirty store-traps per frame** (8-9 of them SMC checks).  Even at a
generous 1000 cycles each that is **0.02% of a frame**.

So their visible speed difference is either GPU-thread work (which we measured
does not bound our vCPU -- IPC is flat across frame weights) or run-to-run
noise.  Copying it would cost image quality and gain nothing measurable.

This also closes the last open item in section 5/7: `tlb_reset_dirty` and the
NV2A dirty clients are not a meaningful vCPU cost.

---

## M. THE HOTSPOT: Halo spins on a 64-bit clock (2026-09-12)

**Guest-PC profiling** (`debug.xemu.guest_map` + `android/tools/guest-pc-profile.py`)
was the one profile never taken here -- everything before was host-side.  It
found a hotspot immediately:

| guest address | share of JIT cycles |
|---|---|
| `0x000bb0df` | **14.6%** |
| `0x000bb0ec` | 7.7% |
| **4 KB page `0x000bb000`** | **22.9%** |

That is ~19% of total vCPU time in one routine.  Dumped the guest bytes
(`debug.xemu.dump_pc`) and hand-decoded:

```
000bb0d4:  test bl, bl
000bb0d6:  jz   0xbb0df
000bb0d8:  push 1
000bb0da:  call <far>                 ; yield / pump?
000bb0df:  mov  eax, [0x1f8c80]       ; <-- 14.6%
000bb0e4:  cmp  [0x1f8c84], esi
000bb0ea:  jl   0xbb0d4               ; loop
000bb0ec:  jg   0xbb0f2               ; <-- 7.7%, exit
000bb0ee:  cmp  eax, edi
000bb0f0:  jb   0xbb0d4               ; loop
000bb0f2:  pop  ebx
```

**It is a 64-bit counter spin-wait**: loop while
`[0x1f8c84]:[0x1f8c80] < EDI:ESI`, high dword first, low dword on equality.
Halo is busy-waiting for a clock to reach a target.

**This is the busy-wait hypothesis, confirmed -- and it is why the earlier
test missed it.**  Section D records "guest busy-wait / spin elimination" as
dead because MMIO was flat at ~730 accesses/frame.  That test was sound for
MMIO but the spin reads *ordinary memory*, so it was invisible to it.  The
dead-ends table is wrong on that row.

**Before celebrating, the open question:** a clock spin means the guest
finished its frame early, so the spin should concentrate in CHEAP frames --
which are already under budget -- and be absent from the expensive frames that
actually miss 30fps.  If so, eliding it cuts average work by ~23% and buys
headroom, battery and thermals (and thermals feed back into sustained clock,
worth ~9%), but does **not** directly fix the dips.

**Next measurement, and it must come before any implementation:** split the
guest-PC profile by frame cost.  If the spin's share is roughly equal in cheap
and expensive frames, spin elision is a direct win on the dips.  If it is
concentrated in cheap frames, the win is headroom rather than frame rate, and
should be costed as such.

---

## N. THE SPLIT: the spin is in the frames that already fit (2026-09-12)

Section M found Halo burning ~23% of generated-code time in a 64-bit clock
spin-wait.  The open question was whether that spin also occurs in the frames
that miss 30 fps.  It does not.

Method: record a wall timestamp per frame (`framelog.bin`), assign each perf
sample to a frame by timestamp, classify the frame by whether it exceeded
33.3 ms, and split the guest-PC profile by class
(`android/tools/guest-pc-split.py`).

Frame distribution over 400 frames: 9% on time, 82% at 33-40 ms, 3% at
40-50 ms, 6% over 50 ms (worst 68.5 ms).

| guest page | on-time frames | dropped frames |
|---|---|---|
| **0x000bb000 (the spin)** | **14.2%** | **1.8%** |
| 0x0011b000 | 3.1% | 6.9% |
| 0x00052000 | 4.5% | 6.3% |
| 0x00184000 | 2.6% | 4.8% |
| 0x00088000 | 5.0% | 3.0% |

**The spin is ~8x more prevalent in frames that hit 30 fps than in frames that
miss.**  Exactly what a clock wait predicts: when the game has time to spare
it burns it waiting; when it is behind, it does not wait at all.

**So spin elision buys headroom, not frame rate.**  ~14% less work on the 91%
of frames that already fit -- worth real thermal and battery savings, and
indirectly some sustained clock, but it does not touch the dips.  It also
carries risk: the loop body calls a function each iteration when BL is set
(`push 1; call <far>`), so it is not a pure spin and cannot simply be skipped.

**And the dropped frames have no hotspot.**  Their profile is flat -- 6.9%,
6.3%, 4.8% spread across several pages of ordinary engine code.  That is the
game doing more work: more AI, more physics, more draw calls.  This confirms,
by a completely independent method, the conclusion reached in section F from
frame-cost analysis.

### Harness bug found here: PMU multiplexing

The per-frame cycle counts recorded during that run were **wrong by ~2.5x**
(median 0.38x budget against the same run's reported 0.96x).  simpleperf and
our in-process `perf_event_open` counter use the same hardware PMU, and the
kernel multiplexes them, scaling our reads down while simpleperf holds the
counters.

**Any cycle measurement taken while simpleperf is recording is suspect.**  The
split was redone using wall time between frames, which is immune to this and
is the more direct question anyway: a frame longer than 33.3 ms IS a dropped
frame.  Caught only because the framelog contradicted the benchmark's own
summary for the same run.

---

## O. SPIN ELISION: implemented and measured (2026-09-12)

The guest waits for wall-clock time to pass, and the counter it polls is
advanced by a timer interrupt.  Sleeping until that interrupt is therefore a
faithful emulation of the wait, not a shortcut -- the guest cannot observe the
difference.  That is what makes this safe where skipping work would not be.

Implementation (`accel/tcg/cpu-exec.c`): TBs whose guest PC falls in a
configured range are translated unchained, so every iteration reaches the
dispatcher; after N consecutive iterations with nothing else running, the
thread sleeps briefly.  Any block outside the range resets the count, so real
work is never delayed.

  debug.xemu.spin_lo / spin_hi   guest PC range (0 disables)
  debug.xemu.spin_us             sleep per burst, default 200
  debug.xemu.spin_thresh         consecutive iterations first, default 64

### Result, interleaved, 900-frame runs

| | spin OFF | spin ON |
|---|---|---|
| **vCPU ms/frame** | **32.81** | **19.90** |
| headroom vs 33.3ms | 2% | **40%** |
| vCPU utilisation | 98% | **59%** |
| fps (guest-paced) | 29.88 | 29.89 |
| wall for 900 frames | 30118 ms | 30115 ms |
| die temp after 2x250 frames | 85-88 C | **70-78 C** |

**~39% less vCPU work, identical frame rate and wall time, 10-15 C cooler.**
About 12 ms per frame of pure spinning is replaced by sleeping.

### What this does NOT show

The per-frame budget histogram is **unusable in this build** and must not be
cited: this session has accumulated ~14 open `perf_event_open` counters
against an ARM PMU with roughly 6, so the kernel multiplexes them and every
PMU-derived per-frame figure is scaled down.  Both configurations printed an
identical histogram, which cannot be true given a 39% work difference.

So **whether the dips improved is unmeasured here.**  Section N predicted they
would not, since the spin sits in frames that already fit.  The honest claim
is headroom and thermals, which are measured from /proc CPU time and wall
clock and are unaffected by the PMU problem.

Thermal headroom may feed back into sustained clock (section: we run at
2.86-2.94 GHz against a 3.19 GHz ceiling), but that is a hypothesis, not a
measurement.

### Caveats before this ships

- The PC range is **game-specific**.  Productionising needs dynamic spin
  detection rather than a hardcoded range.
- The loop is **not a pure spin**: `test bl,bl; push 1; call <far>` runs a
  function on some iterations.  Sleeping between iterations preserves it, but
  anything that skips iterations outright would not.
- Needs play-testing for input latency and audio, which a throughput benchmark
  cannot see.

---

## P. THE NOTDIRTY SPIKE: attributed to one page, but not fixable this way (2026-09-12)

Slow-frame logging (any guest frame >=60 ms reports what happened during it)
gave a clear attribution:

| | traps/frame |
|---|---|
| steady state | 9-13 |
| slow frames | 3,000-5,600 |
| **top page, every occurrence** | **0x059a9000** |
| share reaching tb_invalidate | 80-95% |

**Mechanism, confirmed:** `notdirty_write()` only clears `TLB_NOTDIRTY` once
the page is dirty for *every* client.  This page still holds translated code,
so it stays clean for `DIRTY_MEMORY_CODE`, the flag survives, and **every**
store re-traps rather than just the first.  Guest data sharing a 4 KB page
with guest code degrades from one trap per page to one trap per store, up to
2,475 in a single frame.

**Attempted fix, and it did not work.**  xemu drops upstream QEMU's per-TB
overlap test under `#ifdef XBOX` (tb-maint.c), invalidating every TB on the
page instead of only those containing the written bytes -- inherited from
upstream xemu, present in hakuX and x1box too, with no recorded rationale.
Restoring it (`debug.xemu.tb_overlap=1`, default off) was expected to make
each trap cheap.

Interleaved, 600 frames each: overlap OFF 0 and 2 slow frames at 32.57/32.85
ms per frame; overlap ON 2 and 1 at 32.73/32.65.  **No measurable difference.**
The trap fires either way, and the writes evidently do overlap the TBs on that
page, so the skipped-invalidation path saves nothing.

**What would actually fix it** is stopping that page being code and data at
once -- finer-grained dirty tracking, or not keeping translations on pages
that are written heavily.  Both are substantial, and the payoff is bounded by
how rare these frames are.

**Caution on measuring this at all:** slow frames are sporadic, 0-4 per 600.
An earlier cross-process comparison read 32 against 19 and looked alarming;
interleaved in one process the same comparison gives 1-3 either way.  At these
counts almost nothing is distinguishable from noise, and any future attempt
here needs far longer runs.

### Unrelated crash seen during this work

`gp_ep.c:60: scatter_gather_rw: assertion "page_entry <= max_sge" failed` --
SIGABRT in the APU DSP scatter-gather DMA during normal play, with spin
elision OFF, so unrelated to anything added here.  Upstream xemu code.  The
guest asked for a page beyond the scatter-gather table and the assert is
fatal.  Worth fixing separately; it is reachable in ordinary gameplay.

---

## Q. SOAK TEST: spin elision removes the dips after all (2026-09-12)

Section N concluded that eliding the spin buys "headroom, not frame rate",
because the spin sits in frames that already fit.  **That was right about the
mechanism and wrong about the outcome**, and the error was measuring over 30
seconds from a cool device.  The goal also changed -- not a consistent 30 fps
but a floor above 20 -- which makes worst-case frames the metric rather than
the average.

Method: from 51 C, eight consecutive 600-frame runs per arm, letting the
device reach its steady thermal state.  Runs 2-8 are the settled ones; run 1
is discarded as warmup.

| | spin OFF | spin ON |
|---|---|---|
| frames under 15 fps (>66 ms), per 600 | 1, 5, 3, 7, 6, 1, 7 (~4.3) | **0, 0, 0, 0, 0, 0, 0** |
| frames under 20 fps (>50 ms), per 600 | 12-18 (~15) | **1-3 (~1.4)** |
| worst frame | 68-93 ms (**10.8-14.7 fps**) | 50-63 ms (**15.9-20.0 fps**) |
| die temperature | 90-95 C | 77-89 C |
| vCPU ms/frame | 31.9 | 19.5 |

**Sub-15 fps frames are eliminated entirely.  Sub-20 fps frames fall about
90%.**

**Why it works, given the spin is not in the heavy frames.**  Thermal
headroom converts into sustained clock.  At 90-95 C the SoC throttles; the
project has separately measured 2.86-2.94 GHz sustained against a 3.19 GHz
ceiling.  Cutting 39% of average vCPU work keeps the die 10-15 C cooler, the
clock stays higher, and the heavy frames -- which contain no spin at all --
finish sooner because the core is faster when they arrive.

This is the first change in the whole investigation that moves the metric the
user actually cares about, and it was invisible to every short benchmark
because the effect only appears once the device is hot.

**Caveats.**  The two arms are separate launches, which this project usually
distrusts; the effect is accepted here because it is large (15 -> 1.4, 4.3 ->
0), consistent across seven runs per arm, and both arms started from 51 C.
One scene only.  And the spin PC range is still hardcoded to Halo -- dynamic
detection is required before this generalises.

## R. DYNAMIC SPIN DETECTION: works, but there is nothing for it to find (2026-09-13)

Goal: make spin elision work without the hardcoded per-game range from section O,
by detecting at runtime which blocks form a wait loop.

### What was built

`accel/tcg/cpu-exec.c`. Every few seconds a **probe window** opens: for 40 ms all
blocks get `CF_NO_GOTO_TB | CF_NO_GOTO_PTR`, so every iteration reaches the
dispatcher and becomes observable. Inside the window each block records the
guest register hash it was last entered with, and scores +1 when re-entered in
that same state, -1 when not. Real work cannot accumulate: a copy or transform
loop advances a pointer every iteration, so its state never repeats.

Four design corrections, each forced by a measurement:

1. **Commit to a block SET, not a PC window.** Halo's wait calls a helper, so it
   straddles two regions 0x11e000 apart (`0x1810f0`/`0x18111b` and
   `0x63482`/`0x63492`). Any `±0x80` window covers half of it, the
   consecutive-iteration counter reset on every call, and the elision never
   armed — the exact "0 sleeps/frame" failure seen for three iterations.
2. **Pick the best candidate at the end of a window, not the first to cross the
   threshold.** First-past-the-post latched onto `0x192cb3`, a minor repeater
   worth 200 iterations/frame, and stopped searching.
3. **Accumulate loops across probes.** The first loop found is the one the game
   idles in; replacing the set each probe just trades one loop for another.
4. **Coverage bar of 8%, not 25%.** Coverage is counted in dispatcher visits,
   but a wait loop costs *time* out of proportion to its block count.

Detection itself is reliable. Across ~20 probes it identified the same 4-block
loop at **93–99% of the probe window**, with `run ≈ visits` (essentially every
re-entry in identical register state), and **never once fired on real work**.

### Why it does not pay off

The control experiment is the important result. Setting the hand-found range
from section O on the slot-5 benchmark:

| arm | vcpu ms/frame | iterations/frame | sleeps/frame |
|---|---|---|---|
| MANUAL `0xbb0d4-0xbb0f2` | 36.17 / 36.33 | 51 / 13 | 0 / 0 |
| OFF | 35.45 / 35.35 | 0 | 0 |

**The section-O spin is absent from this workload** — 13–51 iterations/frame, no
sleeps, and slightly worse than off. The ≈3,800 iterations/frame and
≈19.5 ms/frame figure came from free-running gameplay, not this benchmark, and
must not be used as a target for it. The detector was not failing to find that
loop; it was not there.

What the detector does find is the idle loop, which runs 60–190 iterations/frame
during the benchmark. Committing it measured **worse**, consistently:

| detector state | pairs | AUTO-ON vs OFF |
|---|---|---|
| loop committed | 6/6 | **0.21–0.60 ms/frame worse** |
| nothing committed | 3/3 | 0.25–0.35 "better" (noise: identical code paths) |

Two costs: the loop's blocks stay unchained for the whole session, and the
200 µs `nanosleep` is charged to the frame. Note `vcpu ms/frame` is **wall
time**, so sleeping registers as a regression even where it saves real CPU and
power — which is why section Q's soak, measuring sustained clock over 600 frames
of real gameplay, reached the opposite conclusion about the manual range.

The third row is the calibration that matters for everything else here: with
both arms running identical code, interleaved A/B still showed ±0.3 ms/frame.
**That is the noise floor of this harness.** Several deltas recorded earlier in
this document sit at or below it.

### Disposition

Kept, **default off**, opt in with `debug.xemu.spin_auto=1`; probing
self-terminates after 15 consecutive fruitless windows so the cost is one-time
and bounded. The manual `spin_lo`/`spin_hi` override is unchanged.

**Dead end for the benchmark workload.** Worth re-testing only on a workload
where a spin is known to dominate — and any such test must use a CPU-cycle or
utilisation metric, not wall time per frame.

## S. THE DIPS ARE IN SLOT 5, AND THEY ARE WORK (2026-09-13)

Slot 2 was reported as the save state with the dips.  It is not — it is the
clean one.  Slot 5, the state every benchmark in this document already uses,
is where they are.  Interleaved in one process, 400 frames each:

| slot | vcpu ms/frame | fps | frames >50ms (<20fps) | >66ms (<15fps) |
|---|---|---|---|---|
| 2 | 32.6-32.7 | 30.0 | 0-2 | 0-1 |
| 5 | 35.7-37.1 | 25.1-26.5 | 91-111 | 17-24 |

So the benchmark contained the dips all along; what it lacked was the *spin*
(section R).  Those are separate facts and conflating them cost several runs.

### Shape: bimodal, not a shifted mean

New `bench: HIST` line.  Slot 5, typical:

```
<20:19  25-30:1  30-33:6  33-36:258  36-40:8  45-50:19  50-60:71  60-80:14  80+:3
```

~65% of frames sit in a tight 33-36 ms mode — the game's own 30 fps pacing,
i.e. perfectly healthy — and a separate population of ~105 frames costs
50-80 ms.  Slot 2 has the same 33-36 ms mode (311 frames) and nothing at all
past 50 ms.  There is no broad drift to shave; there is a distinct heavy
population.

Slot 2's `45-50:42` frames pair with its `<20:45` frames: a late frame followed
by a short one is pacing jitter, not slowdown.  Do not count those as dips.

### Not periodic

New `bench: SLOWGAPS` line reports frames between consecutive >45 ms frames.

- slot 2: `4 7 7 7 13 13 9 ... 10 11 12 10` — regular, ~every 10 frames.
- slot 5: `3 3 22 3 2 2 1 2 3 3 4 4 3 ... 1 1 1 2 2 2 3 2 3 1` — **bursts** of
  near-consecutive slow frames, with occasional quiet stretches.

Clustered rather than a fixed stride rules out periodic system work — autosave,
audio refill, a texture upload on a timer — and points at scene content.

### Work, not a stall

New `bench: WORKSPLIT` line compares recorded cycles between the populations.

| slot | fast | slow | ratio |
|---|---|---|---|
| 5 | 292 frames, 34.3 Mcyc | 107 frames, 59.2 Mcyc | **1.72x** |
| 2 | 356 frames, 34.9 Mcyc | 43 frames, 52.4 Mcyc | 1.50x |

Cycles scale with wall time, so the slow frames are **executing more**, not
waiting.  Nothing that unblocks the vCPU can help; only making the work cheaper
can.  This confirms the earlier dip analysis on the scene that actually dips.

**Fast frames cost the same in both slots** (34.3 vs 34.9 Mcyc).  Slot 5 is not
systemically more expensive — it has 2.5x as many heavy frames and they are
heavier.  Baseline per-frame cost is not the problem.

Caveat: absolute Mcyc is not trustworthy — 34.3 Mcyc across a 33 ms frame
implies ~1 GHz, consistent with the ~40% PMU-multiplexing scaling noted in
section N.  The ratio is sound because both populations share one counter.

### What this means for the 20 fps floor

Slow frames average ~55 ms and peak at ~80 ms.  Clearing 50 ms needs roughly
10% off them; clearing the worst needs ~38%.  Combined with section N's finding
that dropped-frame time is diffuse (top guest page 6.9%), there is no single
hot spot to remove — the win has to come from making generated code broadly
cheaper, which is the codegen-density target: 84% of cycles in generated code
at ~20 host instructions per guest instruction, issue-bound at IPC 1.85.

Also note section R's noise floor: interleaved A/B moves +-0.3 ms/frame with
both arms running identical code.  Measure dip work with the FLOOR and HIST
counters on slot 5, not with mean ms/frame, which cannot resolve it.

## T. LIVE COMBAT CAPTURE: the dips are input-driven, and the PMU is lying (2026-09-13)

Slot 2 played by hand for ~70 s through the combat section, recorded with the
BENCHMARK broadcast **without** a `slot` extra — that records in place, skips
the state reload, and does not suppress input, so no new code was needed.

### Playing produces the dips; idling does not

| slot 2 | fps | frames >50ms (<20fps) | >66ms (<15fps) | worst |
|---|---|---|---|---|
| no input, 400 frames | 30.0 | 0-2 | 0-1 | 65 ms |
| **played, 2000 frames** | 27.96 | **242** | **62** | **118 ms (8.5 fps)** |

The dips are driven by input-dependent work — combat AI, projectiles,
particles, audio voices — not by anything in the scene's baseline.  This is why
the no-input benchmark on slot 2 looked perfect and slot 5 did not: slot 5 is
simply a heavier static scene.

Shape (`HIST`): 1591 of 1999 frames still sit in the healthy 33-36 ms pacing
mode; the damage is 161 frames at 50-60 ms, 61 at 60-80 ms, 7 past 80 ms.
`WORKSPLIT` puts the slow population at **1.65x the cycles** of the fast one —
work, not a stall, same as section S.

**`SLOWGAPS` is the new information:** `1 1 1 1 1 1 1 1 1 1 1 2 1 1 ...` —
nearly every gap is 1, so slow frames run **consecutively**.  Under input the
game enters a sustained heavy regime for stretches at a time, rather than the
isolated 2-4 frame spikes the static slot-5 scene produces.  A dip is a
*period*, not a spike, which is why it is felt as the game becoming unplayable
rather than as a stutter.

### notdirty is NOT the cause (again)

The per-slow-frame diagnostic fired 20 times.  Nineteen of those show
`notdirty +36` to `+175`, which is negligible.  Exactly one shows `+6583
(smc +5762)`.  A single outlier frame does not explain 242 slow frames.
Section P's conclusion stands; do not re-open this.

### The PMU numbers in the bench output are scaled by ~0.4 — do not trust them

The run reports `effective 1.13 GHz`.  Measured from outside during a live
benchmark, the cores are pinned at **maximum** the entire time:

```
little 2016 MHz | mid 2803 MHz | prime 3187 MHz | maxtemp 83-87 C   (8 samples)
```

No DVFS throttling at all, at 87 C, with the vCPU thread (TID ...245) at a
saturated 100%.  A thread running flat out on a 3.19 GHz core cannot accumulate
1.13 GHz worth of cycles, so **the cycle counter is reading ~35-40% of actual.**

Cause: the bench opens 15+ PMU counters simultaneously (fds 137/141/142, 156,
168/169, 170/175/178 ...) on a PMU with ~6 programmable slots, so the kernel
multiplexes and scales every counter down.  Section N caught this when
simpleperf was running; it is happening **without** simpleperf too, all the
time, to our own counters.

**Consequences:**
- Every absolute per-frame PMU figure in the bench output is understated by
  ~2.9x: `L1I-miss 673611/frame`, `LL miss 93258/frame`, `STALLS backend 18.7%`,
  `effective GHz`, and the `per-frame vs budget` buckets (which classify almost
  everything as `<80%` and disagree with HIST's wall-time view for this reason).
- **Ratios between two populations measured by the same counter are still
  sound** — WORKSPLIT's 1.65x and the cheap/expensive splits survive.
- The codegen-density case (84% of cycles in generated code, IPC 1.85, L1I
  pressure) rests on these absolute numbers and should be re-measured with
  <= 6 counters open before more work is built on it.

Also: thermal throttling of the clock is **ruled out** as a dip mechanism.  The
cores hold maximum frequency at 87 C.

### Leads visible in the capture

- **CC helper 39132 calls/frame, 96% of them LOGICB.**  A `gen_prepare_cc` fast
  path covering one opcode would remove most of those calls.
- REP-STRING 67635 iterations/frame across only 3150 instructions (~21 per).
- MMIO 708 reads + 695 writes/frame, each leaving generated code and taking the
  BQL plus a device lock.

## U. THE PMU WAS UNDER-READING BY 3x, AND IT CHANGES THE ANSWER (2026-09-13)

### The fix

Every counter is opened with `PERF_FORMAT_TOTAL_TIME_ENABLED |
PERF_FORMAT_TOTAL_TIME_RUNNING` and read through `bench_scale()`, which scales
by the fraction of the window the kernel actually scheduled it for.  The
summary now prints that fraction.  `debug.xemu.pmu_few=1` opens only six
counters as a cross-check.

### Validation

`effective GHz` has a known right answer — the vCPU thread saturates a core
whose clock is readable from outside — which makes it a test rather than a
readout:

| | before | after | hardware |
|---|---|---|---|
| effective clock | 1.13 GHz | **2.93 GHz** | 2803 / 3187 MHz |

Three independent confirmations:
1. 95.6 Mcyc/frame at 32.65 ms/frame is exactly 2.93 GHz, and the 33.3 ms
   budget is 98 Mcyc — the frame now sits just under budget, as fps says.
2. The cycle-derived budget buckets (`80-100% = 502`) now agree with the
   wall-time `HIST` (`33-36 ms: 546`).  They contradicted each other before.
3. Reduced vs full counter set agree within 1-2% on every metric (see below),
   so the scaling is accurate and not merely unbiased.

The counters were being scheduled only **9-29% of the time**.  Correction
factors of 3-11x, applied to every absolute PMU figure this project has
recorded.

### The corrected picture

| metric | reduced set | full set (scaled) |
|---|---|---|
| effective clock | 2.93 GHz | 2.93 GHz |
| cycles/frame | 95.9 M | 95.5 M |
| **backend stall** | **59.6%** | **59.3%** |
| frontend stall | 12.6% | 12.7% |
| L1I miss/frame | 1,905,567 | 1,927,193 |
| last-level miss/frame | 286,167 | 280,909 |
| L1D read miss/frame | (not opened) | 216,153 |

### This refutes "issue-bound, not stall-bound"

Section C concluded the emulator was **issue-bound at low ILP** — "78% issuing
at 2.2 of 6-wide", "not stall-bound" — and that conclusion is why six
work-removal experiments were expected to measure zero.  It was computed from
multiplexed counters.

Corrected: **59.3% of cycles are backend stalls and 12.7% frontend stalls.
Only ~28% of cycles issue at all.**  The emulator is memory-stalled, not
issue-limited.

The stall budget reconciles with the miss counts:
- last-level: 281k/frame x ~110 cyc = 31 Mcyc = **32% of the frame**
- L1I served by L2: ~1.9M/frame, of which only 573k reach L2 refill, so
  ~1.35M x ~12 cyc = 16 Mcyc = **17% of the frame**

~49% against a measured 59.3% backend stall — the right order, and the
remainder is ordinary dependency stalling.

**Instruction-side misses outnumber data-side 8.9 to 1** (1.92M vs 216k).  The
dominant memory cost in this emulator is fetching its own generated code.

### What this means

Codegen density remains the target, but for a different reason than recorded:
not "fewer issue slots" but **fewer bytes of generated code, so it fits in
cache**.  That reframes what counts as a win — a change that removes host
instructions without shrinking the footprint may do nothing, and one that
shrinks the footprint without removing instructions may still pay.

Re-measure any earlier microarchitectural claim before building on it.  Every
absolute PMU number recorded before this section is understated by 3-11x.

## V. RE-MEASUREMENT OF SECTION A WITH TRUSTWORTHY COUNTERS (2026-09-13)

Every section-A fact that a hardware counter can move, re-measured on slot 2,
600 frames, after the section-U fix.  Sampling-derived facts (the 84% in
generated code, the within-JIT breakdown, the thread split) come from
simpleperf *proportions* and are immune to multiplexing — they are unaffected
and were not re-run.

| fact | recorded | re-measured | verdict |
|---|---|---|---|
| Host IPC | 1.80-1.85 | **1.67-1.70** | slightly lower |
| Branch mispredicts | 0.10% of insns | **0.098%** | **confirmed** |
| Guest instructions/frame | 4.4-6 M | **3.56 M** (exact) | lower |
| Executed host insns / guest insn | ~33 | **45** | **37% higher** |
| Cache misses | ~213k/frame, ~23% of frame | L1D 216k + **LL 281-286k**, LL alone 32% | understated |
| Stall structure | "not stall-bound", 78% issuing | **56-59% backend, 13-14% frontend** | **refuted** |
| Cycles/frame | (implied ~37 M) | **95.7 M** | 2.6x |

### Getting an exact guest-instruction count

`xemu_guest_insn_count` sits after `tb_add_jump`, so chained blocks never reach
it and it reports only chain breaks — 17k/frame against a true 3.56M/frame, a
210x undercount.  New `debug.xemu.nochain=1` translates everything unchained so
the counter is exact.  It costs ~10% (36.00 vs 32.67 ms/frame) and is a
measurement mode only.

Host-per-guest is then the chained host count over the nochain guest count,
which is legitimate because the benchmark advances a fixed 600 guest frames
from a fixed state: 161.6M / 3.56M = **45 host instructions per guest
instruction**, against ~33 recorded.

Incidentally, **nochain IPC is 2.26 against chained 1.69.**  Unchained code
runs *better* per cycle despite doing more work, which is another sign that the
chained generated code's own footprint is what hurts.

### The fastmem model was circular

Section A argued: *"164M instructions / IPC 1.85 = 88.6M cycles = ~31.6ms vs
~32.5ms measured: the model closes with no hidden stall term."*

Re-measured, it closes just as well — 160M / 1.67 = 95.7 Mcyc = 32.7 ms against
32.67 ms measured — but it now closes around a **56% backend stall**.  That
exposes the reasoning: IPC is *defined* as instructions/cycles, so
instructions/IPC = cycles is a tautology and closes for any workload whatever
its stall content.  It never had the power to rule out a stall term.

The empirical results it was used to explain — barriers and fastmem both
measuring zero — were wall-clock A/Bs and stand on their own.  The
**explanation** was wrong: it is not that "translation is already free and the
residue is the guest's own cache behaviour", it is that we are stalled on
fetching generated code, and neither change reduced the footprint.

### Standing conclusions after this pass

- **Memory-stalled, instruction side dominant.**  56-59% backend stall, L1I
  misses outnumbering L1D 8.9:1.
- **Branch prediction is genuinely a non-issue** (0.098%), so the
  return-address-stack dead end stays dead.  Frontend stalls are 13-14% and are
  I-cache, not misprediction.
- **45 host instructions per guest instruction** is the headline number for
  codegen density, worse than the ~33 previously believed.
- Guest workload is **3.56M guest instructions/frame** at 30 fps.

## W. LOGICB INLINE: correct, removes 97% of the calls, buys nothing (2026-09-13)

The capture in section T showed 39.1k CC helper calls/frame with LOGICB 96% of
them.  Implemented and measured.

### What was built

`gen_inline_eflags_logic()` in `target/i386/tcg/translate.c`, covering
CC_OP_LOGICB/W/L.  Logic ops define CF, OF and AF as zero, so only PF, ZF and
SF are live — far cheaper than the existing SUBL inline.  Two width identities
help: parity always reads the low byte whatever the operand size, and at byte
width SF is bit 7, already CC_S's position, so it needs a mask and no shift.
The helper's `parity_table` lookup folds into shift/xor pairs, keeping the
sequence register-only.

**The important implementation finding:** inlining only the `CC_OP_DYNAMIC`
case moved calls 39.1k -> 26.8k, with the remainder *still* 96% LOGICB.  Most
LOGICB consumers are sites where the translator **statically knows** cc_op, so
they take the `tcg_constant_i32` path and call the helper anyway, bypassing the
runtime check entirely.  Catching those needs no compare and no branch and is
strictly less code than the dispatched version.  With both paths: **39.1k ->
1.1k calls/frame (97% removed).**

Correctness: `debug.xemu.cc_validate=1`, **392,914,398 checks, 0 mismatches.**

### It is a wash

Interleaved A/B on slot 5 (slot 2 is useless for this — it sits at the 30 fps
pacing ceiling at 33.02 ms/frame, so removed work becomes idle and cannot be
seen).  Four pairs each direction:

| order | ON vs OFF, wall time |
|---|---|
| ON first | ON better 4/4 |
| **OFF first** | **ON worse 3/4** |

The sign flips with ordering: the second arm of every pair is slower because
the device is still heating.  **No wall-time effect.**  Order-independent
counters:

| metric | ON | OFF |
|---|---|---|
| CC helper calls/frame | **2,438** | 70,991 |
| host insns/frame | **175.7 M** (lower in 8/8 pairs) | 177.6 M |
| **cycles/frame** | **108.4 M** | **108.4 M** |
| emitted code | 20.2 MB | 20.1 MB |

1.1% of host instructions removed, **zero cycles saved.**

### This is the corrected model's first successful prediction

Section U established 56-59% backend stalls with instruction-side misses
outnumbering data-side 8.9:1, and section V concluded the lever is *bytes of
generated code*, not instruction count.  This result is exactly what that
predicts: the removed instructions were in the shadow of stalls, the footprint
did not shrink (20.2 vs 20.1 MB), and so nothing was gained.

It also re-derives section A's operational rule from a sounder basis.  The rule
said removing instructions from the high-IPC bulk pays ~1:1 — that is now
wrong as stated.  Removing instructions pays only when it removes *stall*
cycles, which for this emulator means shrinking the code footprint.

### Disposition

**Kept, default on**, `debug.xemu.cc_logic=0` disables.  It is correct, removes
real work, and does not grow the code buffer, so there is no reason to carry
the helper calls — but it does **not** advance the 20 fps floor and must not be
counted as progress toward it.

Do not chase the remaining 1.1k calls/frame (LOGICL 37%, SUBW 35% of a tiny
remainder).  On these numbers helper-call elimination is finished as a lever.

## X. WHERE THE EMITTED BYTES GO (2026-09-13)

Sections U-W established that this emulator is backend-stalled with
instruction-side misses outnumbering data-side 8.9:1, and that removing host
instructions without shrinking the code footprint buys exactly nothing.  So the
question became: what owns the footprint?

New instrumentation measures `tcg_current_code_size()` around every op in
`tcg_gen_code()` and accumulates bytes per TCG opcode (`bench: CODE BYTES`).
Bytes, not counts: a rare opcode emitting a long sequence matters more here
than a common one emitting four bytes.

Slot 5, 400 frames, 24.2 MB emitted:

| opcode | % of bytes | ops | bytes/op |
|---|---|---|---|
| **qemu_ld** | **18.86%** | 115,297 | **41.6** |
| **qemu_st** | **16.38%** | 99,490 | **41.8** |
| add | 11.42% | 439,170 | 6.6 |
| ld | 8.25% | 324,014 | 6.5 |
| mov | 6.74% | 389,588 | 4.4 |
| brcond | 4.52% | 164,555 | 7.0 |
| st | 4.35% | 179,685 | 6.2 |
| exit_tb | 3.97% | 130,474 | 7.7 |
| st8 | 3.48% | 163,240 | 5.4 |
| mb | 3.38% | 215,016 | 4.0 |

### The finding

**Guest memory access is 35.2% of the entire code footprint**, at ~41.7 bytes
— about ten AArch64 instructions — per access.  Those 215k ops are under 10% of
all ops emitted but own a third of the bytes.  Nothing else comes close; the
next eight opcodes together are 46% and are all already near-minimal at 4-7
bytes.

This is the same softmmu sequence that section A found to be 71% of *executed*
JIT instructions, and which fastmem tried and failed to make cheaper.  The
difference is the reason to care: not the instructions it executes, which are
absorbed free by a 6-wide core, but the **bytes it occupies**, which is what
drives 3.3M L1I misses per frame against a 64 KB L1I.

### The implication is a trade the old model forbade

Out-of-lining the access sequence into shared per-size stubs would replace ~10
inline instructions with a call, cutting perhaps 25% of total footprint while
*adding* executed instructions.  Under the old "issue-bound" model that was
strictly bad.  Under the measured one it is the right direction, because
instructions in the stall shadow are free and bytes are not.

Risks to size up before building it: a stub needs the address and value in
fixed registers, which constrains the register allocator and may add moves that
eat the saving; and the call/return pair must stay predicted.

Note also **`mb` is 3.38% of the footprint** (215k barriers, 860 KB).
Suppressing barriers was measured at zero for *time* (section D) and is still a
dead end for time — but it is not free in bytes, so it is worth re-testing if
and only if a footprint campaign is underway.

## Y. STALL ATTRIBUTION BY SIDE: footprint is the SECOND lever, not the first (2026-09-13)

Section X concluded that code footprint is the lever and pointed at
out-of-lining guest memory access.  Before building that, the stall budget was
decomposed by side, because the two stall counters had not been reconciled
against the miss counts.  **The check changed the plan.**

Slot 5, 400 frames, 108.8 Mcyc/frame:

| | measured | predicted from misses |
|---|---|---|
| **frontend stall** | 19.3 Mcyc (**20.8%**) | 3,323,094 L1I refills/frame, ~5.8 cyc effective each |
| **backend stall** | 50.6 Mcyc (**54.4%**) | 416,127 LL read misses x ~110 cyc = **45.8 Mcyc** |

75% of all cycles are stalled.  The backend figure is explained almost exactly
by last-level misses, and the frontend figure by L1I refills (naive costing at
12 cyc/refill gives 39.9 Mcyc against 19.3 measured, so they overlap and
prefetch down to ~5.8 cyc effective).

### Why the 54% is data, not code

An instruction fetch that misses stalls the **frontend**, whatever cache level
finally serves it.  If a large share of the 416k DRAM trips per frame were code,
frontend stall would have to be far larger than the 19.3 Mcyc measured — 300k
code fetches to DRAM would alone be 33 Mcyc.  It is not.  So the last-level
misses are overwhelmingly **guest data**, and they own the 54%.

### What this means for the footprint campaign

Code footprint attacks the **frontend stall only: a 20.8% ceiling.**  The
out-of-lining idea from section X would cut ~25% of footprint, which if L1I
refills fall proportionally is ~25% of 20.8% = **~5% of frame time**, roughly
1.7 ms/frame.  That is well clear of the +-0.3 ms noise floor and is worth
building — but it is not the 35%-of-footprint headline from section X, and it
must not be sold as one.

The larger prize is the **54% backend stall: 416k last-level misses/frame,
26 MB/frame of DRAM traffic on the vCPU thread alone** at 64 bytes a line.  That
is the thing to understand next.  Whether it is reducible is open: if it is
genuinely Halo's own working set then it is not ours to fix (which is what
section A claimed), but 26 MB/frame is large enough to ask whether emulator
structures — the 22-way softmmu TLB array, qht/TB lookup, the env block — are
contributing, and that has never been measured.

### Instrumentation note

`bench: SIDES` reports the split.  **ARM event 0x28 (L2I_CACHE_REFILL) reads 0
on this PMU** — it is unavailable, not genuinely zero, so the instruction-side
L2 refill share could not be measured directly; the argument above is made from
the frontend-stall ceiling instead.  Do not read that 0 as data.

## Z. THE 54% IS THE GUEST'S OWN DATA, AND IT IS CLOSED (2026-09-13)

Section Y left the backend stall — 54% of all cycles, 416k last-level misses
and ~20 MB/frame of DRAM traffic on the vCPU thread — unattributed, with two
candidates: the guest's own working set, or the NV2A thread evicting the vCPU
from shared L3.

### Emulator structures are ruled out by size

The softmmu TLB is the only emulator structure on the hot access path: 22 mmu
indexes x 256 entries x 32 bytes is ~180 KB, which is L2-resident on a core
with 1 MB of L2.  A 180 KB array cannot generate 318k DRAM misses per frame no
matter how often it is read.  Same argument disposes of the env block (a few
KB) and the TB jump cache.  qht and TB lookup are touched only on chain breaks,
~10k/frame, three orders of magnitude too few.

### L3 contention from the GPU: directly tested, and it is not there

`debug.xemu.surface_scale` changes NV2A pixel work without touching guest
behaviour — the benchmark still advances a fixed 400 guest frames from slot 5,
confirmed by `reentry_insns` holding at 7.40-7.46M across arms.  Bracketed
1x -> 2x -> 1x so drift is visible:

| scale | LL-miss/frame | L1D-miss | L1I-miss | backend | vcpu ms |
|---|---|---|---|---|---|
| 1x | 399,362 | 308,513 | 3,327,807 | 55.1% | 36.77 |
| **2x** (4x pixels) | **418,982** | 316,438 | 3,388,131 | 54.8% | 37.25 |
| 1x | 409,041 | 307,494 | 3,338,948 | 54.8% | 36.75 |

The 1x repeats bracket the 2x run and differ from each other by 2.4%, so
**quadrupling GPU pixel work moves vCPU last-level misses by at most a few
percent, inside the run-to-run spread**, and backend stall % is flat.
Extrapolating generously, removing *all* GPU traffic would reclaim ~2-5% of
416k misses = 1-2 Mcyc = 1-2% of the frame.

This supersedes the section D entry that killed the same hypothesis from IPC
flatness.  That argument depended on "heavy frames have more GPU work", which
was never verified; this is a direct causal test with the guest workload pinned.

### Conclusion: the data side is not ours

The 54% backend stall is **Halo's own memory access pattern** — ~20 MB/frame
streamed from a 64 MB guest, which for a game pushing geometry, textures and
audio through unified RAM at 30 fps is ordinary.  Section A's assumption was
right, now for a measured reason rather than a circular model.

Nothing we control reduces it.  Huge pages would cut the 8.8k page walks/frame,
which is not where the time is, and THP is "never" on this device and needs
root anyway.

### So the remaining budget is

| | share of cycles | addressable |
|---|---|---|
| backend stall (guest data DRAM) | 54% | **no** |
| frontend stall (code fetch) | 21% | **yes — this is the target** |
| issuing | ~25% | partly, but instructions are free in the stall shadow (section W) |

**The footprint campaign is the best remaining lever, and the ~5% of frame time
estimate from section Y stands as the realistic prize.**  It is also now the
*only* open lever of any size, which is worth knowing before committing to a
TCG backend change.

## AA. PRICING THE OUT-OF-LINING BEFORE BUILDING IT (2026-09-13)

Out-of-lining guest memory access is days of backend work resting on a claim
about bytes.  Rather than build it and find out, the slope was measured by
going the other way: make the sequence longer and read what happens.

### First attempt aimed at the wrong quantity

`debug.xemu.tb_pad=N` appends N never-executed NOPs to every block.  At N=64
the emitted code grew 7.6% (22.99 -> 24.73 MB) and **nothing moved** — L1I
misses 3.27M/3.36M against bracketing 0-runs of 3.27M and 3.37M, frontend stall
flat at ~20%.

Because those bytes are never fetched.  **L1I misses track the code that runs,
not the code that exists**, and the 35.2% from section X is a share of
*emitted* bytes, which includes cold code.  Two different quantities.

### The right knob gives a clean slope

`debug.xemu.ldst_pad=N` puts N bytes of NOPs inside the executed access
sequence.  Bracketed 0/16/0/16, averaged:

| | pad=0 | pad=16 | delta |
|---|---|---|---|
| host insns/frame | 171.83 M | 193.93 M | **+22.10 M** |
| L1I miss/frame | 3,305,475 | 3,771,033 | **+465,558 (+14.1%)** |
| cycles/frame | 105.72 M | 108.67 M | **+2.95 M (+2.8%)** |
| frontend stall | 20.3% | 21.3% | +1.0 pp |

The 0-runs agree to 0.4% on L1I misses, so the +14.1% is far outside noise.

**It is fetch cost, not issue cost.**  Cycles per added instruction is
2.95/22.10 = **0.133**, about 1/7.5 — the NOPs are nearly free to issue.  And
+465,558 misses x ~5.8 cyc effective (section Y) = **2.70 Mcyc against 2.95 M
measured**.  The cycle increase is the extra instruction fetch, essentially
exactly.

### It also answers the emitted-vs-executed objection

+22.10M instructions / 4 NOPs per access = **5.53M executed guest memory
accesses per frame**.  At ~10 host instructions each that is ~55M of 172M host
instructions, **32% of executed instructions** — against 35.2% of emitted
bytes.  The two shares agree, so section X's static measurement was a fair
proxy after all.

### The prediction for out-of-lining

Slope: **~29,100 L1I misses per byte per access** (465,558 / 16).

The softmmu fast path is 8-9 instructions (32-36 bytes) of code unique to each
access site.  A shared stub replaces that with a call plus register setup —
about 8 bytes at the site, with the stub itself a single hot line that stays
resident.  Net removal of unique fetch is therefore ~24-32 bytes per access:

| removed | L1I misses | cycles | frame time |
|---|---|---|---|
| 24 B/access | -700k (-21%) | -4.1 Mcyc | **-3.8%** |
| 32 B/access | -930k (-28%) | -5.4 Mcyc | **-5.1%** |

So **~4-5% of frame time, 1.5-1.9 ms on slot 5's 36.5 ms** — the section Y
estimate, now resting on a measured slope instead of a guess, and comfortably
clear of the +-0.3 ms noise floor.

**Falsifiable:** if the stub version does not cut L1I misses by ~20%+, the
mechanism is wrong and it should be abandoned rather than tuned.  Watch that
counter first, before wall time.

Risks unchanged: fixed input/output registers may force moves that eat the 8
bytes of saving, and the call/return pair must stay predicted.

## AB. BACKEND CHANGE, GROUNDWORK (2026-09-13)

Starting the out-of-lining priced in section AA.  Two prerequisites settled
before writing the stub.

### X30 is already a TCG temp

`TCG_REG_TMP2` is **X30, the link register**, and it is in `reserved_regs`.  A
`BL` into a shared stub therefore clobbers a register TCG already treats as
scratch, so **no spills are needed to preserve the return address** — the
largest risk flagged in section X is not there.

### Reserving a scratch register is free

The stub's fast path needs about four live values (address, TLB entry pointer,
comparator, masked address) and only X16/X17 are free once X30 holds the return
address.  So it needs one more, which means taking a register out of the
allocatable set — and that costs register pressure everywhere, which shows up
as spills: more code, the exact thing the change exists to remove.

`debug.xemu.reserve_x15=1` prices it.  Slot 5, 400 frames, first run discarded
as warm-up:

| X15 reserved | code | L1I miss/frame | Mcyc/frame | vcpu ms |
|---|---|---|---|---|
| 1 | 22.83 MB | 3,304,564 | 106.84 | 36.33 |
| 0 | 22.82 MB | 3,317,252 | 106.79 | 36.35 |
| 1 | 22.81 MB | 3,310,181 | 106.81 | 36.33 |

**Identical on every counter.**  25 allocatable GPRs is enough slack that giving
one up is invisible, so the stub may use a reserved scratch and does not need
the fallback design (saving the return address to an env slot to free X30).

### Design settled

- address in X16, result in X16, X17 + X15 as stub scratch, X30 holds the
  return address and is passed to the slow-path helper as `retaddr` — which
  lands inside the calling block, so `cpu_restore_state` still maps it to the
  right guest PC.
- one stub per (is_ld, size, mem_index) with `oi` baked in, so the call site is
  just `mov x16, addr` / `bl stub` / `mov data, x16` — about 12 bytes against
  the current 36-40.
- the stub is a single hot location, so its own instruction fetch is nearly
  free, and section AA measured issue cost at 0.133 cycles per instruction —
  extra instructions inside the stub are cheap, extra bytes at each call site
  are not.

### Correction

An earlier run in this session appeared to hang with X15 reserved and was
attributed first to the reservation and then to an ordering bug between
`tcg_out_tb_start` and `tcg_reg_alloc_start`.  Neither was true: the device was
in standby, and `tcg_reg_alloc_start` does not snapshot `reserved_regs` — the
allocator reads it live, after both hooks.  The toggle was moved earlier anyway
because it reads more clearly, but it corrected no defect.

## AC. OUT-OF-LINED MEMORY ACCESS: mechanism confirmed, net zero (2026-09-13)

Built.  Shared per-(is_ld, mmu_idx, size) stubs emitted after the prologue; the
call site becomes `mov x16, addr` / `movz x3, oi` / `bl stub` / `mov data, x16`.
`debug.xemu.ldst_stub=1`.  92.5% of accesses use it (the rest are sign-extended
loads or carry alignment bits and keep the inline path).

### Three things worth knowing for any future attempt

**`qemu_ld`/`qemu_st` already carry `TCG_OPF_CALL_CLOBBER`**, so TCG keeps
nothing live in a call-clobbered register across an access and the stub may use
X0-X17 freely.  No register needed reserving after all — the X15 experiment in
section AB was answering a question that did not arise.

**MemOp carries atomicity bits above bit 8.**  Baking `oi` into the stub and
requiring the site to match rejected **92% of sites**, and the diagnostic hid it
because the histogram masked with 0xff, printing `0x02` for what was really
`0x02 | MO_ATOM_*<<8`.  Passing `oi` in X3 (one MOVZ; `make_memop_idx` packs
into 16 bits) makes one stub correct for every atomicity at a given size.

**Restoring the saved link register must not pair with TMP0**, which is X16 and
holds the load result; that would corrupt every slow-path load.

### It does what it was designed to do

Eight runs, both orderings, slot 5:

| | stub off | stub on | delta |
|---|---|---|---|
| code size | 22.96 MB | 17.67 MB | **-23%** |
| **L1I miss/frame** | 3,280k | 2,639k | **-19.5%** |
| frontend stall | 20.15% | 19.45% | -0.7 pp |
| host insns/frame | 173.6 M | 200.0 M | **+15.2%** |
| cycles/frame | 106.70 M | 106.48 M | -0.2% |
| vcpu | 36.84 ms | 37.48 ms | +0.64 |

**The predicted mechanism is confirmed: -19.5% L1I misses against -21%
predicted.**  The net is nevertheless zero, and the two effects cancel almost
exactly:

- fetch saved: 641k fewer misses x ~5.8 cyc = **-3.7 Mcyc**
- execute added: +26.4M instructions x 0.133 cyc = **+3.5 Mcyc**

### Why the section AA prediction was wrong

It priced the bytes removed and never priced the instructions added.  The stub
path executes ~15 instructions per access against ~10 inline — the two
call-site moves, the MOVZ, the BL and the RET — which is +4.8 per access over
5.5M accesses a frame.  Section W had established that instructions are nearly
free, and that was read as *free*; at 0.133 cycles each they are cheap, but
26 million of them are not.

**The lesson generalises:** a footprint change must be priced on *both* sides,
bytes removed and instructions added, using the two coefficients measured in
section AA (~29,100 L1I misses per byte per access; 0.133 cycles per
instruction).  Either alone gives the wrong answer.

### Is it salvageable

The added instructions are three register moves that could be removed by
constraining the qemu_ld/st operands to fixed registers in the TCG constraint
set, so the allocator places the address in X16 and the result there directly,
and by keying stubs on the full MemOp so `oi` can be baked again:

| removed | instructions | cycles |
|---|---|---|
| `mov x16, addr` | -5.5M | -0.7 Mcyc |
| `mov data, x16` | -5.5M | -0.7 Mcyc |
| `movz x3, oi` | -5.5M | -0.7 Mcyc |

All three would turn the wash into about **-2.3 Mcyc, ~2% of frame time**.
Worth doing only if 2% is worth a constraint change that may itself cost moves
elsewhere — the allocator will insert its own when it cannot satisfy a fixed
register.  **Kept, default off**, so the measurement stands and the code is
there if that is attempted.

## AD. THE BARRIERS ARE THE BIGGEST THING LEFT (2026-09-13)

`debug.xemu.mb_mode`: 0 normal, 1 emit a NOP in place of the DMB, 2 emit
nothing.  Mode 1 holds instruction count and code size fixed and removes only
the ordering constraint, which isolates memory-level parallelism from
instruction cost — the separation the out-of-lining experiment failed to make.

### Result

Slot 5, 400 frames, alternating:

| mode | backend | frontend | Mcyc | LL-miss | vcpu | frames >50ms |
|---|---|---|---|---|---|---|
| 0 normal | 52.0% | 19.8% | 104.87 | 403,522 | 35.70 | 95 |
| **1 NOP** | **23.5%** | 15.5% | 95.08 | 363,381 | **32.45** | **22** |
| 2 omit | 23.6% | 15.9% | 94.68 | 364,139 | 32.52 | 28 |
| 0 normal | 52.5% | 19.9% | 106.51 | 409,559 | 36.25 | 101 |
| **1 NOP** | **22.9%** | 16.0% | 92.86 | 362,542 | **32.33** | **20** |
| 2 omit | 22.6% | 16.1% | 91.57 | 359,086 | 32.25 | 30 |

**Backend stall 52% -> 23%.  Frames below 20 fps 95-101 -> 20-30, a 78%
reduction.**  Mode 1 and mode 2 are indistinguishable, so this is the ordering
constraint, not the instruction.

### The guest is doing the same work

Chained runs showed host instructions apparently doubling, which would have
meant the guest had taken a different path and the comparison was void.  Run
again under `nochain=1`, where the guest instruction count is exact:

| mode | guest-insn/frame (EXACT) | host-insn/frame | vcpu |
|---|---|---|---|
| 0 | 10,607,467 | 711,281,141 | 102.05 |
| 1 | 10,670,446 | 710,991,136 | 77.95 |
| 0 | 10,694,094 | 717,299,979 | 103.80 |

Guest instructions agree within 0.8% and host instructions within 0.9%, with
the two baselines bracketing the NOP run: **identical work, 24% less time.**
The doubling seen in the chained runs is unexplained and should be treated as a
counter artifact until someone accounts for it — it does not appear here, where
the controlled quantities are measured directly.

### Why this is probably fixable rather than just measurable

`tcg_gen_req_mo()` (tcg/tcg-op-ldst.c:129) runs before every guest access and
emits a barrier when the guest's memory model is stronger than the host's —
x86 TSO on weakly-ordered AArch64.  `guest_mo` is taken unconditionally from
`guest_default_memory_order` in translate-all.c:345, **with no reference to how
many vCPUs exist**.

`hw/xbox/xbox.c:452` sets `m->max_cpus = 1`.  **The Xbox is uniprocessor.**
TSO describes what one guest CPU may observe of another's stores, and here
there is no other guest CPU — so these barriers are modelling an ordering that
has no observer.

That reframes the change: not "drop ordering the guest is entitled to", but
"stop emitting ordering for an observer that does not exist".  The remaining
correctness question is narrow and concrete: the NV2A reads guest RAM from
another host thread, so the question is whether device DMA can observe vCPU
stores reordered in a way that matters, given that the guest kicks the GPU
through MMIO and the pushbuffer, which are serialised by the device lock rather
than by these barriers.

### Status

**Measurement only for now** (`mb_mode` defaults to 0).  This is the largest
effect found in the entire investigation and the only one that moves the 54%
backend stall, which sections Y and Z had concluded was closed.  Those sections
were right that the *misses* are the guest's own — LL misses barely move
(403k -> 363k).  What changes is the cost of each one: with the barriers gone
the misses overlap, which is exactly the memory-level-parallelism mechanism the
test was built to isolate.

## AE. SHIPPED: store-side TSO barriers elided on a uniprocessor guest (2026-09-13)

Section AD measured the barriers as the largest remaining cost.  This is the
subset that has a correctness argument, implemented properly rather than as a
knob (`accel/tcg/translate-all.c`, `debug.xemu.tso_stores=1` restores them).

### The argument

`tcg_gen_req_mo()` emits a barrier before every guest access because x86 is TSO
and AArch64 is weakly ordered.  Two observers of guest memory exist, and they
are protected differently.

**Guest code.**  TSO describes what one guest CPU may observe of another's
stores.  `hw/xbox/xbox.c` sets `max_cpus = 1` — there is no other guest CPU, so
the store-side ordering has no observer.  The gate is written on
`smp.max_cpus == 1`, not on the machine type, so it stays correct if a
multiprocessor guest is ever added.

**Device DMA.**  Ordered by the device lock, not by these barriers.  The guest
kicks the NV2A by writing a PFIFO register; that MMIO write runs
`pfifo_write()` on the vCPU thread, which takes `d->pfifo.lock`, signals the
FIFO condvar and unlocks, while `pfifo_thread()` wakes holding the same lock.
The release/acquire pair already orders every pushbuffer store made beforehand.

**The load side is deliberately kept.**  A guest that polls an NV2A notifier in
RAM and then reads the data it announces depends on load-load ordering, which
x86 guarantees and AArch64 does not; dropping `DMB ISHLD` would let the data
load be hoisted above the poll and return stale memory.  That is the one
direction with no lock protecting it, so it keeps its barrier.

Measured, keeping it costs little — see the mode 3/4 split below.

### Which direction the cost is in

| mode | cycles/frame | vcpu | frames <20fps |
|---|---|---|---|
| normal | 105.6 M | 35.95 | 101.5 |
| **keep loads, drop stores** | **95.5 M** | **32.65** | **30.5** |
| keep stores, drop loads | 93.8 M | 32.55 | 22 |
| drop both | 89.9 M | 32.35 | 21 |

The safe direction carries most of the win, and the remainder is largely
invisible anyway: below ~33 ms the guest's own 30 fps pacing caps wall time, so
further cycle savings become idle rather than frames.

### Result as shipped

| | barriers kept | **elided (default)** |
|---|---|---|
| backend stall | 52.3 / 53.8% | **42.5 / 42.2%** |
| cycles/frame | 104.6 / 107.9 M | **93.8 / 93.9 M** |
| vcpu | 36.12 / 37.58 ms | **33.20 / 33.52 ms** |
| fps | 25.99 / 25.02 | **28.29 / 28.22** |
| **frames <20 fps** | 108 / 129 | **41 / 38** |
| frames <15 fps | 13 / 21 | 9 / 8 |

**Sub-20 fps frames fall 67%**, cycles 11.7%, and the histogram shows it
directly: the 50-60 ms bucket goes from 86/94 frames to 28/26.

This is the largest improvement in the investigation, and the only one that
moved the backend stall that sections Y and Z had concluded was closed.  They
were right that the *misses* are the guest's own — LL misses barely move.  What
changed is that they now overlap.

### Still to verify

**Play-testing.**  A benchmark cannot show a rare ordering bug.  The risk is
graphical corruption or a hang from device DMA observing stores out of order,
in some path that does not go through the PFIFO kick analysed above.  Audio
(APU/DSP reading guest RAM) has not been traced the way PFIFO was and is the
most likely place for a gap.

## AF. DEVICE AUDIT AND THE APU FENCE (2026-09-13)

Section AE elided the store-side TSO barriers on the argument that guest ->
device ordering comes from device locks.  That argument was only traced for the
NV2A's PFIFO.  This audits every device that can read guest RAM off the vCPU
thread and closes the one gap.

### The audit

Devices holding a direct guest-RAM pointer (`memory_region_get_ram_ptr`):
`apu.c` (`d->ram_ptr`), `nv2a.c` (`vram_ptr`, `ramin_ptr`), the VGA alias, and
`chihiro.c` — the last being ROM loading at init, on no thread.

Threads that could follow one: `nv2a.pfifo_thread`, `mcpx.apu_thread`,
`mcpx.voice_worker` (several), and `pgraph.shader_cache` /
`shader_write_to_disk`, which only touch host files.

| path | ordered by | verdict |
|---|---|---|
| guest -> PFIFO -> pgraph | `d->pfifo.lock` + condvar; MMIO handler runs on the vCPU thread | safe |
| apu_thread -> voice workers | `vwd->lock` + `work_pending`/`work_finished` | safe |
| **guest -> apu_thread** | **nothing** | **gap** |

### The gap

`mcpx_apu_write()` never takes `d->lock`.  It updates registers with
`qatomic_set()`, which is a **relaxed** store and orders nothing.  So the guest
fills a voice buffer in RAM, writes an APU register, and the frame thread reads
that register and follows `d->ram_ptr` into RAM — with no happens-before edge
between the data and the announcement.

This worked before only because x86 TSO made the guest's stores reach memory in
program order, and section AE removed exactly that.  The window is narrow and a
few minutes of play will not reliably hit it, which is precisely why it needed
finding by reading rather than by testing.

### The fix

`smp_wmb()` at the top of `mcpx_apu_write()`, paired with `smp_rmb()` in
`mcpx_apu_frame_thread()` after the control-register reads that authorise
processing.  That is the release/acquire pair the lock was providing for PFIFO.

**Two barriers per APU register write, against ~5.5M per frame in generated
code.**  Cost is unmeasurable:

| | cycles/frame | vcpu | fps | frames <20fps |
|---|---|---|---|---|
| barriers kept | 109.65 M | 37.66 ms | 25.01 | 124.5 |
| **elided + APU fences** | **95.2 M** | **33.10 ms** | **28.19** | **44.7** |

95.2 M against 93.85 M measured before the fences, with the <20 fps counts
overlapping (39-51 across runs).  **Sub-20 fps frames remain 64% down.**

### The general lesson

The barriers were paying for correctness that should be stated explicitly at
the handoff points.  Five and a half million barriers a frame were standing in
for a release/acquire pair per device kick — one of which existed (PFIFO's
mutex) and one of which did not (the APU).  Making the requirement explicit is
both faster and clearer about what is actually guaranteed.

### Remaining exposure

The load-side barriers are still emitted, so device -> guest message passing
keeps x86 ordering.  VGA reads the framebuffer while the guest writes it, but
that races on real hardware too and costs at most tearing.  What has *not* been
proven is that no other guest -> device handoff exists outside the PFIFO and
APU register paths; the audit covered every thread and every RAM pointer in
`hw/xbox`, which is the whole surface unless a device reaches guest memory
through `address_space_*` from its own thread.

### AF.1 The `address_space_*` paths (2026-09-13)

Section AF audited devices by their RAM pointers and threads.  The remaining
exposure was a device reaching guest memory through `address_space_*` /
`pci_dma_*` from its own thread, which that audit would not have caught.  Four
files in `hw/xbox` use those calls; here is every one.

**guest -> device reads** — these need the guest's stores to be visible:

| path | thread | ordered by | status |
|---|---|---|---|
| PFIFO -> pgraph | pfifo_thread | `d->pfifo.lock` | safe |
| APU DSP `ldl_le_phys` (gp_ep.c:87) | apu_thread | `smp_wmb`/`smp_rmb` added in AF | fixed |
| APU voice workers | workers | `vwd->lock` handoff from apu_thread | safe |
| **nvnet TX (nvnet.c:349,525)** | **vCPU thread** | same thread, program order | **safe** |

nvnet is the one worth spelling out: it creates no thread, and
`dma_packet_from_guest()` runs from the `NVNET_TX_RX_CONTROL` MMIO handler on
the KICK bit — so the guest's packet stores and the device's DMA read happen on
the same thread.  There is no cross-thread ordering to provide, with or without
barriers.

**device -> guest writes** — these depend on *device-side* store ordering:

| path | thread | note |
|---|---|---|
| vp.c notifiers (43, 47) | apu_thread | two `stb_phys` with nothing between them |
| vp.c scatter-gather (449, 485, 536) | apu_thread | same |
| nvnet RX (424, 371) | main loop, BQL | same |

**These are not affected by section AE.**  That change removed the *guest's*
store-side barriers; it did not touch the ordering of stores made by device
threads, which was never provided by guest barriers in the first place.

They are worth recording as a pre-existing latent issue on any weakly-ordered
host, independent of this work.  `vp.c:43-47` writes a status byte and then a
"ready" byte with no barrier between, which is device -> guest message passing
that a weakly-ordered host may reorder.  It is mostly masked because the frame
thread announces completion through `bql_lock(); update_irq(); bql_unlock()`,
and a guest waiting on the interrupt gets the ordering from that release
/acquire.  A guest that *polls* the notifier instead would be exposed.  Not
introduced here and not fixed here.

### Audit conclusion

**No guest -> device handoff is left unordered.**  Every one runs on the vCPU
thread, behind a mutex, or behind the fence added in AF.  The store-side
elision in AE is sound on the surface that exists today, and the gate on
`smp.max_cpus == 1` keeps it sound if a multiprocessor guest is ever added.

### AF.2 Settings toggle (2026-09-13)

"Accurate memory ordering" under Settings -> Advanced -> Compatibility,
**off by default**, restoring the TSO store barriers when enabled.

A safety valve, not a tuning option, and the description says so in the user's
terms rather than the implementation's: the Xbox has one CPU so the ordering
its games were written against has nothing to observe it, turn this on only if
you see flickering or corrupted graphics or hear crackling audio, expect a
brief pause while translated code reloads.

Path: `SettingsViewModel.setAccurateMemOrdering()` ->
`NativeInterface.setAccurateMemoryOrdering()` ->
`xemu_android_set_accurate_mem_ordering()`, which sets `g_keep_tso_stores` and
calls `queue_tb_flush()` because every translated block becomes stale.
`EmulationActivity` applies the saved preference at start.

**`debug.xemu.tso_stores` is now an override that only applies when the
property is actually set**, so it cannot stomp the UI value on the next
property refresh.  One consequence worth remembering: clearing it needs
`setprop debug.xemu.tso_stores 0`, not setting it to an empty string —
`__system_property_get` returns 0 for empty, the guard skips, and the previous
value stays live.  That looked like the elision had silently stopped working
until the three states were measured against each other.

Verified all three:

| state | vcpu | fps | frames <20 fps |
|---|---|---|---|
| default (UI off, no property) | 32.17 ms | 28.55 | 26 |
| property = 1 (barriers kept) | 36.25 ms | 26.08 | 92 |
| property = 0 (elided) | 32.25 ms | 28.59 / 29.11 | 27 / 25 |

## AG. LIVE COMBAT, AFTER (2026-09-13)

Slot 2 played by hand through the combat section, ~70 s, with the shipping
configuration: store-side TSO barriers elided, APU handoff fenced, notifier
release added.  Compared against the same section captured in section T before
any of this work.

| | before (section T) | after |
|---|---|---|
| fps | 27.96 | **28.77** |
| vcpu ms/frame | 34.22 | **32.94** (under the 33.33 budget) |
| **frames <20 fps** | 242 / 2000 | **161 (-33%)** |
| **frames <15 fps** | 62 | **28 (-55%)** |
| mean frame | 35.8 ms | 34.8 ms |
| slow/fast work ratio | 1.65x | 1.60x |
| worst frame | 118 ms | 149 ms |

### The shape changed, which is the part that matters

Section T's `SLOWGAPS`: `1 1 1 1 1 1 1 1 1 1 1 2 1 1 ...` — slow frames almost
entirely consecutive, a sustained heavy regime.

Now: `17 18 19 22 1 4 2 12 2 4 10 7 28 50 9 12 21 32` then a shorter run of 1s
and 2s, then `6 9 3 5 29 26 40 2 1 1`.  Mostly isolated slow frames with a few
clusters left.  A one-frame dip is a stutter; twenty consecutive is the game
becoming unplayable, and that is what has largely gone.

The histogram agrees: 50-60 ms falls 161 -> 121, 60-80 ms falls 61 -> 26, and
the 30-33 ms bucket goes 0 -> 43 as frames start finishing with headroom.

### Caveats

- **The absolute Mcyc figures are not comparable across these two captures.**
  Section T predates the PMU multiplexing fix, so its counters read ~0.4x.  Its
  fast-frame figure of 35.4 Mcyc against 91.4 now is instrument, not workload.
  The ratio within each run (1.65 vs 1.60) is comparable; the absolutes are not.
  Everything else here is wall-clock and unaffected.
- **Not a controlled comparison.**  A human plays the section differently each
  time, so this is two samples of a scene, not a paired measurement.  The
  direction and magnitude are clear but the precise percentages are not exact.
- **The worst frame got worse** (118 -> 149 ms).  With 2000 frames and live
  input the single worst frame is not a stable statistic, and the count of slow
  frames -- which is stable -- moved firmly the other way.

### Live gain is smaller than the benchmark's

The slot-5 benchmark showed sub-20 fps frames down 67%; live combat shows 33%.
The benchmark is a fixed scene with no input, so its cost is dominated by the
steady-state work the barriers were taxing.  Live combat adds input-driven
bursts -- AI, projectiles, particles, extra voices -- that the barriers were
never the limit on.  The benchmark number is the ceiling for this change; 33%
is what it is worth in the case that actually prompted the work.

---

## AH. WHAT HLE COULD ACTUALLY BUY (2026-09-18)

Question: could an Xbox emulator built on HLE (Cxbx-Reloaded's approach, or
the Winlator/Box64 stack) beat this LLE design on Android? Bounded by
measurement instead of argument.

**Method.** `debug.xemu.guest_map=1` records host-JIT-range -> guest-PC for
every TB; simpleperf samples cpu-cycles across the deterministic slot-5
benchmark (1200 frames, 32.35 ms/frame vCPU, reproduced to 0.04 ms of the
prior run); each sample is mapped back to a guest PC and bucketed by **XBE
section**, whose virtual address ranges come from the section table
(`XisoReader`'s parser, reused). `.text` is the game; `D3D`/`DSOUND`/`XPP` are
Xbox libraries statically linked into the XBE; guest PCs >= 0x80000000 are the
Xbox kernel.

**Two traps, both hit.** The vCPU is *not* the busiest thread on this scene --
picking by sample volume attributed the entire profile to the NV2A thread and
produced zero JIT hits. Select the thread with generated-code samples instead.
And the top `.text` PC is `0x000bb0df`, the section-M clock spin at 22.6% of
vCPU samples (section M said ~23%) -- that is the game *idling*, so it is
reported separately rather than counted as game work.

### vCPU thread, 12242 samples, 85.8% inside generated code

| bucket | incl. spin | excl. spin |
|---|---|---|
| game `.text` (real work) | 44.7% | **57.8%** |
| Xbox kernel | 10.5% | 13.5% |
| `D3D` | 6.6% | 8.5% |
| `DSOUND` | 1.0% | 1.3% |
| `XPP` | 0.3% | 0.4% |
| QEMU C (dispatch, helpers, devices) | 14.2% | 18.3% |
| **HLE-replaceable (libraries + kernel)** | **18.3%** | **23.7%** |

**The bound: HLE can reach ~18% of vCPU time** (24% of non-idle work). At
32.35 ms/frame that is -5.9 ms if the native replacement were free, and it is
not -- a real D3D8->Vulkan layer still tracks state and issues calls on the
same thread. Halve it and the honest figure is **~3 ms/frame, ~9%**.

The other 58% is the game's own logic. It still needs x86->AArch64
translation on ARM, and it carries the 54% backend stall on guest data that
sections Y and Z showed is unreachable by any codegen change. **HLE's decisive
advantage on x86 -- that guest code runs natively, with no dynarec at all --
does not exist on this host.**

### The unexpected result: NV2A now costs more than the vCPU

Cycle shares of the whole process on this scene:

| thread | share | composition |
|---|---|---|
| NV2A/pgraph (29658) | **38.6%** | libxemu 13.9%, libc 11.3% (`memcpy_opt` 7.7%), **`vulkan.adreno.so` 10.5%**, kernel 2.5% |
| vCPU (29648) | 34.9% | JIT 30.1%, libxemu 4.2% |
| others | ~26% | |

This contradicts the 67.7 / 20.4 split recorded earlier in this document.
Different scene and a much faster vCPU since -- the TSO barrier elision
(section AE) removed a large slice of vCPU work, which would shift the ratio
this way. **Hypothesis, not established**; worth re-measuring the old scene
before treating either number as the current truth.

**Consequence for HLE:** its biggest absolute saving is the NV2A thread, which
HLE largely deletes -- but the vCPU does not block on it, so that does not
convert to frame rate. What it would buy on a handheld is sustained clocks and
battery. An indirect path via memory-bandwidth contention -- NV2A spends 7.7% of the
process in `memcpy_opt` plus ~6.7% in CAS atomics, and the vCPU is
memory-stall-bound -- **is already ruled out**, twice, and this section should
not have re-raised it. `debug.xemu.surface_scale` multiplies NV2A pixel work
while leaving guest work identical, and vCPU IPC is flat across frame weights
(1.69/1.67, 1.85/1.90, 1.86/1.90). If GPU traffic were evicting the vCPU's
working set, heavier frames would show lower IPC. They do not. See section 5
and "L3 contention from the GPU: directly tested, and it is not there".

### Verdict

**Do not rewrite.** ~9% realistic on the pacing thread, bought with per-title
D3D8 signature matching, a from-scratch x86->AArch64 dynarec, and the accuracy
regression that has kept Cxbx-Reloaded behind xemu since 2003.

**But this reprices Turnip.** The prior estimate had the Adreno driver at ~5.3%
of process CPU on a thread that did not matter. It is **10.5%**, on the
single largest thread. That does not make it a frame-rate lever -- the vCPU
still does not wait on it -- but it is a much larger thermal target than
assumed, and `libadrenotools` support is already wired into CMake and settings
(`libs/libadrenotools` is simply not vendored).

---

## AI. TURNIP DRIVER COMPARISON (2026-09-18)

Six user-supplied Vulkan drivers vs the system Adreno driver, deterministic
slot-5 benchmark, 1200 frames, two passes in the same order.

**First: custom drivers had never been able to load.** CMake looked for
libadrenotools at `app/src/main/libs`; the submodule is at `android/app/libs`.
`HAVE_ADRENOTOOLS` was therefore never defined and
`xemu_android_get_vk_proc_addr()` compiled to `return NULL` whatever settings
said. The nested `linkernsbypass` submodule had also never been initialised.
Fixed in 26c14cd838; the feature has existed and been dead since May.

**The harness reproduces to 0.01 ms.** Baseline 32.18 / 32.17 ms across passes
separated by 14 intervening runs, procCPU identical, 29->31 C. That is far
tighter than the +-0.3 ms replay noise floor, so sub-0.2 ms differences are
real here.

| driver | vcpu ms | spread | vs base | fps | procCPU | vs base |
|---|---|---|---|---|---|---|
| system (none) | 32.17 | 0.01 | — | 29.62 | 158% | — |
| Turnip v26.2.0 R4 | 32.00 | 0.00 | -0.17 | 29.71 | 154% | -4 |
| **Turnip v26.3.0-R3** | **31.95** | 0.00 | **-0.22** | **29.77** | **153%** | **-5** |
| Turnip v26.3.0-R5 | 31.99 | 0.02 | -0.18 | 29.80 | 153% | -5 |
| mrpurple T23 | 31.68 | 0.10 | -0.49 | 29.56 | 152% | -6 |
| mrpurple T24 | 31.96 | 0.02 | -0.21 | 29.75 | 154% | -4 |
| mrpurple T30 | 31.95 | 0.05 | -0.22 | 29.77 | 155% | -3 |
| Qualcomm Adreno 840 | 33.47 | **2.17** | **+1.29** | 28.54 | 160% | +2 |

**Result: the prediction held.** No meaningful frame-rate change — the vCPU
does not block on the NV2A thread, so moving driver work does not move fps.
Every Turnip build is 0.2-0.5 ms better on vCPU and 3-6 points lower on process
CPU, consistent across both passes. In fps terms that is nothing; as a thermal
figure it is ~3%.

**Reconciling with section AH's 10.5%.** That was the share of process cycles
inside `vulkan.adreno.so`. Turnip does not remove that work, it *replaces* the
implementation — so the measurable delta is the difference between two drivers
(~3%), not the whole 10.5%. The earlier framing overstated the available win,
and this corrects it.

**Turnip shifts ~1 ms from CPU to GPU wait.** The overlay reads `GPU wait: 0 ms`
on the system driver and `1 ms` on both R3 and T23. Consistent with slightly
less CPU-side driver work and slightly more waiting on the hardware.

**The Adreno 840 package is the one to avoid.** It loads and renders on a 740,
but is the only config that does not reproduce: 32.38 then 34.55 ms, a 2.17 ms
spread against 0.01-0.10 for everything else, pushing the frame past the 33.3 ms
budget and fps to 27.51.

**Visual check, honestly inconclusive.** Frames are not synchronised between
runs, so a pixel comparison is not possible; R3 and T23 both render the scene
with correct geometry, textures, decals and HUD in the frames captured. Subtle
or intermittent glitches need play-testing, not screenshots. An initial read
that T23 was "skipping work" (lowest vCPU, lowest fps) did not survive: its
0.06 fps deficit is inside fps noise, and the GPU-wait change it showed is
shared with R3.

**Verdict.** Keep the system driver as the default. Turnip is worth having as
the user-supplied option it now is — best measured pick **v26.3.0-R3 or R5**
(lowest CPU with the highest fps of the set) — but the honest sell is a few
percent of CPU and thermal headroom, not frame rate.

---

## AJ. THE OUT-OF-LINING IS CLOSED (2026-09-19)

Section AC left a salvage open: remove the three register moves at each stub
call site and the wash becomes ~2%. Both halves are now built and measured.

**Baked the MemOpIdx** (removes `movz x3, oi`): the stub table is keyed on
atomicity as well as (is_ld, mmu_idx, size), so the value can be exact rather
than approximate. Section AC's attempt rejected 92% of sites because MemOp
carries MO_ATOM_* above bit 8 and the diagnostic masked with 0xff. Coverage is
unchanged at 92.4%.

**Constrained the operands** (removes `mov x16, addr` and `mov data, x16`): the
stub ABI moved off X16/X17 -- TCG_REG_TMP0/TMP1, reserved, so TCG can never
place an operand there -- onto X0 for the address and loaded value and X1 for
stored values, scratch in X15/X16/X17. None needed reserving; qemu_ld/st carry
TCG_OPF_CALL_CLOBBER and all three are call-clobbered. Constraints are selected
through `C_Dynamic` on the runtime knob, which is safe because the knob queues
a TB flush.

**Section AC's stated risk did not materialise.** The worry was that the
allocator would insert its own moves where it could not satisfy a fixed
register. It satisfied every one: 56567 loads, 37367 stores, **zero misses**.

### Everything worked and the answer is still no

| | stub off | stub on | |
|---|---|---|---|
| code size | 25.24 MB | 17.93 MB | **-29%** |
| L1I miss/frame | 2,471k | 1,787k | **-27.7%** |
| host insns/frame | 180.6 M | 192.1 M | **+11.5 M** |
| vcpu ms/frame | **32.22** (spread 0.05) | **32.38** | **+0.15 ms, +0.5%** |

AC's stub was +0.64 ms; this one is +0.15 ms, an 0.49 ms gain against the
0.67 ms the model predicted for removing those moves -- so the model was about
right on the *delta*, having been wrong on the absolute level.

### Why it cannot be salvaged further

At ~5.5M accesses per frame the `BL` and `RET` alone are ~11M instructions,
which is essentially the entire +11.5M that remains. The moves are gone; what
is left *is* the call. **An out-of-lined access cannot cost less than the pair
that makes it out-of-lined**, and at this access rate that pair costs more than
the instruction fetch it saves. There is no third thing to remove.

Run-to-run spread with the stub on is **2.02 ms** against 0.05 ms for the
inline path (host insns swing 32.5M across identical configs), which would
disqualify it even at break-even.

Kept, default off, constraints in place, so this is not priced a fourth time.

### A measurement was taken against unchanged code

The first half was committed with numbers from a binary that did not contain
it. `tcg/tcg.c` -- and so `tcg/aarch64/tcg-target.c.inc` -- compiles into
**`libsystem.a`**, not `libqemu-i386-softmmu.a`, so the documented two-step
rebuild recompiled the object and rebuilt the wrong archive, silently. The
giveaway was there and was misread: stub coverage came out 92.4% against AC's
92.5%, which was taken as "unchanged by the change" when it was unchanged
because nothing had changed. Both traps are now in CLAUDE.md, including that
`ui/xemu.c` is CMake-compiled and must *not* be put back into `libsystem.a` --
doing so aborts at startup with `assertion "mutex->initialized" failed`, which
reads like anything but a duplicate QOM registration.

---

## AK. GAMES DO CHANGE THE x87 ROUNDING MODE (2026-09-19)

The native x87 path skips `gen_flcr()`, so guest rounding-mode changes are
discarded, and the comment in `translate.c` justifying that said "this is not
a regression". The softfloat path *does* honour the control word
(`update_fp_status()` -> `set_x86_rounding_mode()`), so the claim rested
entirely on the assumption that no game asks. Measured instead.

**Method.** Count guest-initiated writes to the x87 control word -- `FLDCW`,
`FLDENV`/`FRSTOR`, `FXRSTOR`, deliberately *not* QEMU's own reset -- bucketed
by the RC field (bits 10-11), counting transitions rather than repeats so a
program rewriting the same default does not register.

| title | directed RC changes | modes |
|---|---|---|
| **Halo** | **2,378,791** | round-toward -inf |
| **Tony Hawk's Pro Skater 2x** | **4,047,209** | 4,046,848 round-down + 361 round-toward-zero |
| Jet Set Radio Future | 0 | — |
| Metal Gear Solid 2 | 0 | — |

**Half the sample does it, thousands of times a frame**, in the set-mode /
operate / restore pattern that implements `floor()` and C float->int casts.
THPS2x uses both idioms: round-down for flooring and round-toward-zero for
truncating casts. `perf.hard_fpu` defaults to true, so every one of those
requests is currently ignored and the operation runs round-to-nearest.

**What this does not show.** Visible error. Both games play correctly today,
and round-down and round-to-nearest agree on any value that is already
integral -- which may be most of what these paths convert. Establishing
impact needs an A/B against the softfloat path (`perf.hard_fpu = false`),
which honours the control word; that is a separate experiment.

**What it does show** is that the premise the omission rested on is false, so
`flcr` is worth implementing rather than dismissing. The remap is a
read-modify-write of FPCR (MRS, BFI RMode into bits 22-23, MSR), with x86 and
ARM encoding the two directed modes in opposite order -- x86 01 is toward
-inf, ARM 01 is toward +inf. The hazard to design around: FPCR is per-thread
state shared with QEMU's own C code on the vCPU thread, so a TB that changes
it changes the mode seen by everything else until something restores it.

Instrumentation kept -- it is ~6.7k increments/frame in the heavy case -- so
this is settled by counting rather than by argument.

---

## AL. WHAT FEX TEACHES US (2026-09-20)

Read FEX-Emu's "The scourge of x86 emulation" (2026-09-17) and their tree,
looking for anything transferable. The comparison is unusually clean: **their
central problem is one we do not have, for a structural reason.**

### Their hard problem is free for us

The whole article is about paying for x86-TSO on every memory access. FEX
emulates multi-threaded Linux programs, so guest threads observe each other's
stores and the ordering can never be dropped. The Xbox has one CPU, so
`max_cpus == 1`, and section AE deleted the store side outright. That was the
single biggest win of this project and it is not available to them.

The same fact quietly covers two more of their chapters. Their table of 19
`LOCK` ops mapped to ARMv8.1 atomics, and the patchpoint/fault-handler
machinery for split-locks, exist because unaligned atomic RMW must stay atomic
against other guest threads. QEMU derives `CF_PARALLEL` from
`max_cpus > 1` (`tcg-accel-ops-mttcg.c`), so ours is never set, and
`tcg-op-ldst.c` then forces `MO_ATOM_NONE` -- every guest atomic degrades to a
plain read-modify-write. We get their worst correctness problem for nothing.

### The load side is not a cost to recover -- removing it is a 1.4% regression

We keep load-load ordering deliberately (device -> guest notifier polling).
The old note claimed that cost ~0.3 ms. Re-measured, slot 5, 1200 frames,
both orderings:

| | vcpu ms | host insns/frame | code |
|---|---|---|---|
| load barriers kept | **32.29** (spread 0.14) | 182.2 M | 25.25 MB |
| no barriers at all | **32.74** (spread 0.02) | **403.2 M** | 24.32 MB |

Removing them emits *less* code and executes **2.21x** the instructions. Fewer
generated, 221 M more executed: the extra work is guest-side. Without
load-load ordering the notifier poll reads stale memory and the guest spins --
the hazard the comment predicted, now visible as a number. The earlier 0.3 ms
figure does not reproduce and has been corrected in place.

### The one transferable idea, and why it is still unpriced

FEX's answer to load ordering is `LDAPR` (FEAT_LRCPC): an RCpc load giving the
same load-load guarantee in *one* instruction, at plain-load speed on modern
cores. QEMU has no notion of this -- `tcg_gen_req_mo()` only emits `DMB`. The
Thor supports everything required: `lrcpc`, `ilrcpc` (LRCPC2) and `uscat`
(LSE2, which keeps unaligned ordered loads atomic within a 16-byte granule and
so removes most of what forced FEX into patchpoints).

**The A/B above cannot price it.** `mb_mode=2` removes the *ordering*, not just
the barrier, so the guest diverges and the comparison measures spinning rather
than barrier cost. Pricing `LDAPR` means implementing it, because it is the
only configuration that removes the instruction while keeping the guarantee.

### Built it. It faults on unaligned access, exactly as the article warns

Implemented behind `debug.xemu.ldapr` (commit 53f2b87571). It boots, renders
the slot-5 scene correctly, and then kills the emulator about a second into
real gameplay.

ARM's ordered loads require natural alignment. FEAT_LSE2 (`uscat`, present
here) relaxes that only *within* a 16-byte granule; an unaligned access
crossing one still faults. x86 code crosses those boundaries freely.

Bisected with diagnostic modes rather than assumed:

| mode | result |
|---|---|
| LDAPR every size, barrier kept | **dies** |
| LDAPR byte loads only, barrier kept | survives |
| no LDAPR (control) | survives |

Byte loads cannot be misaligned and take the identical `ADD` + `LDAPR` path,
which rules out the address composition and the barrier suppression and leaves
only alignment. Note the process dies with **no signal in logcat** -- worth
knowing, because it looks like a silent disappearance rather than a fault.

**So the idea is blocked, not disproven.** Making it usable needs the machinery
FEX built for this exact reason: a patchpoint at every ordered load, a SIGBUS
handler that rewrites the faulting site to `DMB` + plain load, and cache
maintenance. Until that exists the barrier cost stays unmeasured, because no
knob can isolate it -- removing the ordering changes what the guest does.

### Built the patchpoint. It works, and it is neutral

**The blocker was never LDAPR.** `qemu_thread_create()` masks every signal
except SIGSEGV, SIGFPE and SIGILL, above a comment saying blocking them is
undefined behaviour, plus `TODO avoid SIGBUS loss on macOS`. SIGBUS is in that
same class, so a hardware alignment fault on the vCPU thread was *blocked*, the
kernel forced the default action, and the process died with **no handler run
and no crash dump** -- which is why every diagnostic came back empty. The TODO
is not macOS-specific. Unblocking it is worth keeping regardless: before this,
any hardware SIGBUS in the emulator was unreportable.

Two other things had to be right first, both found by measurement rather than
reasoning: a 20-line native probe showed unaligned LDAPR returns correct data
at offsets 1-7 and faults only across a 16-byte granule (14, 15, 31); and
`system/cpus.c` owns SIGBUS for the process and re-raises anything that is not
a machine check, so a handler installed elsewhere is both overwritten and
unreachable. The patcher is called from inside it.

**Mechanism:** `ADD + LDAPR + NOP` per ordered load; on `BUS_ADRALN` the site
is rewritten in place to `LDR + DMB`, barrier first so a concurrent execution
sees `LDAPR+DMB` or `LDR+DMB`, never a load with no ordering. 105-115 sites
demote on slot 5, zero foreign faults -- the vast majority of sites never
straddle, which is what makes the approach viable at all.

**Result:**

| | vcpu ms | spread | fps | host insns |
|---|---|---|---|---|
| DMB + LDR | **32.33** | 0.04 | 29.53 | 180.9 M |
| ADD + LDAPR | **32.52** | 0.38 | 29.64 | 382.6 M |

The instruction doubling is **Halo's clock spin, not extra work**: that loop
waits on wall-clock, so cheaper iterations simply mean more of them. Eliding
the spin in both configs converges them at 160.2 M against 161.0 M.

That is real evidence LDAPR is cheaper per access than DMB + LDR -- and also
that the `ADD` plus the patchpoint `NOP` cost back slightly more than the
barrier saves. Two instructions for two, plus a slot.

**Do not use the spin-elision harness for A/B work.** Elision engages
inconsistently run to run (519 to 11,177 us slept per frame) and swamps
everything; one baseline run under it read 36.17 ms / 25.50 fps against a true
32.33 / 29.5.

### Removed the slot. Still inconclusive, and now the harness is the limit

The NOP went away by rewriting *both* words on demotion: `ADD + LDAPR` becomes
`DMB + LDR` with the original index addressing, which needs no scratch. Two
instructions against two. No side table is needed -- the ADD already encodes
base, index and extension, the LDAPR encodes size and destination.

**The model predicted the move correctly.** Slotted was +0.19 ms; one fewer
instruction per load is ~0.15 ms at section AA's 0.133 cyc/insn, and the first
clean pair came out 32.20 vs 32.21 -- dead even.

Over six runs it does not resolve:

| | vcpu ms | spread | fps |
|---|---|---|---|
| DMB + LDR | **32.22** | 0.03 | 29.59 |
| ADD + LDAPR | **32.54** | 0.53 | **29.71** |

The two metrics disagree, and they disagree *for a known reason*: Halo's clock
spin absorbs anything freed. It waits on wall-clock, so cheaper iterations mean
more of them -- inflating vcpu (which is wall time) while nudging fps toward
the 30 fps pace. LDAPR's spread is 18x the baseline's for the same reason.

**The session drift now exceeds the effect.** One unchanged baseline run came
in at 34.33 ms against 32.20/32.23 for the same config. Adjacent pairs give
+0.01, +0.51 and -1.65 ms. That is not a measurement any more.

### That verdict was wrong. On combat it is a large win

The "neutral" conclusion above came entirely from the slot-5 benchmark, and
that benchmark cannot see this effect. Slot 5 lets the guest idle: ~1400 of
1999 frames sit in the 33-36 ms band and the clock spin swallows anything
saved. The frames that matter are the *heavy* ones, where there is no slack to
absorb a saving -- and slot 5 barely has any.

Built the workload that was missing: load slot 2, replay a recorded 29 s
combat section (21,784 input events), measure 800 frames. Deterministic, and
genuinely heavy -- 9% of frames over 50 ms against slot 5's ~1%.

| | baseline | LDAPR | change |
|---|---|---|---|
| mean frame time | 35.45 ms | 34.25 ms | **-3.4%** |
| vcpu ms/frame | 33.02 | 32.60 | -1.3% |
| fps | 28.23 | **29.23** | **+3.5%** |
| **frames > 50 ms (sub-20fps)** | **82** | **38** | **-53.7%** |
| frames > 66 ms (sub-15fps) | 18 | 12.5 | -30.6% |

**Two independent measurements agree.** Live manual play gave -54.7% on frames
over 50 ms; the deterministic replay gives -53.7%. The individual runs do not
overlap either (LDAPR 32 and 44, baseline 73 and 91), and the ordering was
0,1,1,0 with the *last* baseline the worst of the four, so drift pushed against
LDAPR rather than for it.

This is the same shape as the TSO barrier elision (section AE), which earned
its place on exactly this metric.

**The lesson is about the harness, not the instruction.** A benchmark on a
self-pacing scene measures the wrong thing: it reports mean frame time while
the guest converts every saving into idle spin. Six careful runs on slot 5
resolved nothing because the effect is not in the mean -- it is in the tail,
and slot 5 has almost no tail. Any future change aimed at frame-time relief
should be measured on the combat replay, not slot 5.

**Still default off** pending play-testing: the mechanism is self-modifying
code driven from a signal handler, and while it has been clean across every
run so far (zero foreign faults, correct rendering, ~110-140 sites demoted) it
deserves soak time before becoming the default.

Kept default off (`debug.xemu.ldapr`, modes 0-5 including size bisects). The
SIGBUS unblock in `qemu_thread_create()` stays regardless: it is an independent
bug fix, without which no hardware SIGBUS in the emulator is reportable.

Rough shape of the upside: at ~3.3 M guest loads per frame there are ~3.3 M
`DMB ISHLD` in 182 M instructions (~1.8%). Whether that is worth 1% or 5%
depends entirely on what a `DMB ISHLD` costs on an X3 when there is nothing
outstanding to order, which is not something this harness can isolate.

**Implementation note if it is attempted:** `LDAPR` has no register-offset
addressing. The softmmu fast path loads from `addend + guest_addr`, so the
address must be materialised first -- the trade is one `ADD` for one
`DMB ISHLD`, not a free removal.

---

## AM. MEASURING THE TAIL, AND WHAT IT REOPENS (2026-09-21)

Section AL's correction -- that slot 5 hid a 53% tail improvement -- raises two
questions: how to measure the tail reliably, and which rejected ideas were
rejected on the wrong metric.

### Building a tail benchmark: what each ingredient actually controls

| workload | intensity | repeatability |
|---|---|---|
| slot 5, no input | low | excellent |
| slot 2 + synthetic input | low | **excellent** (6/6 frames >50 ms, matching histograms) |
| slot 5 + synthetic input | **variable: 12, 26, 71** | poor on the tail |
| slot 2 + recorded combat | high | positional drift |

**Repeatability comes from the input.** A hand-played recording drifts, because
replay is wall-clock scheduled: if the configurations run at different speeds
the inputs land on different guest frames and the player ends up elsewhere.
Synthetic input fixes this -- rotation plus forward is a closed circular path,
so position stays bounded. `android/tools/make-synthetic-recording.py`.

**Intensity comes from the save state, and cannot be synthesised.** The same
synthetic input is light from slot 2 and variable from slot 5. Stick-waggling
does not create load; being somewhere busy does.

**A single heavy reading is not a heavy benchmark.** slot 5 + synthetic gave
71 frames over 50 ms once and 12 and 26 later under identical settings. The
first was taken back-to-back after another batch, the others after 25 s
cooldowns -- so that tail was substantially *thermal*. This is the same class
of error as section AL: reading one number without checking it reproduces.
**A tail metric needs a state saved inside sustained action**; none of the
existing states qualifies.

### Which rejections were made on the wrong metric

Everything below was judged on slot-5 mean frame time, which section AL showed
cannot see a tail effect. Re-examined:

| idea | rejected because | does the tail change the verdict? |
|---|---|---|
| **ld/st out-of-lining** (AC, AJ) | +0.15 ms on slot 5; BL/RET floor | **Worth retesting.** It cut code size 29% and L1I misses 27.7% and paid for it in instructions. Heavy frames are where I-cache pressure is highest and where the fetch saving is worth most, so the trade could invert exactly where it matters. The strongest candidate. |
| SRA / register allocation (K) | 2-4% ceiling from TB-boundary cost | Unlikely. Boundary sync is a per-block constant, not concentrated in heavy frames. |
| fastmem (AG) | +12.5% regression | No. Its cost is fault and flush machinery, which gets *worse* under load. |
| LSE atomics | no measurable effect | No, and moot: CF_PARALLEL is never set, so guest atomics are already plain RMW. |
| spin elision (M, N) | headroom, not frame rate | No -- and now demonstrated harmful: with detection on but not engaging, a baseline run cost +3.8 ms for the machinery alone. |
| AOT / HLE (8, AH) | 54% backend stall unreachable | No. Memory stalls do not care which metric is used. |

### Pushing LDAPR further

An ordered load is `ADD + LDAPR` against `DMB + LDR`. Ideas, roughly by
expected value:

1. **Skip ordering for provably private loads.** Load-load ordering exists for
   one reason here: a guest polling an NV2A notifier in RAM. Loads that cannot
   observe device memory need neither LDAPR nor a barrier. Stack-relative
   accesses are the obvious candidate and are a large share of all loads.
   Correctness argument required before any of this is attempted -- "devices do
   not DMA into the guest stack" is an assumption, not a proof.
2. **Let TCG eliminate the ADD.** The address composition is emitted opaquely
   in the backend, so the optimiser never sees it. Expressed in the IR, repeated
   `base+index` for consecutive accesses to the same page would be common
   subexpression eliminated.
3. **Combine with out-of-lining.** Currently mutually exclusive (the stub uses
   TMP2, which the ordered load also wants). If the stub is revived by the item
   above, the two address the same instruction stream from opposite ends --
   fetch and ordering -- and might compound.
4. Hardware TSO mode -- Apple Silicon only, nothing to do on Snapdragon.


---

## AN. SHADER AND PIPELINE CACHES PERSISTED (2026-09-22)

The Vulkan renderer kept both of its caches in memory only: SPIR-V in an LRU,
and a `VkPipelineCache` created empty every launch and never saved. The GL
renderer's disk cache did not carry over when the port moved to Vulkan, so
every session paid every first-seen shader again.

**Built:** SPIR-V keyed on the generated GLSL text (`vk/glsl.c`), so a
generator change can never serve stale code; the full text is compared, so a
hash collision is a miss. Pipeline cache loaded at init if vendor, device and
`pipelineCacheUUID` match (a Turnip build starts clean), saved from the
per-frame sync once new pipelines have been quiet for 3 s. The snapshot is
taken on the pfifo thread (<1 ms) and written on a detached thread. Only a
pipeline creation over 2 ms dirties the cache, so warm sessions never re-save.

**Measured:** cold (both caches deleted) vs warm, interleaved, same process
sequence, slot 5, 300-frame benchmark started 1 s after the state load so
first-seen shaders fall inside the window. Absolute counters from each run:

| | cold (x3) | warm (x3, fully cached) |
|---|---|---|
| SPIR-V compiled | 94, 75, 94 — 350-440 ms | **0** (96 disk hits, 4 ms) |
| pipelines created | 76-90 — **1.5-1.8 s** | 90 in **37 ms** |
| GLSL generation | 14-17 ms | 13 ms |
| worst frame | **2105-2241 ms** (one 166) | **63-65 ms** |
| frames > 66 ms | 4-6 | **0** |
| frames >= 80 ms | 3-4 | **0** |
| mean frame time | 34.0-34.3 ms | 33.3-33.4 ms |

**The driver is the bigger half.** Pipeline creation — Adreno compiling SPIR-V
to ISA — costs 4x what glslang does, so the `VkPipelineCache` matters more than
the SPIR-V cache. GLSL generation is negligible, which is what made keying on
the text free.

This is a stutter fix, not a throughput one: frames > 50 ms are unchanged
(they are the scene's own heavy frames, section S), and the mean moves only
because the multi-second compile stall is gone. It affects the first time a
shader is seen *per install* rather than per session.

Log line: `vk-cache:` under `xemu-stdout`, every 10 s while pipelines are
being created, and on each save.

---

## AO. ADPF HINT SESSION: nothing to ask for on this device (2026-09-23)

Built an `APerformanceHint` session (`android/app/src/main/cpp/xemu_adpf.c`,
dlsym'd since minSdk is 28) over the vCPU and `nv2a.pfifo` threads, reporting
each guest-frame interval against a pace-following target (16.7 or 33.3 ms).
The platform accepts it: `dumpsys performance_hint` shows HAL support true,
both tids, target 33.3 ms, `SessionAllowed: true`.

**A/B, in one process, property toggled live.** Slot 2 + `combat2` replay,
800 frames, order 0,1,1,0,0,1,1,0, 20 s cooldowns. Engagement checked per run:
ADPF reports +0 on every off run, +799 on every on run.

| pair | off: >50 ms | on: >50 ms |
|---|---|---|
| 1 | 12 | 21 |
| 2 | 38 | 33 |
| 3 | 27 | 35 |
| 4 | 29 | 73 (22 over 66 ms) |

On wins 1 of 4. The device climbed 57 -> 68 C across the batch; the 73 is
most likely thermal and is **not** established as harm, but there is no
benefit to set against it.

**Mechanism: there is no clock left to request.** Sampled during the replay,
12 samples each way: the X3 (cpu7, where the vCPU is pinned) sat at its
3187 MHz maximum in every sample *without* ADPF, and the A715/A710 cluster
running `nv2a.pfifo` at its 2803 MHz maximum. Identical with ADPF on. A
pinned thread at ~100% utilisation already holds schedutil at the top;
ADPF can only raise a floor that is already at the ceiling, and cannot lift a
thermal cap.

**Disposition:** kept, off, property-only (`setprop debug.xemu.adpf 1`) --
no settings toggle, since it would promise nothing. The case where it could
matter is a phone whose governor downclocks eagerly or does not pin; this
makes that a one-line test. Side result: the tail on this replay is now
12-38 frames over 50 ms at baseline (LDAPR on), against 82 in section AL.

---

## AP. A REPEATABLE TAIL BENCHMARK: slot 7 + walk_fps_dip (2026-09-23)

Saved by the user at a point where the frame rate reliably dips: walking into
a health pack triggers a full-screen visual effect. Replayed with the
recording `walk_fps_dip`, LDAPR on, 400 frames, 25 s cooldowns:

| run | frames > 50 ms | 45-50 ms | 50-60 ms | mean |
|---|---|---|---|---|
| 1 | 16 | 10 | 11 | 34.2 ms |
| 2 | 13 | 9 | 9 | 34.1 ms |
| 3 | 15 | 10 | 12 | 34.2 ms |

Spread +/-1.5 frames, against 12/26/71 for slot 5 + synthetic input (section
AM). This is the first state that meets section AM's requirement, and it
unblocks the tail retests (out-of-lining, LDAPR follow-ups, flcr cost).

**Representativeness caveat, from the user:** the load is a full-screen effect,
which likely stresses NV2A fill and whatever guest code drives the effect, not
the AI/physics mix of a firefight. Real combat dips only 1-2 fps,
intermittently, and could not be captured repeatably. So a change that helps
here should be confirmed in live play before it is called a combat win --
the same two-source standard section AL used.

---

## AQ. #32 RETESTED ON THE TAIL: out-of-lining still buys nothing (2026-09-25)

Section AM reopened ld/st out-of-lining on the argument that its fetch saving
(-29% code, -27.7% L1I misses, section AJ) might pay off exactly in the heavy
frames that slot 5's mean could not see. Retested on the section AP tail
benchmark.

### First, it crashed -- a latent bug from the LDAPR work

With LDAPR on at startup (now the default), switching the stubs on killed the
vCPU thread within a frame: `SEGV_ACCERR` with `pc == lr == x16 + 0x80035bdc`
-- guest RAM's host base plus a kernel-space guest address. The stubs are
generated once, in the prologue, by calling `tcg_out_qemu_ld_direct`, which
consults `g_ldapr`; built with LDAPR on, their loads compose the address into
TMP2 = X30, which inside a stub is the return address, so the `RET` jumped
into the guest data just loaded. Section AJ measured before LDAPR existed, so
never saw it. **Fixed:** `xemu_emit_ldst_stubs()` emits with LDAPR forced off,
which is always the right body since stubs are refused while LDAPR is on.
Verified: process survives the toggle, 39,163 sites stubbed, no crash through
four full replays.

### Result

Slot 7 + `walk_fps_dip`, 400 frames, in one process, order ABCCBA ABCCBA,
each toggle followed by a 20-frame throwaway bench so the TB flush falls
outside the window. Engagement per run: LDAPR mode and demoted sites logged;
stub sites (absolute) rose only in stub runs, apart from ~5k translated during
the cooldown before the next flush.

| config | frames > 50 ms | mean | > 66 ms | vcpu ms |
|---|---|---|---|---|
| A: LDAPR on (ships) | 17, 16, 21, 15 | **17.3** | 0, 6, 10, 9 | 32.59 |
| B: LDAPR off | 18, 18, 21, 17 | **18.5** | 0, 3, 10, 10 | 32.26 |
| C: LDAPR off + stubs | 23, 19, 20, 17 | **19.8** | 4, 4, 11, 6 | 32.25 |

Out-of-lining against its own baseline (C vs adjacent B): worse in 2 pairs,
better in 1, tied in 1; +1.25 frames. Against what ships (C vs A): +2.5
frames. **No tail benefit, and it cannot coexist with LDAPR.** #32 is closed
on both metrics now.

### The benchmark's own calibration, which matters more than the verdict

**LDAPR barely moves this scene: 17.3 vs 18.5 (-7%), against -53.7% on the
slot-2 combat replay (section AL).** A change known to cut a heavy CPU tail in
half registers as noise here. So slot 7's dip is largely *not* bound by
vCPU memory-ordering cost -- consistent with the user's caveat that it is a
full-screen effect, plausibly NV2A-side. **Use slot 7 for NV2A/GPU-side
changes; do not read a null CPU-codegen result on it as a combat verdict.**
C2 above is therefore "no benefit found where it could be tested", not
"proven useless in combat" -- though with section AJ's mean-time result it has
now failed on every workload available.

**Heat.** The batch ran at 66-73 C (the calibration runs in AP were at 56 C),
and frames over 66 ms rose from 0 to ~10 across it in *every* configuration.
ABCCBA ordering balances that drift between configs; it does not remove it.
Heat is the largest single effect in this table.

---

## AR. EVERY PAUSE DEMOTED THE APU TO A LITTLE CORE (2026-09-26)

Reported by the user as "fps very bad after loading state 5". It was not the
load: **any pause and resume** -- panel, pause hotkey, backgrounding --
dropped slot 5 from 30.0 fps to ~24 for 15+ s. It surfaced now because the
dual-screen panel makes pausing routine.

**Chain, each link measured:**
1. The slowdown is not vCPU work: vcpu ms/frame stayed ~31.5 while frames
   took ~43 ms. The vCPU was *asleep* half the time (sampled thread state).
2. Off-CPU profile of the vCPU: **54% blocked in `qemu_mutex_lock` under
   `memory_region_dispatch_write` -> `voice_lock` (vp.c)** -- guest writes to
   APU voice registers take the APU's global `d->lock`.
3. APU frame rate stayed at a correct 1500/s, but **utilization rose from
   ~0.2 to 0.86** and decayed over ~15 s -- every frame ~4x dearer, so
   `d->lock` held ~4x longer. On-CPU samples 1300 -> 4957 per 4 s, spread
   across VP *and* DSP, i.e. the whole thread slowed, not one component.
4. **The thread moved cores**: cpu3-6 @ 2803 MHz before the pause, cpu0-2
   (A510, in-order) @ 2016 MHz after, still there 10 s later. Sleeping through
   the pause decays the scheduler's load estimate; on wake it is placed as a
   light task on a little core and climbs back slowly.

**Fix:** `mcpx_apu_frame_thread` and `voice_worker_thread` call
`pin_to_big_cores()` at start, as `pfifo_thread` already did (which is why
NV2A never showed this). Verified after a pause: APU on cpu3-6, utilization
0.18, **30.05 fps / first 30 frames 32.9 ms** against a no-pause control of
30.03 / 32.4; the user's scenario (panel controller mode, quick load, resume,
slot-5 bench) **24.0 -> 30.0 fps, frames > 50 ms 163 -> 22**.

A standing `apu: utilization > 0.7` warning (xemu-stdout) now flags the
signature if it recurs.

**Method note.** Three plausible theories were eliminated by measurement
before this one (load path, input release, DSP cycle debt), and the
"slowdown is permanent" reading was wrong -- the cumulative average hid a
recovery. Per-segment frame times and absolute sample counts are what broke
it open; percentages and averages did not.

---

## AS. LAUNCH TIME: where the black screen goes, and 0.42 s of it removed (2026-09-26)

Tap -> Halo title menu is 21.5 s: emulator ready ~2 s, Halo's own startup
~5 s (black), Microsoft + Bungie intros ~14 s. Skip-boot-animation works.
Quick Resume (-loadvm at startup) is the real lever and is queued (OPEN-WORK
A6). Two smaller things were examined first.

**Vulkan buffer allocation was 466 ms of every launch.** Timed per init step,
then per buffer: upstream's generous sizes ("FIXME: Profile buffer sizes")
totalled ~4.4 GB of device memory -- system RAM on the Thor. The two
inline-vertex buffers alone were 1279 MB each (267 ms); the depth/stencil
conversion buffers 800 MB each (sized for resolution scale 10).

Measured need, Halo boot + slot 5 + slot 7 and slot 2 replays: inline peak
**26 KB** of 1279 MB, index peak **777 KB** of 199 MB, zero flushes forced by
lack of space. (JSRF/MGS2/THPS2x were launched but their numbers matched the
common boot sequence; treat as untested.) Running out is already handled --
`ensure_buffer_space` flushes and restarts -- so only one worst-case draw has to
fit. On Android: conversion buffers sized for scale 4 (the Android clamp),
inline for exactly one worst-case draw (128 MB), index for eight; surface.c
now asserts the conversion-buffer fit.

Result, 3 launches: device selection -> main loop **0.55 s -> 0.13 s** in all
three; tap -> logo 7.4/7.4/7.6 s before, 6.8/6.8 s after (plus an 8.0 s first
launch after install). Regression: scale 1 slot 5 30.02 fps, slot 7 replay
29.12 fps / 15 frames > 50 ms (calibration 13-16); scale 4 runs both without
tripping the new asserts.

**Two misreadings, recorded so they are not repeated:**
- *"The pipeline cache load costs 0.5 s."* Its log line happened to print at
  the end of the slow stretch. A background-load rewrite then reported the
  load itself at **1 ms** -- the time was buffer allocation. Reverted.
- *"The vCPU is 19% blocked on the APU lock at startup."* True, and
  irrelevant: removing all voice-lock locking moved tap-to-logo 7.4 -> 7.3 s
  (dead-ends table). Off the critical path.

---

## AT. hakuX BOOTS HALO ~1 s FASTER; NOT THE DRIVER, IMAGE OR DRIVE (2026-09-26)

Tap -> Microsoft logo, same device, same brightness probe:
hakuX Debug 0.3.1 **5.4 / 5.4 / 5.8 s**; Boxxy **6.8 / 6.8 s** (Qualcomm),
**6.8 / 6.5 s** (Turnip). The hakuX release build crashes launching Halo here.

Host startup is level: first frame 0.76 s after tap for hakuX, ~0.8 s for us
since section AS (hakuX had already capped the same oversized Vulkan buffers --
it logs a `memory_budget`). The gap is guest time: kernel boot + Halo's own
startup.

Eliminated, one at a time:
- **GPU driver.** Turnip on Boxxy: 6.8 / 6.5 s (Turnip left enabled; it was
  installed but switched off since section AI).
- **Disc image.** Both apps load the same `.xiso.iso` file.
- **HDD image.** Boxxy on a byte-exact copy of hakuX's 315 MB drive:
  **6.6 / 6.6 / 6.6 s**. User's drive backed up and restored, md5 identical.

What remains is hakuX's translator (+734 lines in the AArch64 backend, a
tier-1 recompile of hot blocks at threshold 64, pinned guest-RAM base
registers -- section H). Its persisted TB cache is not the reason: it loaded
0 hints, and startup prewarm is disabled on Android in its changelog.
**Before porting anything, measure how much of the startup window is
translation (tcg_gen_code) versus execution** -- startup is dominated by
first-seen code, which our steady-state benchmarks never exercise.

---

## AU. STARTUP IS THE KERNEL'S DELAY LOOP -- AND 1 BOOT IN ~5 CRASHES (2026-09-26)

**Translation is not the startup cost.** vCPU profile over the black-screen
window: `tb_gen_code` inclusive **4.5%**. Guest-PC attribution (guest_map):
**72% of generated-code time is one block, 0x800153d0**, Xbox kernel.

It is `KeStallExecutionProcessor(us)` (decoded from `dump_pc`):
`mov eax,[0x8003aff0]; mul ecx; L: sub eax,1; jnz L` -- a delay expressed in
loop iterations, 0x16f = **367 per microsecond** (2 cycles/iteration on the
733 MHz P3). Halo's boot requests **1,417,174,095 iterations = 3.865 s** of
stalls up to the logo.

`debug.xemu.stall_hle` (translate.c recognises the bytes `83 e8 01 75 fb`,
misc_helper.c `helper_xemu_stall`; default 0 = off):
| mode | tap -> logo |
|---|---|
| 0 current | 6.7 s |
| 1 faithful (real-time wait, 1 ms chunks) | 7.7, 7.9 s |
| 2 no delay | 3.7 s |

So **our translated loop already runs faster than a real P3** and shortens the
kernel's delays by ~1.1 s; hakuX's is faster still, which is the ~1 s it
leads by (section AT). Eliding the delays entirely saves ~3 s.

**The boot crash, found while testing mode 2.** Interleaved 5x mode 0 vs 5x
mode 2, checked at 16 s: failures **1/5 (mode 0) and 2/5 (mode 2)**, all with
the same signature; a further 6 mode-0 launches reproduced it once, stuck >60 s.
Signature: stuck after exactly **60 ATAPI commands** (the kernel's first disc
burst), vCPU `halted=1 IF=0 eip=0x800151ef` -- the kernel's `cli; hlt`
bugcheck -- with the IDE interrupt count jumping to 400+ in ~20 ms (normal
boots total ~210). **Pre-existing, not caused by stall elision** (n too small
to say whether mode 2 raises the rate). hakuX logs `Inline AIO enabled from
crash marker`, i.e. it detects a crash of this kind and runs disk I/O inline
on the next launch -- a strong hint the cause is an IDE async-I/O race.

Do not ship mode 2 before this is understood: removing delays shortens every
kernel timeout, and a boot that already races the IDE controller is exactly
where that would bite.

---

## AV. THE BOOT CRASH WAS DEAD-FLAG ELIMINATION (2026-09-26)

~1 boot in 7 (6/45 with all defaults) stuck on a black screen forever after
the kernel's first 60 ATAPI commands.

**Chain, each link measured:**
1. Guest crash report (`guest-stuck`, one-shot on halted+IF=0): bugcheck
   **0x1E KMODE_EXCEPTION_NOT_HANDLED, 0x80000004 STATUS_SINGLE_STEP**, at
   0x80030e4d -- the second instruction of the kernel's clock ISR.
2. #DB delivery ring (`guest-#DB`): the timer interrupt arrived in Halo's
   DirectSound init (0x0019a8e8) with **EFLAGS 0x33a -- TF set, and reserved
   bits 3 and 5 set**. That code has no pushf/popf; reserved bits are
   impossible on hardware. EFLAGS was being built from garbage.
3. The only unmasked source: `CC_OP_EFLAGS` returns `cc_src` as-is. A check
   there (`cc-garbage`) caught it: **6 stray-bit reads per boot with defaults**
   (cc_src=0x00000008 at 0x0018cd01), **0 with dfe=0**, 12 with the CC inline
   paths off but DFE on (cc_src=0xfd000000 -- an address).
4. Bisection by crash rate agreed: 0/20 with all Android CPU features off, 0/20
   with the flags group off, 0/20 with cc_*/dfe off, 2/20 positive control.

**Cause:** the DFE pass (section J, ported from hakuX's tier1-opt) resets CC
liveness at labels, branches, calls and block ends -- not at guest memory
ops, which can leave a TB mid-way (fault, MMIO exit, notdirty/SMC). State is
restored at that instruction missing a cc write the pass deleted, so cc_op
can say EFLAGS while cc_src holds an operand. When a stray bit is TF, the
next iret single-steps the guest; a timer interrupt in that window
bugchecks the kernel.

**Fix:** DFE off by default (`g_xemu_dfe = 0`; `debug.xemu.dfe=1` to
re-enable). It never bought speed (section J: within noise), so a correct
version is not worth writing. Verified on the default build: stray bits
**0**, and **0 crashes in 30 boots** with the crash-inducing procedure (was
6/45; 30 clean at the old rate is ~1.5% likely by chance).

**Note for the hakuX comparison:** hakuX ships this pass in its tier-1
recompiler. Its boots may be exposed to the same fault.

**Diagnostics left in place:** `guest-stuck` (register/stack dump when the
guest halts with IF clear), `guest-#DB` (interrupt history on any #DB),
`cc-garbage` (stray EFLAGS bits, first 8 logged, count in the vCPU health
line). All cheap; together they turned a "black screen" into a bugcheck
code in one reproduction.

---

## AW. SKIPPING THE KERNEL DELAYS: 3.2 s OFF HALO'S BOOT, NOTHING ELSE MOVES (2026-09-27)

Re-measured on the DFE-fixed build (section AV), `debug.xemu.stall_hle`
mode 2 against mode 0, 10 launches each interleaved:

| | tap -> Microsoft logo | boots reaching Halo |
|---|---|---|
| mode 0 (today) | 6.6-6.9 s, mean **6.73** | 10/10 |
| mode 2 (skip) | 3.5-3.7 s, mean **3.56** | **10/10** |

-3.2 s, -47%. Requested delay is identical every boot (1,417,174,105
iterations); 0 stray EFLAGS bits in all 20.

**Other titles, 30 s after tap, mode 0 vs 2:**
- THPS2x requests **1,417,119,058** -- within 0.004% of Halo's. Two different
  games requesting the same ~3.9 s points at shared library code (likely
  DirectSound's init) running a wait to its full timeout every time. With the
  skip it is on the Treyarch intro at 30 s; without, still black.
- JSRF and MGS2 request ~4.0M (~11 ms): no gain, no change -- same guest PC
  and same screen (SEGA disclaimer / "Hideo Kojima") either way.

**Gameplay: never used.** Mode 2, iterations after boot = after 20 s of
slot 5 = after the full slot-7 replay (1,417,174,105 throughout). Slot 7
bench 30.05 fps in both modes. The skip only touches startup.

(Side observation, not isolated: slot 7 showed 0 frames > 50 ms in both
runs, against 13-21 in sections AP/AQ. Turnip was enabled in between
(section AT) and slot 7 is GPU-bound; confirm before crediting it.)

**Audio: checked by ear by the user** (Bungie intro, menu music, weapons) --
fine. **Shipped** as Advanced -> Fast boot, default on
(`xemu_android_set_fast_boot`, applied before emulation starts); the
`debug.xemu.stall_hle` property now overrides only when it holds 0-2, so
"auto" leaves the setting in charge. Verified: default 3.6 s (4.0 s on the
first launch after install), off 6.7 s.

## AX. THE LOWER PANEL'S COST: 0.13 W WHEN OFF, NOTHING ELSE (2026-09-30)

D3 asked whether blanking the Thor's lower panel during play buys power
or, through heat, performance. Measured with `android/tools/panel-idle-ab.sh`:
unplugged, Halo in-game from slot 7, one process, the panel forced
awake/dim/off by the PANEL_SLEEP debug broadcast. The order rotated each of
4 rounds; 20 s warmup was discarded, then 40 s sampled per phase.

Instrumentation checked first. A dry run (plugged in) printed absolute
values: backlight 1099 -> ~20 in each mode, fps and vCPU parsed from
`bench: RESULT`. The first fps probe matched no log line and would have
printed "?" silently. Charging was refused: current_now was +4 A, the
charger's story.

| mode  | mean power | vs awake | per-round delta (mW)   |
|-------|-----------:|---------:|------------------------|
| awake | 6.71 W     |          |                        |
| dim   | 6.70 W     | -0.01 W  | -88, +82, -56, +16     |
| off   | 6.58 W     | -0.13 W  | -188, -41, -99, -206   |

- **fps / vCPU:** 30.0 fps and 32.4 ms/frame in every phase.
- **CPU temperature:** drifted 70.4 -> 76.5 °C monotonically across the
  run. Per-mode differences sit inside that drift, so there is no thermal
  effect to claim.
- **Why dim does nothing:** the panel UI is mostly near-black, which an OLED
  barely pays for even at full drive. Off additionally stops the 1 Hz stats
  redraw.
- **Conclusion:** off is a ~2% battery nicety, not a performance lever.

Side finding: the window `screenBrightness` override is strongly non-linear
on this panel. 0.05 -> backlight 18, 0.1 -> 34, 0.2 -> 129, 0.3 -> 319,
0.4 -> 614, 0.5 -> 1025. At low drive the OLED shows a red cast on its left
side. The framebuffer is uniform there (sampled (19,21,6) left, centre and
right), so the cast is panel non-uniformity, not the app.

## AY. THE BLACK SCREEN WAS A MISSING glFlush: RESUME -1.9 s, EVERY BOOT -0.9 s (2026-09-30)

Open item: after Quick resume the state finished loading ~1.4 s after the
tap, but the scene appeared only ~3.6 s after it. Diagnosed with a timeline
on the cold-resume path, each step ruling something out:

1. **Guest and renderer were not stalled.** Guest frames arrived from +0.4 s
   after the load at a steady 30 fps. There were no sync misses and no
   pipeline compiles. Yet a `glReadPixels` probe of 5 screen points read
   exactly 0 until +2.2 s, then jumped straight to ~212: a cut, not a fade.
2. **Cold-only, and not the snapshot.** A warm load of the same snapshot
   showed content at once. So did the first load in a ~20 s old process.
3. **Process age, not boot stage.** With the load held back by 0, 1 and 2 s
   (`debug.xemu.resume_delay_ms`, since removed), the picture appeared at a
   fixed ~3.6 s after the tap: 3.58, 3.62 and 3.82 s, the last being just
   the normal first frame.
4. **The Vulkan output was correct all along.** The displayed surfaces were
   GPU-drawn every frame (~1,900 draws per frame, pvideo off). The CPU-mapped
   readback buffer's centre pixel was the scene, 82,86,51, from the first
   frame at +304 ms, while the screen stayed black.

**Cause.** On Android, `render_display()` copies the Vulkan display image
to a host-visible buffer and uploads it with `glTexSubImage2D` in the PFIFO
thread's GL context. The render thread samples that texture from its own
context. Nothing flushed the uploading context, so the uploads waited on the
driver's own submission heuristics, which in a fresh process took ~2.2 s.
Desktop xemu imports the image memory directly and never needed a flush.
GL guarantees cross-context visibility only after the writer flushes and
the reader rebinds; the HUD blit already rebinds every frame.

**Fix:** `glFlush()` after the upload, with `debug.xemu.display_flush=0` as
an A/B switch.

- **Instrumentation check:** with the flush off, a cold resume went black
  again (+2168 ms); with it on, +479 ms.
- **Cold resume:** picture with the first guest frame, +285 / +302 / +398 ms
  after the load, against +2.1 to 2.3 s before. Tap to picture ~1.7 s,
  was ~3.6 s.
- **Normal boot,** first picture after the first render frame,
  interleaved: on 1642 / 1628 / 1626 ms, off 2557 / 2497 / 2498 ms. That is
  **-0.9 s on every launch.**
- **Cost:** slot 7, 600 frames, ABBA, one process. vCPU ms/frame on 31.43*,
  31.15, 31.15, 31.13; off 31.08, 31.15, 30.98, 31.05 (*first benchmark in
  the process). Difference ~0.08 ms, inside noise; fps 30 throughout. The
  flush runs on the PFIFO thread, not the vCPU.

## AZ. C5: SLOT 7'S DIP WAS DISPLAY PACING, NOT EMULATION (2026-10-01)

C5 asked what the remaining >50 ms frames are made of, using section AP's
slot 7 scene.

**Harness correction first.** The dip is the state slot 7 loads into, a
full-screen effect, and walking into the health pack ENDS it.
`walk_fps_dip` is only 1.4 s long, so a benchmark started 2 s after the
replay (as `combat-replay-ab.sh` does) measures the scene after the dip:
0 frames over 50 ms in 3 of 3 runs. To measure the dip, benchmark straight
after the load with no replay: `android/tools/tail-run.sh OUT 7 none 240`
with `SETTLE=1.5`. That gives 15-17 frames over 50 ms in 240, all 50-60 ms,
one every 9-15 frames.

### Each layer, ruled out in turn

1. **"Slow frames do more work" was an artefact.** WORKSPLIT showed slow
   frames with 1.6x the vCPU cycles and 1.74x the host instructions, at
   equal IPC, which looked like real work. But the frame boundaries are when
   the RENDER THREAD saw a new frame, and the vCPU is busy all the time, so
   a 52 ms window simply holds 1.6x the cycles. The guest-PC split agreed:
   Halo's clock spin (page 0x000bb000) is 49% of expensive-frame samples
   against 35% of cheap ones.
2. **The guest never misses a frame.** A vblank probe
   (`debug.xemu.clockprobe=N`, nv2a.c) showed `[0x1f8c80]` is a vblank
   counter, +1 per emulated vblank. The spin targets step by exactly 2,
   149 of 149 and 164 of 164: a perfect 30 fps limiter.
3. **NV2A never misses a flip.** Flip completions (`INCREMENT_READ_3D`)
   were 32-35 ms apart, 261 of 261. Every frame finished 20-24 ms after
   the previous flip. Per frame: ~14 ms NV2A CPU, ~7.3 ms fence wait, 954
   draws, 4 surface downloads, 31 surface-to-texture copies. The new
   NV2A frame-history dump (`nvf:` lines at benchmark end) shows all of it.
4. **The render loop was late picking frames up.** With a per-iteration
   probe (`rl:` lines, also on clockprobe), all 12 late frames had a 16-20 ms
   display-sync wait, against a 2 ms median otherwise. Split into phases:

   | | pickup (request until PFIFO starts) | readback | queue idle |
   |---|---|---|---|
   | late frames | **14-21 ms** | 1.1-2.1 ms | ~0.3 ms |
   | normal | 0.01 ms median | 1.3 ms | ~0.4 ms |

   `vkQueueWaitIdle` in the readback is innocent. `pgraph_process_pending()`
   ran only between pusher passes, and a pass covers everything queued --
   here a whole frame, ~14 ms of NV2A work.
5. **And the loop was paced by nothing.** The Android render loop was
   `gl_render_frame(); g_usleep(16000);`, about a 17.6 ms cadence tied to
   neither the 60 Hz display nor the 30 fps guest. Fixing only the pickup
   exposed it: two frames 18 ms apart every ~10 frames. Before that, the
   14 ms stall had accidentally locked the loop to 33 ms, which was smooth
   at a good phase and 52 ms at a bad one, hence the run-to-run luck.

### Fixes

- **Mid-stream sync** (`pfifo.c`): after each method, service a pending
  display sync. The pusher already services pending work at arbitrary
  method boundaries whenever it catches up with dma_put; only the latency
  changes. A/B switch: `debug.xemu.sync_midstream=0`. Pickup median went
  from 12-14 ms to 0.01 ms; max from 17-23 ms to 2.7-3.6 ms.
- **Present per guest frame** (`pgraph.c` + `ui/xemu.c`):
  FLIP_INCREMENT_WRITE posts a semaphore, and the render loop waits on it
  (50 ms timeout) instead of sleeping 16 ms. A/B switch:
  `debug.xemu.present_mode=0`.

### Results

Off-cadence frames (render-loop pickup gap <25 or >42 ms), slot 7, ~6 s
per run, ABCCBA x2 in one process:

| config | frames | off-cadence | >42 ms |
|---|---|---|---|
| A original | 709 | 5.4% (30 of them in the one bad-phase run) | 9 |
| B mid-stream only | 750 | 9.1% (every run) | 0 |
| **C both** | 749 | **1.2%** | **0** |

- **Ground truth for C:** SurfaceFlinger `--latency` on the SurfaceView
  layer gave 123 of 126 presents exactly 2 vsyncs apart, twice. The residue
  is one slip per ~4 s: the emulated vblank (a host timer) and the panel's
  vsync are separate 60 Hz clocks.
- **Cost:** slot 7, 400 frames, ABBA: vCPU 31.16 vs 31.36 ms/frame, within
  the run spread of 30.88-31.50; fps 30.02 both.
- **Pause and resume** still work.

**Open (follow-up):** lock the emulated vblank to the panel's vsync
(Choreographer) to remove the residual slip.

## BA. A8: THE THOR NEVER RUNS ITS PANELS AT 120 Hz (2026-10-05)

A8 assumed the top panel's default 120 Hz mode was active during play and
wasted power and heat on a 30 fps guest. Measured: it is not.

- **The configuration holds 60 Hz.** The user's settings are
  `peak_refresh_rate=60`, `min_refresh_rate=60`. DisplayModeDirector also
  carries a device `PRIORITY_DEFAULT_REFRESH_RATE` vote with max 60
  (`mDefaultRefreshRate: 60`).
- **Nothing moved it off 60 Hz.** `mActiveSfDisplayMode` stayed at 60 Hz on
  both panels in every case:
  - peak 120 / min 60;
  - peak 120 / min 120;
  - the game window requesting the 120 Hz mode (`debug.xemu.panel60=120`);
  - continuous touch on the top panel.
- **The game's frame rate would pick 60 Hz anyway.** Since AZ, Boxxy
  presents only per guest frame, at 30 fps.

So there was no power or thermal comparison to make. Kept anyway, because
it is a standard API and harmless: game windows (top and lower screen)
request their display's 60 Hz mode through `preferredDisplayModeId`, which
would hold 60 Hz on firmware or devices where 120 Hz is reachable.
`DisplayRate.kt`; `debug.xemu.panel60` = 0 (no request) / 1 (60, default)
/ 120 (for measuring). C8's list of heat levers loses A8 on this device.

## BB. C4: THE x87 ROUNDING MODE IS HONOURED, AT NO MEASURABLE COST (2026-10-05)

Section AK found Halo and THPS2x selecting directed x87 rounding millions of
times a run, all discarded by the native FPU path. Implemented, per game.

**Why FPCR alone was not enough.** The AArch64 backend converted float->int
with FCVTNS, which always rounds to nearest whatever FPCR says, and those
conversions (FISTP after FLDCW) are exactly the floor/cast idioms these
games use.

**Change** (`g_xemu_x87_rc`, tcg.c; set at launch from the per-game setting
"Accurate x87 rounding", which defaults to on for Halo t4d530004 and THPS2x
t41560001 via a built-in title default):

- **Conversions** (`tcg-target.c.inc`): FRINTI (round to integral by
  FPCR.RMode) into the reserved V31, then FCVTZS, which is exact on an
  integral value. Under the default mode this gives FCVTNS's result.
- **FPCR sync** (`update_fp_status`, fpu_helper.c): copies RC into
  FPCR.RMode when the guest writes the control word, remapped (x86
  0/1/2/3 -> ARM 0/2/1/3) and written only on change. This costs nothing
  per TB; the `flcr` op stays unused.

**Verification:**
- **Encodings:** all six new instructions match clang's assembler byte
  for byte.
- **Semantics, on the Thor:** a native test of the exact sequence under
  each x86 mode gave nearest-even (2.5->2, -2.5->-2), floor (2.7->2,
  -2.3->-3), ceiling and truncate correctly. Under the old path Halo's
  floor(2.7) was 3.
- **Engagement:** FPCR syncs are 2,850,362 = 2 x 1,425,181 guest switches
  to round-down (each set plus each restore); 0 with the setting off.

**Cost:** Halo slot 7, 400 frames, launches ABBA x2:

| | vCPU ms/frame | fps | >50 ms frames |
|---|---|---|---|
| on | 31.18, 30.90, 31.00, 30.85 (mean 30.98) | ~30.05 | 0 |
| off | 30.98, 30.95, 31.00, 30.85 (mean 30.95) | ~30.05 | 0 |

The 0.03 ms difference is inside the spread, against AK's 0.3-0.5%
estimate: the sync runs only when the mode changes, and the conversion gains
one instruction.

**Not measured:** visible change. Neither game showed an obvious error
before, and directed and nearest rounding agree on integral values. The
remaining hazard, by design: a directed mode set by the guest is also seen
by C code on the vCPU thread until the guest restores it, which these
games do within a few instructions.

## BC. WIDESCREEN: 4:3 GAMES WERE STRETCHED; AUTO NOW FOLLOWS THE GAME (2026-10-05)

Not a performance result; recorded because the method and the open question
matter.

**Before.** The emulated EEPROM's VideoFlags (offset 0x94) were 0, so the
console told every game "4:3 TV". Boxxy's default aspect was "16:9 stretch",
and "Auto" was also a plain stretch. Every game was shown 1.33x too wide:
Halo's logo measured 782 px from centre instead of 587.

**How the console reports aspect.** The kernel writes PM GPIO 0x16 (5 =
widescreen) on every video mode set (`acpi_xbox.c` ->
`xemu_get_widescreen()`). Logged with guest PC: the kernel's boot mode set
writes the console setting, then each game's own mode set
(`pc 0x80030760`, a game address on the stack) writes what the GAME chose.
So Auto now follows the running game, as desktop xemu's Auto does, and is
the default.

**Verified.** With the console set to widescreen, all five games tested
(Halo, Halo 2, MGS2, JSRF, THPS2x) wrote 5 at boot and then 4 at their own
mode set. Auto showed each 4:3, and the pixels confirm it: the Halo logo
measured 587 px in all three runs, and the Halo 2 logo width was identical
with widescreen off and on (1032 vs 1031 px).

**The widescreen setting.** Global and per game. It writes EEPROM VideoFlags
bit 0x00010000 at each launch, in `qemu_init` before the machine reads it,
with the user-section checksum recomputed. The algorithm reproduces the
stored checksum. It works at kernel level: the boot GPIO write follows it.

**Correction (same day): the aspect GPIO does not say what a game renders,
so Auto now follows Boxxy's Widescreen setting instead.** Prompted by the
user's point that on component or composite a TV never auto-switches -- you
set it to 16:9 by hand -- Halo 2 was checked in gameplay. Cairo Station
spawn, identical view, widescreen on vs off, both shown 4:3. The horizontal
scale that best maps one onto the other is **1.32** (correlation 0.55,
against 0.21 at 1.00): Halo 2 renders true anamorphic 16:9, while its own
mode set writes GPIO 4 ("4:3").

Its title logo and HUD are NOT squeezed: the motion tracker is round in the
4:3 view and so wide on a 16:9 screen, as on hardware. That is why the
earlier logo-width test (1032 vs 1031 px) wrongly said "4:3". Auto trusting
the GPIO would have shown Halo 2 squeezed.

Auto = the game's effective Widescreen setting (on -> 16:9 fill, off -> 4:3
pillarbox), like a TV set by hand; the GPIO is logged beside it for
diagnosis only. With Widescreen on, Halo 2 shows full screen with correct
geometry.

Instrumentation kept: `xbox gpio:` lines with guest PC and the `aspect Auto:`
decision.

## BD. VBLANK LOCK + PRESENT ON FLIP: ZERO JUDDER (2026-10-06)

Section AZ left one slip per ~4 s. Its cause, measured: **the Thor's
"60 Hz" panel refreshes at 59.753 Hz**. SurfaceFlinger's actual present
times give a median 16.7356 ms, and a Choreographer-tracked vsync period
agrees. The emulated vblank free-ran at exactly 60.000 Hz, and two clocks
0.247 Hz apart slip by one refresh every 4.05 s, which is the AZ residue.

**A second finding on the way: Boxxy showed every frame one frame late.**
Since AZ it presented at FLIP_INCREMENT_WRITE, when the NV2A finishes a
frame. But it displays the surface at PCRTC_START, and a game flipping in
its vblank interrupt changes that only afterwards, ~13 ms later in Halo.
Each present therefore showed the previous frame, at the GPU's jittery
completion time.

**Changes:**
- **Present on flip** (`pcrtc.c`, `ui/xemu.c`): a PCRTC_START change bumps
  `g_nv2a_flips` and wakes the render thread. While flips are happening, a
  flip is what makes a frame new; frame_time is the fallback for code that
  never flips. A/B: `debug.xemu.present_on_flip`.
- **No duplicate presents** (`ui/xemu.c`): a wake with nothing new skips
  the blit and swap. Waking on both completion and flip had doubled the
  presents, to every vsync.
- **Vblank lock** (`xemu_android.c` Choreographer thread; `ui/xemu.c`
  vblank thread): each emulated vblank lands on the panel's vsync grid plus
  a phase (`debug.xemu.vblank_phase_us`, default 1000). The thread sleeps
  with clock_nanosleep(ABSTIME) on CLOCK_MONOTONIC, waking 13-14 us late on
  average against g_usleep's milliseconds. A/B: `debug.xemu.vblank_lock`.
  Since the follow-up below, it also locks to every n-th vsync on 120 Hz-class
  panels.

**Result**, Halo slot 7, SurfaceFlinger present intervals, ~4.4 s windows,
ABCCBA x2 in one process:

| config | intervals | off-cadence |
|---|---|---|
| A: present at completion, free-running vblank (AZ) | 503 | 2.98% |
| B: present on flip, free-running vblank | 501 | 2.40% |
| **C: present on flip + vblank lock** | 502 | **0 (all exactly 2 vsyncs)** |

**Trade-off:** the guest now runs at the panel's 59.753 Hz, 0.3% below a
real NTSC Xbox's 59.94 Hz; fps reads ~29.92. vCPU ms/frame ABBA:
on 31.17, off 30.96. The gap comes from the first (cooler) off pair; later
pairs are equal, and a 0.4% longer frame adds ~0.14 ms of Halo's
clock-spin time anyway.

**Follow-up (same day): refresh-rate changes and 120 Hz.** The maths moved to
`android/app/src/main/cpp/vblank_lock.h`, pure so it can be tested
off-device (`android/tools/vblank-lock-test.c`), because the Thor cannot run
at 120 Hz. Two changes:

- **The period tracker follows a new refresh rate.** It previously ignored
  any interval more than 25% off its estimate (meant for skipped
  callbacks), so it could never follow a refresh-rate change. With the old
  rule the test shows **120 -> 60 Hz stepping two 60 Hz vsyncs per
  emulated vblank: the game at half speed**. Now 8 matching intervals in a
  row are accepted as the new rate.
- **The lock steps n vsyncs** wherever n x period is within 1 ms of
  16.67 ms: every 2nd vsync at 120 Hz, none at 50 or 90 Hz.

Test: 9/9 scenarios pass (60, 59.753, 59.94 and 120 Hz; 60 Hz with every
7th callback lost; 120->60 and 60->120 at 3 s; 90 and 50 Hz staying
unlocked). Every locked deadline lands within 0.2 ms of a real vsync plus
the phase, at the right step. With rate changes disabled, the two switch
cases fail, so the test can fail. On the Thor: locked 300/300 every 1 vsync;
377/377 present intervals exactly 2 vsyncs.

## BE. SHADER COMPILES MADE VISIBLE; THE OVERLAY'S COUNT WAS DEAD ON VULKAN (2026-10-09)

The user sees occasional frame-rate drops in Halo and Halo 2 and could not
tell whether they were shader compiles. Two findings:

- **The overlay's "Shaders" count was a broken probe on Vulkan,** the default
  renderer. It read `g_shaders_compiled_count`, which only the OpenGL
  renderer increments, so on Vulkan it showed 0 whatever happened.
- **Fix:** one counter and timestamp, `pgraph_note_shader_compile()` in
  `pgraph.c`, now records real compiles only:
  - OpenGL program compiles;
  - Vulkan GLSL->SPIR-V compiles, i.e. SPIR-V disk-cache misses;
  - Vulkan pipelines taking 2 ms or more. Under 2 ms means the pipeline
    cache served it; the threshold is `PIPELINE_COMPILED_THRESHOLD_US`.
- **On screen:** a "Compiling shaders…" label at the top left, below the perf
  overlay when that shares the corner. It shows for 1 s after any compile
  (polled at 10 Hz).

**Verified:** Halo from cold, with the Vulkan caches moved aside, showed the
label during the boot bursts: 45 SPIR-V compiles and 29 pipelines (547 ms).
From warm it stayed hidden: 0 compiles, 45 disk hits, 29 pipelines in 0 ms.
The user's caches were restored afterwards (706 SPIR-V files, 14.5 MB
pipeline cache).

**Next:** a drop that happens without the label is not a compile. That
splits the Halo/Halo 2 reports into compile stutter (fix: cache coverage or
async compile) and real load (the C items).

## BF. PIPELINE CHURN WAS THE BLEND CONSTANT; CACHE SAVES OFF THE RENDER THREAD (2026-10-10)

**The user confirmed** that Halo's fps drops coincide with the "Compiling
shaders" label (section BE). Their session log showed three costs:

1. Real compiles of first-seen shaders: SPIR-V ~18 ms each, driver pipelines
   ~20 ms each. Any one of them overruns a frame, since NV2A already uses
   ~14 ms of the 33.
2. **2,378 pipeline creations in 6 s.** These were cache hits, but cost
   255 ms of work in total.
3. The pipeline-cache snapshot (`vkGetPipelineCacheData`, 14 MB) taken on
   the pfifo thread: 6-12 ms after each compile burst.

**The churn, measured.** A diagnostic, `debug.xemu.pipe_diff=1` in
`draw.c`, finds for each new pipeline an existing one differing in exactly
one key component. On slot 7 + `walk_fps_dip`:
- 3,584 misses in total;
- **BLENDCOLOR 2,998**, shader 308, SETUPRASTER 105, CONTROL_0 81, other 92.

The blend constant was baked into every draw pipeline, and Halo animates it
per frame for the health-pack effect, so each new value needed a whole
pipeline.

**Fix:**
- `VK_DYNAMIC_STATE_BLEND_CONSTANTS` on draw pipelines;
- BLENDCOLOR removed from the key and from the dirty check;
- `vkCmdSetBlendConstants` in `begin_draw` before every draw.

The value is the same register read at the same point, so the output is
unchanged by construction. All draw paths go through `begin_draw`, and the
clear pipeline already set its own constants.

**Result:** slot 7 plus the replay, run twice, created **0 new pipelines**
(previously ~3,450 per run). Warm-cache pipeline creation stayed in cache-hit
territory: 95 pipelines in 3 ms.

**Cache save:** the snapshot now runs on the writer thread with the file
write. Pipeline caches are internally synchronised. One save runs at a time,
a request made during a save is retried, and finalize waits for any save in
flight. Verified with a cold boot: saves landed and nothing crashed.

**Not done:** the remaining churn sources (SETUPRASTER, CONTROL_0) are each
under 3% of misses. The real fix for first-seen compiles is asynchronous
compilation (next).

## BG. BACKGROUND SHADER COMPILATION: NO FRAME OVER 50 ms FROM A COLD CACHE (2026-10-10)

The fix for the compile stutter in section BF: setting "Compile shaders in
background", on by default, also overridable per game.

**How it works:**
- **SPIR-V:** a shader missing from the disk cache is compiled on a worker
  (`pgraph_vk_create_shader_module_async`). Disk hits stay synchronous.
- **Pipelines:** first requested with
  `VK_PIPELINE_CREATE_FAIL_ON_PIPELINE_COMPILE_REQUIRED_BIT_EXT`
  (`VK_EXT_pipeline_creation_cache_control`, which Turnip 26.2 has). A
  driver-cache hit is created and drawn at once; only
  `VK_PIPELINE_COMPILE_REQUIRED` goes to a worker. Without the extension,
  pipelines stay synchronous.
- **Skipping:** a draw whose shaders or pipeline are pending is skipped:
  `begin_pre_draw` returns false, and the four draw paths return.
  Bookkeeping (draw_time, surface dirty) proceeds as if drawn.
- **Threading:** two workers (`GThreadPool`). Workers touch only their job.
  The pfifo thread installs results (`pgraph_vk_drain_compile_jobs`, at
  `create_pipeline` and the per-frame tick) or discards them if the
  pipeline entry was evicted (its `pending_job` no longer matches). Jobs
  hold module refs; finalize waits for all jobs.

**Measured:** Halo, caches moved aside (cold), boot + slot 7, 240-frame
benchmark, ABBA across processes. Cold needs a fresh process; the effect is
far larger than drift.

| async | worst frame | frames > 50 ms | skipped draws (whole session) |
|---|---|---|---|
| on | 35 ms | 0 | 11,392 |
| off | 50 ms | 1 | 0 |
| off | 50 ms | 1 | 0 |
| on | 35 ms | 0 | 10,949 |

- **Engagement checked:** the setting's log line was present, cache control
  was on, and the skip counter was non-zero only with async on.
- **Warm cache, async on:** 13 skipped draws, worst 35 ms, 0 over 50 ms.
- **The settled image matches async off** (screenshot comparison, slot 7).

**Harness mistake caught:** the first ABBA batch ran on the previously
installed APK, because the new build was never installed. The cache-stats
line lacked the new "async" field and the setting's log line was absent,
which is what exposed it (rule 1). That batch also showed two ~1.2 s single
frames in 4 runs on the old build. Not seen in 8 runs since; unexplained.

**Trade-off:** a newly seen object or effect can be missing for a frame or
two, once. Compile throughput is unchanged (~2.1 s of CPU cold for Halo's
boot), just off the render thread.

## BH. PLAY TEST: COMPILES ABSORBED; THE REMAINING DROPS ARE x86 CPU LOAD (2026-10-10)

**Live play, Halo, background compilation on.**
- New-area shaders compiled at 19:00:02-19:00:22: 4 SPIR-V and ~10 pipelines,
  with 40 draws skipped. **There were zero slow frames in that minute.**
- The drop the user reported was sustained, not a hitch:
  - 19:05:19-49 and 19:06:44-07:01, with 50-67 ms frames back to back
    (~17-20 fps);
  - 137 slow frames in one minute, against 1-11 otherwise;
  - for those frames: shaders +0, TLB flushes +0, low notdirty, vblank
    still locked.

**A heavy-scene save state at last.** The user saved that spot to **slot 1**.
Benchmark, 300 frames, no input, three runs back to back:

| run | frames > 50 ms | worst | vCPU ms/frame (budget 33.33) |
|---|---|---|---|
| 1 | 25 | 66 ms | 32.27 |
| 2 | 28 | 67 ms | 32.47 |
| 3 | 29 | 68 ms | 32.53 |

- **vCPU-bound:** util 92%, headroom 2-3%.
- **Slow frames are heavier frames:** 1.60× cycles and 1.48× host work at
  similar IPC (3.16 vs 2.92). MMIO rises ×3.35 in them.
- **Stalls:** frontend 19.6%, backend 20.9%; L1I misses 4.0 M/frame.
- **Heat:** CPU at 92.3 °C. The effective vCPU clock was **2.74 GHz**,
  against 2.93 GHz in the section U calibration on a cooler device, so heat
  costs ~6-7%. It does not explain the drops alone: heavy frames need ~134
  Mcyc, against a 33 ms budget of ~98 Mcyc even at 2.93 GHz.
- Android's thermal status was 0 and policy max clocks were uncapped right
  after the run.
- Live play was heavier than the no-input benchmark: ~17-20 fps against
  28.3 fps mean.

**This is the first state that is CPU-bound for real.** Slot 7's dip was
display pacing (section AZ). Slot 1 is the right benchmark for the C items
(C3 LDAPR follow-ups, C6/C7 compiler, C8 heat). Its spread is ±2 frames over
50 ms across three back-to-back runs, which is warming the device.

---

## BI. C3: LDAPR IS FREE; THE ADDRESS ADD WAS 2 CYCLES, NOW 1 (2026-10-10)

**Priced natively first** (`scratchpad/ldprobe`, cpu7, PMU cycles, reproduced
twice to within 0.01 cyc):

| per load, cycles | chain, L1 | independent, L1 | chain, DRAM |
|---|---|---|---|
| `LDR [b, w, uxtw]` | 4.1 | 1.06 | ~395 |
| `ADD uxtw` + `LDR [x]` | 6.1 | 1.07 | ~396 |
| `ADD uxtw` + `LDAPR` | 6.1 | 1.06 | ~399 |
| `DMB ISHLD` + `LDR` | 7.3 | **7.7** | ~395 |

- **LDAPR costs exactly what LDR costs**, in every case, MLP included.
  The ordering is free on this core, so a stack-load exemption has
  nothing to recover except the ADD. Added to the dead ends.
- The whole remaining cost is the composing ADD. It costs **2 cycles,
  because the extended-register (`uxtw`) form is a 2-cycle op**, and it
  sits on the addend → load chain. A plain ADD is 1 cycle, and an extra
  `ADD #0` on the chain adds exactly 1 (it is not eliminated).
- Throughput-wise, DMB ISHLD costs ~6.6 cycles per load even with nothing
  outstanding. That is the mechanism behind section AL's tail win.

**What a cycle on that chain is worth in the emulator.** The new knob
`debug.xemu.ldapr_lat=N` puts N `ADD Xb, Xb, #0` on the addend. Slot 1 was
measured with `BENCHMARK --ei slot 1` immediately after load. **A settle first
misses the heavy stretch:** with 12 s of settle, the same slot gave 0-5
frames over 50 ms. Interleaved in one process:

| | +8 cycles (0 8 8 0) | +2 cycles (0 2 2 0 0 2 2 0) |
|---|---|---|
| Δ vCPU ms/frame | +2.23 | +0.50 |
| frames > 50 ms | 35 → 51 | 33.8 → 41.3 |

**~0.26 ms per cycle per ordered load**, consistent between the two. That is
~22% of the 3.3 M loads' latency landing on the critical path. The old ADD's
2 cycles were therefore worth ~0.5 ms/frame.

**Shipped:** `MOV Wtmp0, Waddr` + plain `ADD TMP2, TMP1, TMP0`.
- The MOV depends only on the guest address, so it runs in the TLB load's
  shadow. Probe: 6 → 5 cycles.
- Demotion is unchanged: the patcher already decodes the LSL form, and
  rewrites it to `LDR [TMP1, TMP0]`, where TMP0 still holds the
  zero-extended address.
- `debug.xemu.ldapr_uxtw=1` restores the old form for A/B.
- Engagement: code is ~1% larger (+0.23-0.29 MB, 4 B per site), and
  demotions keep working (0 foreign faults across 20 runs).

| block (slot 1, 300 frames) | old vCPU / >50 | new vCPU / >50 | Δ vCPU |
|---|---|---|---|
| 1 0 0 1 (hot run, part 1) | 33.87 / 38 | 32.79 / 17.5 | -1.08 |
| 1 0 0 1 (part 2) | 33.92 / 33.5 | 33.65 / 31 | -0.27 |
| 1 0 0 1 (part 3) | 34.22 / 38.5 | 34.12 / 38.5 | -0.10 |
| 0 1 1 0 x2, after cooldown to 47.5 °C | 33.44 / 29.3 | 33.26 / 29.0 | -0.18 |
| **all 20 runs** | **33.77 / 33.7** | **33.41 / 29.0** | **-0.36 (-1.1%)** |

- **vCPU:** better in all four blocks, with the size the slope predicted.
- **Tail:** not established. Only the first block moved it.
- **Harness note:** runs alternate fast and slow (and large and small code)
  by position in the process, whatever the config. Keep the orders
  parity-balanced (ABBA does this).

**C3 is closed.** The ordering costs nothing, and half the ADD is gone. The
last cycle could only be removed per load, with a correctness argument, for a
gain of ≤ ~0.1 ms.


---

## BJ. C8 SOAK: A 5% CLOCK LOSS TRIPLES THE TAIL (2026-10-11)

78 back-to-back `BENCHMARK --ei slot 1 --ei frames 300` runs in one process,
20 minutes from a cold device (31 °C idle). Smart fan, High Performance,
Turnip R3, on battery (36 -> 24%). Script: `scratchpad/soak.sh`.

| runs | min | frames > 50 ms | vCPU ms | eff. GHz | die °C | fan |
|---|---|---|---|---|---|---|
| 1-10 | 0 | **7.5** | 31.52 | **2.940** | 78 | 42% |
| 11-20 | 3 | 6.9 | 31.57 | 2.932 | 87 | 52% |
| 21-30 | 5 | 11.0 | 31.92 | 2.881 | 90 | 56% |
| 31-40 | 8 | 17.0 | 32.31 | 2.839 | 91 | 58% |
| 41-50 | 11 | 20.6 | 32.42 | 2.818 | 91 | 58% |
| 51-60 | 13 | 19.3 | 32.51 | 2.798 | 92 | 58% |
| 61-70 | 16 | 22.9 | 32.71 | 2.792 | 92 | 58% |
| 71-78 | 18 | **24.4** | 32.86 | **2.784** | 92 | 58% |

Grouped by clock instead, the relation is monotonic:
- 2.94 GHz: ~7 frames;
- 2.84 GHz: ~17 frames;
- 2.78 GHz: ~24 frames.

**The tail is extremely clock-sensitive.** -5.3% clock gives +3.3x
frames over 50 ms, because slot 1's heavy frames sit just past the
budget. vCPU time moved only +4%.
- BH's 25-29 (taken on a hot device) was mostly heat.
- A cold device gives ~7 on the same state.

**Every tail A/B must be read against this.** A 1% clock drift is worth
~3 frames here.

**The throttle is slow and is not the die sensor.**
- The die plateaus at 91-92 °C from minute 8, but the clock keeps sliding
  for another 10 minutes.
- cpufreq has reported max throughout in earlier sections (AO, T), so the
  limit is hidden from the OS view.
- The likely limiters are a slower board or skin sensor (FanBase's input
  reached 75-76 °C) or the SoC's hardware limits management.

**The Smart fan curve stops at 58%,** and holds there while the clock
keeps falling. AYN's other modes are Sport and Customize
(`settings system fan_mode`: 4 = Smart). Performance mode is already High
Performance (`performance_mode=2`), the top setting.

**Side result: fast charging costs more than the Sport fan gains.**
Sport fan, **plugged in**, charging from 25% at up to 3 A (25 → 45% in
~20 min). Stopped at run 21 because it is confounded:

| run | 1 | 5 | 10 | 15 | 21 |
|---|---|---|---|---|---|
| eff. GHz | 2.93 | 2.89 | 2.80 | 2.75 | **2.71** |
| frames > 50 ms | 25 | 22 | 32 | 33 | **38** |

- At 5 minutes it was already below anything the Smart fan reached on
  battery in 20 (2.78 GHz, 24 frames).
- Fast charging is a heat source large enough to swamp the fan setting.
- Fan comparisons must be run on battery. Advise players that a
  fast-charging Thor will run heavy scenes noticeably worse.
- Sport mode is a fixed speed, so `FanBase` logs nothing in it.

**CORRECTION (same day): Sport is not a stronger fan.** Reading the PWM
node shows Sport is a **fixed 50%**: `/sys/class/gpio5_pwm2/duty` =
25000 with `period` = 50000. Smart ramps to 58% (smartSpeed 29000) under
this load. So:
- Sport ran *more* air than Smart only in the first ~5 minutes, and
  *less* at steady state.
- The +1.2% below therefore cannot be the fan. It is session-to-session
  variation (start temperature, ambient, battery), and is this harness's
  cross-session noise floor.
- **The fan has not yet been tested at full speed.** Customize allows a
  curve up to 100% (duty 50000).

The node is world-writable (`-rw-rw-rw-`) and readable, which makes fan
duty loggable per run.

**Sport fan, on battery (kept for the record; see the correction above).** Same 20-minute soak, from 36 °C
(Smart's run started at 31 °C):

| runs | Smart: >50 / GHz / die | Sport: >50 / GHz / die |
|---|---|---|
| 1-10 | 7.5 / 2.940 / 78 | 9.7 / 2.940 / 84 |
| 11-20 | 6.9 / 2.932 / 87 | 13.1 / 2.895 / 90 |
| 21-30 | 11.0 / 2.881 / 90 | 16.4 / 2.854 / 91 |
| 31-40 | 17.0 / 2.839 / 91 | 18.1 / 2.840 / 92 |
| 41-50 | 20.6 / 2.818 / 91 | 18.3 / 2.836 / 92 |
| 51-60 | 19.3 / 2.798 / 92 | 20.2 / 2.832 / 92 |
| 61-70 | 22.9 / 2.792 / 92 | 18.0 / 2.830 / 92 |
| 71-78 | 24.4 / 2.784 / 92 | 20.8 / 2.829 / 91 |
| **runs 41-78** | **21.7 / 2.799** | **19.2 / 2.832** |

- **Steady state:** +1.2% clock, ~12% fewer frames over 50 ms.
- **Early on, Sport was worse**, because it started 5 °C warmer.
- These are separate sessions, so treat the difference as indicative only.

**Superseded in part by the correction above.** The die *is* regulated at
~92 °C, but whether a 100% fan lifts the equilibrium is untested.
**Why so small: the die is regulated, not cooled.** Under either fan the die
sits at 91-92 °C, and the clock is whatever holds it there. A stronger fan
shifts that equilibrium slightly; it cannot lift it. **The device-side
levers are now used up:**
- Performance mode is at its top setting.
- Sport fan gives +1%.
- Turnip R3 is already in use.
- Render scale is already 1×.
- A8 is moot (section BA), and the lower panel buys nothing (section AX).

**What is left is our own power.** At a regulated die temperature, every
watt the emulator does not burn becomes clock. That means the vCPU's
instruction count (C6/C7), the NV2A thread (~38% of process cycles, on the
2.8 GHz cluster), and idle spin in light scenes (section Q's 10-15 °C).
Section Q's Halo range does not apply to slot 1, which has 2-3% headroom.

**Battery-gauge note:** level fell 36 → 24% in the Smart run, but only
79 → 76% in the Sport run, under identical load. That reading is not
trustworthy as an energy measure.

**100% FAN: THE TAIL STAYS AT ITS COLD LEVEL.** Customize mode
(`fan_mode=6`, `fan_speed=100`), with PWM duty logged per run (50000/50000
throughout). On battery, from 35.5 °C, same 20-minute soak:

| runs | >50 ms | GHz | die °C |
|---|---|---|---|
| 1-10 | 8.4 | 2.943 | 80 |
| 21-30 | 7.4 | 2.937 | 88 |
| 41-50 | 8.9 | 2.918 | 90 |
| 71-78 | 9.2 | 2.911 | 90 |

| steady state, runs 41-78 | Smart (≤58%) | Sport (50%) | **100%** |
|---|---|---|---|
| frames > 50 ms | 21.7 | 19.2 | **8.8 (-59%)** |
| frames > 66 ms | 0.61 | 0.47 | **0.05** |
| eff. GHz | 2.799 | 2.832 | **2.914 (+4.1%)** |
| die °C | 91.6 | 91.7 | 90.0 |

- This is cross-session, but the effect is ~5x the Smart/Sport difference.
  It holds in every bucket after minute 5, and it matches the clock →
  tail curve from the Smart run (2.91 GHz ≈ 9 frames there too).
- **The limiter is not the die sensor.** The die ran only 1.6 °C cooler,
  yet the clock was 4% higher. The throttle follows a slower board/skin
  sensor, and that is what the fan cools. This corrects "the die is
  regulated, the fan cannot lift it" above.
- **Sustained heavy play on a Thor wants a 100% fan.** That holds slot 1 at
  cold-device performance indefinitely, against ~2.5x the slow frames on
  Smart after 10 minutes.

**Can the app do this itself?** It is not attempted yet.
- The duty node `/sys/class/gpio5_pwm2/duty` is world-writable, but AYN's
  `FanBase` rewrites it every ~1-2 s in Smart mode, and SELinux may deny
  an app.
- `fan_mode` / `fan_speed` are non-public `Settings.System` keys, which
  apps targeting API 23+ cannot write.
- The practical route is to tell the player: suggest Fan → Customize →
  max for heavy games.

---

## BK. THE PFIFO THREAD RE-HASHES CLEAN TEXTURES; FIXING IT EXPOSES LOCK WAITS (2026-10-11)

**Where the process's CPU goes on slot 1** (simpleperf, 12 s, heavy part;
41,351 samples ≈ 1.72 cores):
- vCPU: generated code 38%, our C code 15%. Helpers are therefore **28%
  of vCPU time**, not the 14-18% in C6's note.
- NV2A pfifo thread: ~29% (46% of a core).
- APU: ~9%.
- Our compiled C code is ~41% of the process, all of it already `-O3
  -march=armv8.2-a -mtune=cortex-x3`. XXH3 is NEON-vectorized already.

**The pfifo thread's biggest symbol is XXH3, at 30.6%.** It comes from
texture validation in `pgraph_vk_bind_textures`. New per-10 s counters
(`vk-tex:` log line) give the cause:

| | first slot-1 load after launch | after a second load |
|---|---|---|
| texture binds | ~540,000 | ~540,000 |
| content hashes | 300 | **517,000** |
| … re-hashing an already-hashed range, no write since | 0 | **517,000** |
| … contents unchanged | 0 | **517,000** |
| bytes hashed | 24 MB | **23 GB (2.3 GB/s)** |
| real uploads | ~300 | ~300 |
| single-page dirty tests | 6.4 M | 6.4 M |

**Bug:**
- On a cache hit, nothing clears `possibly_dirty` or the dirty range after
  hashing. Upstream xemu has the same (only the cache-miss path resets it).
- Once a write touches a cached texture, every later bind re-hashes it.
  A state load rewriting VRAM triggers this, and so would streaming.
- Section 4296658096's per-page scheme made each hash cover only the
  range, but the range is never consumed.
- The first-load measurement looks clean because new textures start clean.

**Fix, measured in one process** (ABBA ×2, slot 1, 600 frames;
`debug.xemu.tex_reset`):

| | pfifo CPU | hashed / 10 s | uploads | vCPU ms | frames > 50 ms |
|---|---|---|---|---|---|
| old | 48-51% | 23 GB | ~299 | 31.45 | 8, 12, 8, 13 |
| reset | **31-32%** | 24 MB | ~294 | 31.36 | **40, 49, 36, 36** |

- **Power:** pfifo -36%.
- **Correctness:** uploads are unchanged, so no update was lost.
- **But the tail got ~4x worse, in every pair**, reproduced again at
  18/21 vs 36/38. The vCPU did the same work, even slightly cheaper
  (89 vs 92 Mcyc in normal frames, less memory contention). Yet
  wall/frame rose 33.7 → 34.8 ms and util fell 94 → 91%, with slow
  frames every 2-3 frames (`SLOWGAPS 1 2 2 2 …`).

**Not DVFS:** pfifo stays on cpu3-6 and that cluster sits at 2803 MHz in
both arms (sampled at 5 Hz).

**It is lock waiting.** An off-CPU profile of the vCPU (`--trace-offcpu`,
8 s per arm):
- The vCPU is **blocked in `pthread_mutex_lock` for 25.6% (old) and 28.8%
  (reset) of its time.** The difference is ~1.1 ms/frame, matching the
  extra wall time.
- Waits by site:
  - ~62% from `cpu_exec_loop` / `mttcg_cpu_thread_fn` (BQL);
  - 18% `pgraph_read` (pfifo.lock + pgraph.lock);
  - 9% `user_read` (pfifo.lock);
  - 2% `pfifo_read`.
- The guest polls NV2A registers, and each read queues behind locks the
  pfifo thread holds.

**Disposition:**
- The reset is kept but off: `debug.xemu.tex_reset=1`.
- Counters stay on (one log line per 10 s).
- **The lead now is the vCPU's ~9 ms/frame blocked on NV2A locks.** Why it
  grows when pfifo does less work is unexplained; lock handoff (an unfair
  mutex re-acquired by a busy thread) is the first hypothesis to test.
- C6/C7 are deprioritized: the hot C code is already optimized, and the
  contention dwarfs anything flags could buy.

---

## BL. THE vCPU WAITED ON NV2A LOCKS INSIDE ITS MMIO; LOCK-FREE STATUS PATHS (2026-10-11)

**Hypotheses tested and rejected first:**
- **DVFS/migration of pfifo.** Sampled at 5 Hz, it stays on cpu3-6 at
  2803 MHz in both arms.
- **The `user_read` DMA_GET poll.** Only ~550 polls/s, and a lock-free
  read changed nothing: with the texture reset on, 27/36 lock-free vs
  37/23 locked.

**Instrumentation that found it.**
- `debug.xemu.bql_prof=1`, in `system/cpus.c`, gives per-site BQL hold and
  wait times. **The BQL holder was the vCPU itself, inside its own MMIO**
  (`cputlb.c` loads 2098 and stores 2641, which run under the BQL): store
  holds rose 148 → 521 ms per 10 s with the texture reset, with single
  holds up to 5.9 ms.
- Per-handler timed locks (`nv2a-lock:` lines) showed what those holds
  were waiting for. Per 10 s, slot 1:
  - `pgraph_rd` (pg->lock): ~450-510 ms in **both** arms, max 5.9 ms.
    Mostly **`PGRAPH_INCREMENT` (0x71c)**, ~25×/s; also `INTR` (0x100).
  - `user_wr`, the DMA_PUT kick (pfifo.lock): 0.3 → 99-444 ms with the
    reset.
  - `pfifo_rd` (pfifo.lock): 1-36 → 98-158 ms with the reset. Mostly
    `PFIFO 0x0080`, a plain register.
- After making the reads lock-free, the wait moved to `pgraph_wr` of
  **INCREMENT**: ~380 ms per 10 s, one write per frame at ~1.5 ms each.
  That is the guest's vblank handler doing a read-modify-write of the flip
  counter.

**Mechanism.**
- The pfifo thread holds pfifo.lock for nearly all its pushbuffer parsing,
  and pg->lock for whole draws (up to ~6 ms).
- The guest's status polls, the DMA_PUT kick and the per-frame flip
  increment queue behind those locks while holding the BQL.
- Less pfifo work (the texture reset) means pfifo.lock is held a larger
  share of the time. That is why BK's fix made the tail worse.

**Fix (`g_nv2a_lockfree`, on; `debug.xemu.nv2a_lockfree=0` restores):**
- **Reads without the lock:** PGRAPH `INTR`, `INTR_EN`, `INCREMENT`;
  PFIFO `INTR_0`, `INTR_EN_0`, `RUNOUT_STATUS`, `0x0080`; and USER
  `DMA_PUT/GET/REF` (acquire loads). The pusher publishes `DMA_GET` with a
  release store.
- **DMA_PUT kick:** a release store, then `pfifo_kick_lockless()`. That is
  a two-flag handshake (`fifo_kick` / `sleeping`, a full barrier on each
  side), and it takes the lock only to wake a sleeping thread. The thread
  clears `fifo_kick` with a barrier before reading PUT again.
- **INCREMENT write:** READ_3D advances by CAS, then a lockless kick. Every
  writer of `NV_PGRAPH_SURFACE` (the flip methods) now uses the same
  atomic update.
- Engagement: `pgraph_rd`, `pfifo_rd` and `user_wr` waits drop to 0. The
  INCREMENT write wait is gone.

**Result, slot 1, one process, ABBA ×2, 600 frames:**

| | before | lockfree + texture reset (BK) |
|---|---|---|
| frames > 50 ms | 22, 21, 25, 26 (23.5) | **2, 3, 6, 5 (4.0), -83%** |
| fps | 29.31 | **29.81** |
| pfifo CPU | 48-50% | **35%** |
| vCPU busy | 93% | 99% |
| vCPU Mcyc/frame | 91.7 | 94.6 (+3%) |
| MMIO reads/frame | ~238 | ~232 |

Lock-free without the texture reset is ~neutral (10, 15 vs 5, 16). The two
belong together: lock-free removes the waits the reset exposed.

The vCPU's extra 3% is guest code, not register spinning (the MMIO counts
are unchanged). That time used to be spent asleep in futex; now the guest
runs ahead and idles in its own loop. It is net power on the X3 against
-14 points of a core on pfifo, which is why a soak follows.

**Both are now default on.** `debug.xemu.tex_reset=0` and
`debug.xemu.nv2a_lockfree=0` restore the old behaviour.

**Soak (C8 conditions: Smart fan, battery 32%, 20 min, from 39 °C; 66
runs) — the gain holds hot:**

| runs | >50 ms | GHz | die °C | fan |
|---|---|---|---|---|
| 1-10 | 0.9 | 2.934 | 90 | 48% |
| 21-30 | 2.1 | 2.853 | 94 | 54% |
| 41-50 | 3.4 | 2.840 | 95 | 55% |
| 61-66 | 3.7 | 2.830 | 94 | 55% |

| steady state (runs 41+) | BJ Smart soak | lock-free + reset |
|---|---|---|
| frames > 50 ms | 21.7 | **3.5 (-84%)** |
| frames > 66 ms | 0.61 | **0** |
| eff. GHz | 2.799 | **2.834** |
| die °C | 91.6 | 94.5 |
| FanBase sensor °C / fan | ~75 / 58% | ~73 / 55% |

- The die runs ~3 °C hotter (the vCPU is no longer asleep in futex), but
  the board sensor runs ~2 °C cooler and the sustained clock is
  *higher*. That is consistent with BJ's finding that the throttle
  follows the board/skin sensor, not the die.
- The tail at steady state is better than the cold-device baseline was
  (~7).
- These are separate sessions, but the effect is ~6x the
  Smart-vs-Sport-fan difference.

## BM. SAVES FROZE THE GAME FOR 0.46-0.74 s; FAST SAVES STOP IT FOR ~130 ms (2026-10-11)

A play session with frequent quick saves showed 0.45-0.94 s freezes, each
bracketed by an AAudio pause/start on the QEMU main-loop thread: a save.
Timed in place (debug `SAVE_TEST` broadcast, `test_` names only):

| Phase of `save_snapshot()` | New state | Overwriting |
|---|---|---|
| Delete the old snapshot (main loop, before the stop) | — | 102 ms |
| `vm_stop` | 4 ms | 10 ms |
| Write ~70 MB of state into the qcow2 | **643 ms** (new clusters) | 121 ms |
| `qemu_fclose` | 0.3 ms | 145 ms |
| Create the internal snapshot | 88 ms | 84 ms |
| **Total** | **739 ms** | **462 ms** |

A probe that sent the state to a memory buffer and made a state-less disk
snapshot stopped the VM for 131-136 ms (state to memory 58 ms, disk
snapshot 66 ms), so the state file can be written after the resume.

Shipped (`xemu_fast_save_*` in `migration/savevm.c`): the state is written
to `files/states/<name>@<date>.xstate` after the VM resumes (55-190 ms,
off the main loop), paired with its disk snapshot by creation time. The
old snapshot is deleted later, while paused (`xemu_fast_prune`), because
deleting one costs 70-100 ms of main loop: done right after the save, it
was a second 110-130 ms hitch.

- Per save: one 161-185 ms frame, down from 0.46-0.74 s (0.6-0.9 s in play).
- Loading a fast save takes 184-188 ms; old in-image states still load
  through `load_snapshot()`.
- Pruning 3 replaced snapshots while paused took 302 ms, unseen.
- The remaining ~130 ms is about half state capture and half the qcow2
  snapshot (refcount updates over the whole image). Neither was attempted.

## BN. HALO AMMO-BAR FLICKER ON VULKAN: TWO RENDERER BUGS, THEN TURNIP (2026-10-12)

Symptom: with active camo, Halo's top-left ammo bars blinked out on Vulkan
(OpenGL fine). Repro: user's quick slot 1, 20 sampled frames per run.

| Build / driver | Bars shown |
|---|---|
| Vulkan + Turnip v26.3.0-R3, before | 2-5 / 20 |
| Vulkan + Turnip, vertex fix | 12-17 / 20 |
| Vulkan + Qualcomm stock driver, vertex fix | 60 / 60 |

Fixed (`vk/draw.c`): a draw only protected vertex-RAM pages *uploaded in the
current command buffer*, so a page uploaded earlier (or cleared by a submit
inside `begin_pre_draw`) could be overwritten under a queued draw. The draw's
ranges are now marked in `uploaded_bitmap` after `ensure_command_buffer`.
Also fixed (`vk/texture.c`): `texture_content_hash` mapped the dirty range
from the page boundary instead of the texture base, dropping writes to
non-page-aligned textures (no visible effect found here).

Residual on Turnip only. The bars' draw (inline buffer, 4 verts, tex0
0x3098000, blend 0x4ca) renders nothing unless it is the first draw in a
submit. **Ruled out by experiment:** texture cache, surface overlap with
vertex pages, UBO / inline / staging data changing before submit (all
identical at submit; staging memory is coherent), 16-byte inline alignment,
render-pass restart, descriptor rewrite, full ALL_COMMANDS memory barrier,
fresh framebuffer, aux submits in between. A submit right *before* the draw
fixes it 100%; right after does not. Not resolved; the stock driver is
correct. Turnip saves ~3% CPU with no fps change (AI), so the stock driver
costs little.

## BO. A PLAYTEST STUTTER REPLAYED: 0.75 s OF 20 fps IS PLAIN vCPU OVERLOAD (2026-10-12)

Auto-capture (`_bench_auto`, 22:54) replayed twice with `BENCHMARK --es state
bench_auto --ei frames 600`. Both runs: mean 33.9/34.0 ms, and the same burst
~14.5 s in: 15-18 consecutive ~50 ms frames, shaders +0, TLB +0.

**Correction (BP):** the "~10k notdirty traps" on the first slow frame of each
burst was a counting artifact. The slow-frame line only advanced its
baselines on slow frames, so the first one after a quiet stretch reported
every trap since the previous slow frame. Per-frame, burst frames show 30-90
against a steady 9-13. The same applies to the 35k figure quoted from play.

framelog.bin: burst frames take 129-137 M vCPU cycles against a 90 M median
(x86 at ~98% in the panel), so the game's own CPU work per frame rose ~1.5x
and each frame slipped from two vblanks to three. Not a stall, a lock, or
the notdirty page (P, Q). (BP refines "over budget": the extra time is
mostly dispatcher passes, not the game's own code.)
The 187/218 ms single hitches seen while playing did not reproduce in replay
(only the post-load ones, 235-351 ms, which are the load itself).

Harness fix: a BENCHMARK broadcast now quiets the auto-capture watcher for
its duration, so a replayed stutter cannot overwrite the capture it replays.

## BP. THE 20 fps STRETCHES ARE HALO'S OWN WORK; THE "DISPATCH" COST IS THE KERNEL IDLE LOOP (2026-10-13)

Workload: the user's slot 5, saved mid-stall during a slot-4 replay; it
replays as 50 ms frames for most of its first seconds. Slot 4 also reaches
the same kind of stall ~43-57 s in, but not deterministically.

**Measurement bug fixed first.** The slow-frame line advanced its baselines
only on slow frames, and `xemu_notdirty_top()` reset only when read, so the
first slow frame after a quiet stretch reported everything since the last
one -- the "10k-35k notdirty floods" of BO were sums over hundreds of
frames. Baselines now advance every guest frame. A `ref frame` line is
logged every 300 frames for comparison.

**simpleperf + guest_map (slot 4, 70 s):** in stall frames `cpu_exec_loop`
self time rose from 2.5 to 11.3 samples/frame, ~70% of it on the
instruction after the `dmb ish` of `qatomic_set_mb(icount_decr.high)` in
`cpu_handle_interrupt`, i.e. per-dispatcher-pass cost, ×4.6 passes.

**What the passes are.** New per-frame counters (dispatch passes, irq,
exit_request, kicks, two-page TBs, translations, longjmps): passes were
200k-1M/frame and ~98% at two blocks, 0x8001b02f / 0x8001b030. Guest bytes
(`dump_pc`): `sti; nop; nop; cli; cmp ebp,[ebp]; je; ... cmp [ebx+0x2c],0;
je` -- the Xbox kernel's **idle loop** (KiIdleLoop: no DPC, no next thread).
It never `hlt`s, and `sti` ends a TB, so it spins through the dispatcher.
irq/exitreq/kick/xpage/gen are all in the tens to low thousands.

**Per-frame time split (slot 5, 900 frames):**

| | wall | kernel idle | Halo busy (wall - idle) | NV2A thread on-CPU | vCPU on-CPU |
|---|---|---|---|---|---|
| ref frames | 33-35 ms | 4.7-9.4 ms | ~24-29 ms | 18.6-21.8 ms | = wall |
| stall frames | 50-52 ms | 0-23 ms, mostly 10-20 | ~28-47, median ~38 | 20.6-24.1 ms | = wall |

- The vCPU is never descheduled (on-CPU = wall).
- The NV2A thread is not the limit: ~22 ms either way.
- The stall is **Halo's own work exceeding 33.3 ms**; the frame then waits in
  the idle loop for the next vblank (50 ms). The cpu_exec_loop growth in the
  profile was that wait, not overhead.
- The extra game work is spread thinly across many game pages (each ~1%);
  no hotspot. Only general TCG speed helps these stretches.
- Hitches of 187/218 ms during play (BO) were the user's own quick saves
  (slot_4 file times 22:51:38.47 / 22:52:47.57 vs slow frames .682 / .751).

**Lead (not built): halt the kernel idle loop.** It burns a big core at
100% for 5-9 ms of every normal frame (15-28%) and up to ~24 ms of a stall
frame, each pass through a full barrier. Halting the vCPU there until the
next interrupt (what `hlt` would do; only an interrupt can queue a DPC or
ready a thread on the single-CPU Xbox) is a power/heat saving, which
section Q showed feeds back into sustained clocks. It cannot speed up a
frame directly: the idle time is after the work.

Instrumentation left in: `xemu_idle_ticks` (cntvct between consecutive
dispatcher passes inside 0x8001b02e-0x8001b047; this kernel's address), the
loop counters, and per-thread CPU clocks for vCPU and NV2A
(`g_xemu_vcpu_clock`, `g_xemu_pfifo_clock`) in the slow/ref frame lines.
Also: a debug LOAD_STATE broadcast overwrote the _bench_auto capture via
auto-capture (fixed, 6df13fbe25).

## BQ. HALTING THE KERNEL IDLE LOOP: −23% vCPU TIME, −12 °C -- BUT IT EXPOSES A BOOT RACE (2026-10-13)

BP found the Xbox kernel's idle loop (KiIdleLoop, 0x8001b02e: `sti; nop;
nop; cli; cmp ebp,[ebp]; je; ...; cmp [ebx+2Ch],0; je`) spinning a big core
whenever every guest thread is blocked. It never executes `hlt`.

**Implementation** (`debug.xemu.idle_halt`, translate.c + misc_helper.c
`helper_xemu_idle`): at the first `nop` after the `sti` (pattern
`90 90 fa 3b 6d 00 74 0c`, CPL 0) -- inside the interrupt shadow, where
`sti; hlt` waits on real hardware -- if IF=1, the DPC list is empty and
`Prcb->NextThread` is 0, halt exactly as `helper_hlt` does. The next
interrupt wakes it and returns to the nop; the loop re-tests.

**Measured, slot 5, 600-frame runs interleaved in one process, round 1
discarded:**

| per frame | off | on |
|---|---|---|
| vCPU on-CPU | 33.0-33.3 ms | **25.3-26.0 ms** (−23%) |
| idle spinning | 6.6-7.7 ms | 0 (halted 7.5-8.0 ms) |
| mean frame time | 33.7-34.0 | 33.7-33.9 |
| frames > 45 ms | 9, 11, 19 | 8, 11, 14 |
| hottest CPU sensor at end | **94.7-95.5 °C** | **81.2-83.6 °C** |
| battery power | 7.28-7.65 W | 7.16-7.27 W (noisy) |

**But it breaks boots.** Jade Empire cold boots bugcheck in ~60% of
launches: `0x0A IRQL_NOT_LESS_OR_EQUAL`, two signatures -- a page fault
reading 0x19 at IRQL 0x1c (clock), or a context switch from the idle loop
to `NextThread = 0x00000001`. Scheduler state is corrupt. Both happen at the
same loading step (the last disc reads are identical across crashes:
LBA 0x2afc6..0x2b11e), ~700-2000 ATAPI commands in. Halo's gameplay ran
8 interleaved benchmarks without a crash; Halo cold boot was not tested.

| Jade boots, idle_halt | crashed |
|---|---|
| 0 off | 0/4 (+1/1 on the final default build) |
| 1 halt | 3/4, 3/6, 6/9 across runs |
| 1 halt, main loop only (not the 0x8001b078 DPC loop) | 3/6 |
| 1 halt, Fast boot (stall_hle) off | 5/6 |
| 1 halt, APU DSP off | 4/6 |
| 2 sleep 100 us, no halt | 5/6 |
| 4 reads only, no delay | 0/6 |

**So it is not the halt.** A plain 100 us delay in the idle loop crashes
as often; the reads alone do not. Making the idle loop slower exposes a
timing race elsewhere -- not Fast boot, not the DSP, and not disc DMA into
kernel data (an IDE PRD logger saw DMA into low pages 0x2000-0xe000 in
crashing and clean boots alike, none into 0x30000-0x40000 where the PRCB,
idle stack and the driver's PRD table live). Not found yet. A real P3's
idle loop is ~ns per pass and real interrupt latency is µs; ours with the
halt is the host wake latency.

**Disposition (superseded by BR):** first shipped OFF; modes 1/2/4 left
behind the property for the next attempt. BR found the corruption was an
old nv2a.c hack, not a race, and the halt is now ON by default. Next step would be a write
watchpoint on `Prcb->NextThread` / `CurrentThread` (KPRCB at 0x80035bdc,
+0x2c / +0x28) logging any store of a non-kernel pointer with its EIP --
that names the corrupting code, guest or device. The prize is large:
−23% vCPU time and −12 °C at equal frame rate.

Also added: `bench: IDLE per frame` summary line (vCPU and NV2A on-CPU,
spin, halted, temperature) at the end of every benchmark.

## BR. THE "BOOT RACE" WAS AN EARLY-PORT HACK WRITING 1 TO NextThread; IDLE HALT NOW ON (2026-10-13)

BQ's corruption, found with two watchpoints (`debug.xemu.kprcb_watch=1`,
misc_helper.c):

1. **TCG write watchpoint** on `Prcb->CurrentThread/NextThread`
   (0x80035c04, 8 bytes; log-only flag `XEMU_BP_LOG` in
   accel/tcg/watchpoint.c), logging PC, GPRs, stack, IRQL and the old
   values per store. 5 crashes out of 6 boots, all identical: the last guest
   store left `NextThread = 0` as the scheduler switched to the idle thread;
   ~0.9-1.0 s of halted idle followed (only clock interrupts); then the idle
   loop found `NextThread = 1` with **no guest store in between**.
2. **Host ARM hardware watchpoint** (perf_event `PERF_TYPE_BREAKPOINT` on the
   host RAM backing guest 0x35c08, every thread but the vCPU, samples with
   user call chain read from the perf ring at the crash; signals cannot be
   used because QEMU threads block them and a blocked forced SIGTRAP kills
   the process). It caught the writer at once: the **vblank-timer thread**,
   `vblank_timer_thread -> graphic_hw_update -> nv2a_vga_gfx_update ->
   cpu_memory_rw_debug`, storing 1.

**Cause.** `nv2a_vga_gfx_update` (Android only, from the April GLES port)
sampled the vCPU's EIP every 6 vblanks; if it was 0x8001b02f for ~2 s with
`[ebx+0x2c] == 0`, it wrote 1 there "to unblock the wait". 0x8001b02f is
the idle loop's nop, exactly where a halted (or mode-2 sleeping) idle loop
sits, so any ~1 s idle in a load tripped it; the kernel then switched to
"thread" 0x00000001 and bugchecked. Its `LOOP FORCE-SIGNAL` log line is in
all 6 crashed boots and in neither clean one. It explains every BQ row:
modes 1 and 2 park EIP at 0x8001b02f, mode 4 and mode 0 do not. With the
halt off a long idle could still trip it, so removing it is a fix on its
own.

**Fix:** hack removed; `g_xemu_idle_halt = 1` (on by default,
`debug.xemu.idle_halt=0` turns it off). **Default reverted to off by BS**
(MGS2 stalls with it on).

| boots after the fix | result |
|---|---|
| Jade, halt on, watchpoints on | 6/6 clean, ~4490 ATAPI commands (full load); host watchpoint: no writer but the vCPU |
| Jade, halt on, watchpoints off | 4/4 clean |
| Jade, halt off | 2/2 clean |
| Jade, default build (property unset) | 2/2 clean, mode 1 |
| Halo cold boot, halt on (never tested before) | 3/3 to the main menu, 29.6 fps |

BQ's measurements stand: −23% vCPU time, ~−12 °C at equal frame rate.

**Lesson:** a corruption that looks like a timing race can be a host-side
write. A host hardware watchpoint on the guest RAM page answers "who wrote
this?" for every thread in one run; the guest-side watchpoint alone could
only say "not the guest".

## BS. IDLE HALT STALLS MGS2's COLD BOOT; DEFAULT BACK TO OFF (2026-10-13)

Cold boots of the rest of the library with the halt on (BR's default
build), 60 s each, screenshot of the game screen:

| game | halt on |
|---|---|
| Halo 2 | "Press Start", 30.5 fps |
| Jet Set Radio Future | title screen, 59.9 fps |
| Tony Hawk's Pro Skater 2x | intro video, 30.5 fps |
| **Metal Gear Solid 2: Substance** | **stuck on the "Hideo Kojima" logo** |

MGS2, 90-120 s runs:

| idle_halt | result |
|---|---|
| 0 off | 3/3 reach the intro animation (~212 ATAPI commands, vCPU ~92%) |
| 1 halt | 3/3 stuck on the logo (~177 commands, then none; vCPU ~15%) |
| 2 sleep 100 us, no halt | stuck |
| 4 reads only | intro |

So, as with BQ's rows, *any* slower idle loop does it; it is not the halt's
wake path. The disc reads are paced pairs every 1-2 s (a stream) and stop.
Ruled out:

- **Lost interrupts:** per-vector delivery counts (temporary counter) match
  off vs on for USB 0x31, GPU 0x33, IDE 0x3e and SMBus 0x3b, apart from IDE
  stopping when the stream stops. Clock 0x30 is ~6% lower with the halt
  (ticks coalesced during the wake latency).
- **The second loop at 0x8001b078:** the helper is emitted only at the main
  idle loop.
- **The removed hack:** it does not fire.

The vCPU health log shows `irq_req=0x2` (HARD) pending at nearly every
sample in *every* mode, including off -- a line that stays asserted.
Unexplained, and a lead. Not found yet: what MGS2's streaming thread waits
on.

**Disposition:** `g_xemu_idle_halt = 0` again. MGS2 with the default
build: intro, 212 commands. BR's hack removal stands on its own.
**Superseded by BT:** the cause was lost PIT ticks; fixed, halt back on.

## BT. MGS2's STALL WAS LOST CLOCK TICKS -- 3.6% EVEN WITHOUT THE HALT; REINJECTED, HALT ON (2026-10-13)

Followed MGS2's stalled stream down to the cause with on-demand guest
introspection (`debug.xemu.kprcb_watch=2` plus `thread_dump`, `watch_va`,
`dump_mem`, `dump_ring`, misc_helper.c):

1. **Per-thread switch table** (ESP at each switch-out = the blocked
   thread's wait chain). The disc-stream thread had last run exactly when
   the reads stopped, blocked with no timeout on a semaphore (limit 1).
2. **Write watch on that semaphore** with the halt off: released every 1-3 s
   by a game thread that wakes every ~16 ms. With the halt on, the same
   thread still woke every 16 ms but stopped releasing.
3. **Disassembled the game's stream code** (dumped from guest RAM,
   assembled as `.byte` and read with llvm-objdump). It is a 512 KB packet
   ring: a read is requested only when a third of the ring is free, and the
   read cursor advances only over consumed packets.
4. **Ring walk:** stalled, every audio packet (type 0x01) was consumed and
   the ring was full of unconsumed video (0x0c); flowing, both were mixed.
   Video had fallen behind audio, and the player deadlocked.
5. **APU unchanged** (1,500 frames/s, 6 voices in both modes), so audio runs
   on real time. The guest's clock did not:

| PIC IRQ0 (PIT, 1 kHz), MGS2 boot | edges | cancelled before taken |
|---|---|---|
| idle_halt 0 (shipped) | 42,383 | 1,507 (**3.6%**) |
| idle_halt 1 | 42,437 | 4,116 (**9.7%**) |
| idle_halt 2 (sleep) | 42,436 | 4,069 |

xemu's i8259 (`#ifdef XBOX`) clears a pending edge IRQ on its falling
edge, as a real 8259 does; the PIT's square wave is high for ~0.5 ms, so a
tick the vCPU had not taken by then was dropped. A real CPU takes it in
microseconds; ours sometimes takes over a millisecond (halted host thread
waking, long TCG stretches). Latching IRQ0 alone only turned the drops into
merges (10% / 3.5%) at the next edge.

**Fix (hw/intc/i8259.c):** IRQ0 stays pending until acknowledged, and a
tick that arrives while one is still pending is owed and re-raised at the
acknowledge (KVM-PIT-style reinjection, capped at 200, cleared on reset).
`bench: clock ticks (total) N, reinjected M` logs it every 300 frames.

| after the fix, 60 s cold boots, halt on | result |
|---|---|
| MGS2 | intro movie (332-338 commands; was stuck at 177). Halt off: 330 (was 214 -- the 3.6% loss slowed it too) |
| Halo, Jade, Halo 2, JSRF, THPS2x | menu / title / intro as before; Jade loads fully |
| reinjected ticks | Halo 140, THPS 193, JSRF 258, Halo 2 308, Jade ~1,000-3,700, MGS2 ~5,700 per ~62,000 |

Halo slot 5, 600 frames, 4 interleaved rounds:

| | halt off | halt on |
|---|---|---|
| mean frame | 33.7-34.3 ms | 33.6-34.0 ms |
| frames > 45 ms | 7, 11, 17, 29 | 6, 10, 6, 18 |
| vCPU on-CPU | 32.9-33.6 ms | **25.3-26.3 ms** |
| hottest CPU sensor | ~95 °C | **80-85 °C** |

**Disposition:** idle halt ON by default again (`debug.xemu.idle_halt=0`
turns it off). The tick fix applies with or without it: every game's guest
clock now keeps real time, where it ran 3-4% slow before.

**Lesson:** when a timing change breaks a game, check the guest's clock
against the real-time devices (APU, vblank) before blaming the change: here
the change only amplified an existing drift.


## BU. IDLE HALT OVER A 20-MINUTE SOAK: SLOW FRAMES −56%, ~11 °C COOLER, +0.15 GHz (2026-10-14)

Slot 5 (the agreed benchmark save), back-to-back 600-frame BENCHMARK runs
in one process per arm, 20 min each, on battery, fan and performance mode
at the user's settings (Smart, High Performance). Halt on first (start
34 °C), then halt off after a capped 25-min cool-down (start 38.8 °C, which
it passed within its first run). Script: scratchpad `soak6.sh`.

| 5-min period | halt | >50 ms/run | mean frame | vCPU ms | GHz | hottest CPU | fan |
|---|---|---|---|---|---|---|---|
| 0-5 | on | 4.7 | 33.67 | 25.4 | 2.93 | 76.5 °C | 48% |
| 0-5 | off | 7.0 | 33.78 | 33.1 | 2.83 | 94.3 °C | 57% |
| 5-10 | on | 8.8 | 33.80 | 25.6 | 2.89 | 81.7 °C | 57% |
| 5-10 | off | 19.0 | 34.23 | 33.5 | 2.70 | 94.7 °C | 60% |
| 10-15 | on | 9.3 | 33.83 | 26.2 | 2.84 | 83.9 °C | 59% |
| 10-15 | off | 24.6 | 34.44 | 33.7 | 2.67 | 94.9 °C | 61% |
| 15-20 | on | 12.2 | 33.93 | 26.4 | 2.81 | 84.2 °C | 60% |
| 15-20 | off | 28.2 | 34.56 | 33.8 | 2.66 | 94.7 °C | 61% |

All 47 runs per arm: >50 ms frames **416 (on) vs 938 (off)**, mean frame
33.81 vs 34.26 ms; battery 6% vs 8% in 20 min. No frame over 66 ms in either.

With the halt off the die reaches its ~95 °C regulation point in the first
minute and the clock sags to 2.66 GHz; with it on it settles near 84 °C and
holds 2.81 GHz. Same mechanism as C8 (heat -> clock -> slow frames), now
removed at the source instead of with the fan. Confirms BQ/BT's default.

## BV. PHASE 0 FOR SUPERBLOCKS: THEY FAIL THE GATE; RETURNS ARE THE BOUNDARY THAT COSTS (2026-10-15)

Lead C2 (superblocks) priced before building, as planned. New diagnostic
`debug.xemu.tbprof=1` (translate.c): every TB gets a slot with its guest PC,
length and how it ended (opcode of the last instruction), and generated code
bumps an exact execution counter on entry, chained or not; inline-cache
misses are counted per exit site (tcg-op.c slow path). Zeroed at BENCHMARK
start, written to `files/tbprof.bin` at its end.

**Instrumentation check.** Same benchmark with `debug.xemu.nochain=1`, where
the dispatcher's own counter is exact: guest instructions 10,356,801 vs
10,356,958 per frame, TB executions 1,653,309 vs 1,653,338 -- agree to
0.003%.

**Correction to section V.** Slot 5 runs **~10.6M guest instructions per
frame** (1.78M TB executions, 6.0 per TB), three times V's 3.56M measured on
slot 2. The clock spin (M) does not run here (1 exec/frame). So V's "45 host
instructions per guest instruction" does not describe this scene; with ~76
Mcyc of vCPU time per frame it is ~7 cycles per guest instruction.

**TB ends, slot 5, halt on, per frame (two runs agree within 2%):**

| last instruction | execs | share | target off-page | IC misses |
|---|---|---|---|---|
| jcc | 1,195k | 67% | 2.4% | ~0 |
| ret | 245k | 14% | -- | **74.5k (30%)** |
| call rel | 197k | 11% | **82%** | 7.9k (4%) |
| jmp rel | 69k | 4% | 3% | ~0 |
| call r/m | 48k | 3% | -- | 1.2k |
| other / fall / jmp r/m / sti | 25k | 1.4% | | 1.1k |

**Superblocks fail the gate (≥ ~1 ms/frame, 3%).** Following direct `jmp` and
`call` would remove 264k boundaries/frame. At section K's ~2.1 cycles per
boundary plus the inline-cache check on the 160k off-page calls (which
already hit 96%), that is ~1-2 Mcyc, **0.4-0.7 ms/frame (1.5-2.5%)**.
Extending through `jcc` (67% of ends, both sides already chained) is bounded
by K's sync ceiling (~3%) at far higher complexity. Not building.

**What the measurement found instead: returns.** A `ret`'s target depends on
the caller, so its single-entry inline cache misses 30% of the time, and
each miss calls `helper_lookup_tb_ptr`, often falling through the jump cache
to `qht_lookup_custom`. simpleperf on the vCPU thread during the same
benchmark: helper_lookup_tb_ptr 4.36% + qht_lookup_custom 2.65% +
tb_lookup_cmp 0.53% = **~7.5% of vCPU time, ~2.0 ms/frame for ~85k misses,
~65 cycles per miss; 87% of the misses are returns.**

**Lead (next): a guest return-address stack for TB lookup.** At a call, push
the return EIP and a per-call-site slot caching the host TB for it; at
`ret`, pop and, if the EIP, flags and IC generation match, goto the cached
TB, else take today's path (so a mismatch costs only the check). Up to
~1.7 ms/frame (~6%) if it removes most return misses; expected to show in
the tail, like LDAPR (AL). This is NOT the dead "return-address-stack"
entry in section D, which was host branch prediction (0.10% mispredicts);
this is the cost of finding the next translated block.



## BW. RETURN-ADDRESS STACK FOR TB LOOKUP: WORKS, BUYS NOTHING (2026-10-15)

Built BV's lead behind `debug.xemu.ras` (default off): each call site owns
an inline-cache-shaped slot for "the code at my return address" and pushes
it on a 16-entry ring after the guest's own push; `ret` pops it and, if
generation, EIP and flags match, jumps there, else falls back to its own
inline cache and the helper and fills the popped slot
(`tcg_gen_lookup_and_goto_ptr_ras`, tcg-op.c; `gen_ras_push`, translate.c).
Halo boots and plays with it on.

**Mechanism confirmed (tbprof, slot 5):** ret IC misses 74.5k -> 9.7k per
frame (30% -> 4.1%); all IC misses 85k -> 20.5k.

**Profile, vCPU thread:** helper_lookup_tb_ptr 4.62% -> 2.04%,
qht_lookup_custom 2.89% -> 2.22%, tb_lookup_cmp + TLS 1.22% -> 0.95%: the
lookup path 8.7% -> 5.2%. qht barely moves -- most of its cost is other
callers (the dispatcher's own lookups), not return misses.

**Wall result, 12 paired rounds in one process, slot 5, halt on, 600 frames:**

| | ras on | ras off |
|---|---|---|
| vCPU on-CPU | 25.73 ms | 25.91 ms |
| paired difference | **-0.18 +- 0.18 ms (se)**, sd 0.63 | |
| frames > 45 ms per run | 19.3 | 19.3 |
| mean frame | 34.22 ms | 34.19 ms |

A ~3.5-point drop in sampled lookup time becomes <1% of vCPU time and no
tail change: the lookup work largely overlapped memory stalls (the W/AC
pattern), and the push/pop code on every call and return pays back part of
the rest. BV's 65-cycles-per-miss price came from sample shares and was the
error -- the same trap as W. Disposition: off; code kept behind the switch.

**Where this leaves "faster translated code":** superblocks, flags, register
allocation, out-of-lining and now block lookup are all measured at <=1-3%.
The vCPU's time is the game's own memory traffic (Z) and code fetch; the
remaining lever with demonstrated size is heat/clock (BJ, BU).

## BX. THE NV2A THREAD, PROFILED: HALF THE PROCESS, AND WHERE IT GOES (2026-10-15)

Slot 5, 1200-frame BENCHMARK, halt on, simpleperf 20 s with `--call-graph
fp` (scratchpad gpu/gpu.data). NV2A thread 18.7 ms/frame on-CPU, vCPU
24.4 ms. Process share by thread: **nv2a.pfifo 50.4%**, vCPU 19.5%, the two
APU voice workers 15.8%, apu_thread 5.8%, qemu_main 2.6%. With the idle
halt, the GPU thread is now the biggest consumer by far.

**NV2A thread, by library:** libxemu 51%, libc 28%, Turnip (Vulkan) 9.3%,
**Adreno GLES 5.5%**, kernel 3.4%.

**By cost, with callers** (about half the libc samples have no caller --
no frame pointers in leaf functions -- so caller shares are lower bounds):

| item | share of thread | where |
|---|---|---|
| mutex lock/unlock + CAS/SWP | ~12.7% | `pfifo_run_puller`: unlock pfifo, lock pgraph, unlock pgraph, lock pfifo -- four mutex ops per method |
| memcpy | 10.9% | per-draw state copies: `pgraph_vk_bind_shaders` (24% of it), `update_descriptor_sets` (11%), `begin_draw`, `flush_draw` |
| dirty tracking | ~8% | `physical_memory_test_and_clear_dirty` + bitmaps + `memory_region_sync/clear_dirty_bitmap`, from `pgraph_vk_bind_textures` on every bind |
| apply_uniform_updates | 6.0% | `pgraph_vk_bind_shaders`, per draw |
| Adreno GLES | 5.5% | all `glTexSubImage2D` in `pgraph_vk_render_display`: the per-frame upload of the Vulkan display image into a GL texture (section AY's path) |
| pfifo_thread self | 5.6% | pushbuffer parsing |
| XXH3 | 4.4% | texture content hashing in `begin_pre_draw` |
| pgraph_method_log | 2.4% | a method-name table lookup on **every** method, though tracing is off; only the trace call is gated |
| clock_gettime | 1.4% | `g_get_monotonic_time` per texture bind |

**Candidates, cheapest first:**
1. `pgraph_method_log`: skip unless a trace event is enabled -- ~2.4%.
2. One timestamp per draw instead of per texture bind -- ~1.4%.
3. Uniform updates: skip unchanged blocks earlier -- part of 6%.
4. Dirty checks on texture binds: per-page tests repeated every draw -- part of ~8%.
5. Display handoff without the CPU copy and GL upload (AHardwareBuffer or
   presenting from Vulkan) -- 5.5% plus its share of memcpy, and a GPU->CPU
   readback per frame.
6. Per-method lock churn -- ~12.7%, but BL showed these locks govern the
   vCPU's waits; any change must be measured on the tail.

The point is heat, not frame rate: the vCPU does not wait on this thread
(A, BL), but BU showed ~0.15 GHz of sustained clock halves slow frames, and
this thread is now half the process's CPU.

**Done (same benchmark, re-profiled):** candidates 1 and 2.
`pgraph_method_log` now returns unless one of its trace events is enabled
(2.41% -> gone); the `vk-tex` stats check reads the clock every 256th draw
instead of every draw (clock_gettime 1.37% -> 0.15% -- it was our own BK
diagnostic, not texture code). NV2A thread 18.69 -> 18.15 ms/frame
(-2.9%, cross-session, so approximate).

## BY. UNIFORM UPDATES: COMPARE WHILE COPYING -- NV2A THREAD −8%, SLOW FRAMES −86% (2026-10-17)

BX candidate 3. Per draw, `update_shader_uniforms` rebuilt every uniform
from state, copied each array element with its own memcpy (the 192 vertex
constants: 192 calls) and then hashed both layouts in full to see whether
anything changed. Now (`debug.xemu.ubo_cmp`, default 1) one memcmp/memcpy
per contiguous array, with the change flag computed while copying; a
different or recycled binding than last time also counts as changed, so it
never uploads less than the hash path.

**Correctness check, `ubo_cmp=2`** (new path plus the old hash, counting
draws the hash would flag and the comparison did not): **0 missed in ~2M
updates.** On slot 5 every update counts as changed under both -- Halo
rewrites its constants every draw (~1,600/frame) -- so the saving is the
copy loop and the hashing, not skipped uploads.

**Slot 5, 600 frames, one process, ABBA x3, mode confirmed per run by the
`vk-ubo:` line (device charging, equal for both arms):**

| | ubo_cmp=1 | old (0) |
|---|---|---|
| NV2A thread on-CPU | **17.00, 17.04, 17.10, 17.35, 17.40, 17.66** | 18.47, 18.44, 18.74, 18.81, 18.96, 19.04 |
| frames > 45 ms | **1, 1, 2, 3, 1, 2** | 9, 7, 14, 11, 13, 18 |
| mean frame | 33.5-33.6 ms | 33.7-34.0 ms |
| vCPU on-CPU | 24.5-26.1 | 24.8-26.3 |

-1.4 ms/frame on the NV2A thread (-8%) in every pair, and the tail falls
~86%. The tail effect is BL's mechanism: the pfifo thread holds pg->lock
for whole draws, the vCPU's NV2A accesses queue behind it, and shorter
draws shorten those waits.

**Lesson:** NV2A-thread savings are not only heat. While draws run under
the lock, making them cheaper also helps the vCPU directly.


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
| Out-of-lining memory access as built | -23% code, -19.5% L1I, but +15% instructions cancels it — section AC |
| Eliminating CC helper calls | 97% removed, 1.1% fewer host insns, ZERO cycles saved — section W |
| Trusting absolute PMU figures from before section U | counters were multiplexed to 9-29%; understated 3-11x |
| Dynamic spin detection (auto) | detects correctly, but the benchmark has no spin worth eliding; 0.2-0.6 ms/frame worse — section R |
| Trace JIT / LLVM backend (HQEMU, Instrew) | user-mode results; system-mode ceiling 1.15x |
| GPU thread stealing vCPU time by blocking | vCPU shows no wait symbols, 1.26% kernel |
| Shrinking the JIT translation buffer (iTLB locality) | **MEASURED 2026-09-12: tb-size 256 vs 32 MB = 32.55 vs 32.55 ms/frame.** iTLB misses rose with the smaller buffer. Switchable via `debug.xemu.tb_size`, default 256. |
| Huge pages for guest RAM | **MEASURED 2026-09-12: actual page-table walks are ~4,800/frame against ~148k last-level misses, about 3%.** Nearly all dTLB refills are served by the L2 TLB and never become walks. 2 MB pages would remove a rounding error, so rooting the device for THP is not worth doing. (THP is also `[never]` here and root-only; QEMU already issues MADV_HUGEPAGE at physmem.c:2421.) |
| ~~Guest busy-wait / spin elimination~~ **THIS ROW WAS WRONG — see sections M and N.** The spin is real and large: ~23% of generated-code time in a 64-bit clock wait at guest 0x000bb0df. It reads ORDINARY MEMORY, so the MMIO-based test below could not have detected it, and the conclusion drawn from that test was unjustified. It remains *not worth doing for frame rate* — section N shows it is 14.2% of on-time frames against 1.8% of dropped ones — but that is a different and better-supported reason. Original (MMIO-only) reasoning, kept as the record of the mistake: **MEASURED 2026-09-11.** MMIO is only ~730 accesses/frame, and it is FLAT across frame classes (x1.06, x1.09) while host work rises x1.48. Cheap frames sit at 93 Mcyc against a 98 Mcyc budget. The 96-98% utilisation figure reflects real work, not polling. |
| MMIO lockless_io fast path (skipping BQL + device lock) | Same run: ~730 MMIO accesses/frame. Even at a generous ~1000 cycles each that is <1% of a 93 Mcyc frame. The ~10-line change is real and QEMU already ships the switch, but there is nothing to win. |
| L3 contention from the GPU thread evicting the vCPU's data | **MEASURED 2026-09-11: IPC is FLAT across frame weights** (cheap vs expensive: 1.69/1.67, 1.85/1.90, 1.86/1.90). If GPU memory traffic were evicting us, heavy frames — which have more GPU work — would show *lower* IPC. They do not. My hypothesis, killed by my own measurement. |
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

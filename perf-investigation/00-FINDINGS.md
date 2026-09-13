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
| Host IPC | 1.80-1.85 |
| Branch mispredicts | 0.10% of instructions |
| Cache misses | 0.13% (~213k/frame, ~23% of frame at ~100cyc) |
| Guest instructions/frame | ~4.4-6M |
| Emitted per guest instruction | 82 bytes / 20.5 host instructions |
| Executed per guest instruction | ~33 host instructions |
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

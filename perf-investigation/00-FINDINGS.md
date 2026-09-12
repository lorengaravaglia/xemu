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

### 4. OPEN: `rep movs`/`rep stos` -> host memcpy
QEMU emits x86 string ops as a per-element in-TB loop
(target/i386/tcg/translate.c:1564-1680), so a `rep movsd` of 4 KB is 1,024
iterations at ~20-30 host instructions each. Halo streams and decompresses
assets, so this may be a real slice of the ~4.4-6M guest instructions/frame.
Self-contained: no kernel-API hook, no per-title signature fragility.
**Falsify in ~15 lines:** one counting helper per `rep` execution, passing
ECX; if under ~200k iterations/frame, drop it. Currently the most promising
untested lead.

### 5. OPEN (reduced): tlb_reset_dirty's O(whole TLB) scan
From the heavy-frames agent, not yet measured:
- `tlb_reset_dirty` is O(whole TLB), not O(range): 22 mmu indexes x (256+8)
  entries = 5,808 entries / ~400 KB touched per call, larger than the X3's L1D.
  It is called per dirty page from the GPU thread, writing into the vCPU's
  hottest structure. Xbox is UMA so this is armed over all 64 MB.
  Measured at 2.8% of the vCPU's libxemu cycles (= 0.39% of vCPU) directly,
  but the cache-pollution cost is unmeasured.

### 6. ~~L3 contention from the GPU thread~~ — DEAD, see section D

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
| Guest busy-wait / spin elimination (idle detection) | **MEASURED 2026-09-11.** MMIO is only ~730 accesses/frame, and it is FLAT across frame classes (x1.06, x1.09) while host work rises x1.48 — heavy frames are not spinning. Cheap frames sit at 93 Mcyc against a 98 Mcyc budget, so there is barely any slack to spin in during combat. The 96-98% utilisation figure reflects real work, not polling. |
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
4. [~] host-memory — RUNNING (its L3 component is already dead) — full cost of x86 flag emulation, NZCV mapping
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

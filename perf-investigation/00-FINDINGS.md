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

### 1. Native ARM flags (FEAT_FlagM2) — VERIFIED GAP, low cost
The aarch64 backend contains **zero** `RMIF`/`CFINV`/`AXFLAG`/`XAFLAG`/
`SETF8`/`SETF16` (all six grep to 0 in tcg/aarch64/tcg-target.c.inc) and
detects only LSE2 (:1791). The FP-compare->EFLAGS path (:3305-3311) is six
instructions (3x CSET + 3x ORR-LSL) where `AXFLAG` does one. Cortex-X3
implements all six (Armv9.0-A mandates FEAT_FlagM2).
Target: cc_compute_all ~4.3% of vCPU plus inline cc_src/cc_dst stores.
**Size 2-4%. Incremental: start with the FP-compare sequence (~20 lines,
backend only).** Prior art: Rosetta 2 uses CFINV/RMIF/SETF8; FEX maps EFLAGS
to NZCV with a redundant-flag-elimination pass; MobiSys'25 "ARMing x86 Games"
does software-only validated flag speculation for this exact workload class.

### 2. Superblocks / multiblock TBs — big pool, high cost
Boundary tax = env loads 5.8 + env stores 3.3 + branches/exits 3.1 = 12.2% of
JIT cycles, plus lookup_tb_ptr/qht ~3% of vCPU. **~13% ceiling, realistic
5-8%.** High cost: TB formation is load-bearing in QEMU, and bigger TBs worsen
SMC invalidation.
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

### 4. OPEN: guest spin-wait on MMIO, and tlb_reset_dirty's O(whole TLB) scan
From the heavy-frames agent, not yet measured:
- `pgraph_read` takes `pg->lock` and `pfifo_read` takes `pfifo.lock` on EVERY
  guest MMIO register read, and the guest busy-waits by polling those
  registers. A spin-wait produces exactly the "more cycles AND more
  instructions" signature that was attributed to heavier game frames.
- `tlb_reset_dirty` is O(whole TLB), not O(range): 22 mmu indexes x (256+8)
  entries = 5,808 entries / ~400 KB touched per call, larger than the X3's L1D.
  It is called per dirty page from the GPU thread, writing into the vCPU's
  hottest structure. Xbox is UMA so this is armed over all 64 MB.
  Measured at 2.8% of the vCPU's libxemu cycles (= 0.39% of vCPU) directly,
  but the cache-pollution cost is unmeasured.

### 5. HYPOTHESIS (mine, unproven): L3 contention from the GPU thread
The GPU thread spends ~11.5% of its cycles in memcpy/memcmp/memset -- bulk
traffic through a shared 8MB L3 -- while the vCPU takes 213k misses/frame. It
does not block us, but it may be evicting us. Fits every fact including heavy
frames. **Untested. Four plausible mechanisms have already died here.**

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
| NV2A surface churn forcing TLB + jump-cache flushes | **MEASURED 2026-09-11: 0.00 full flushes/frame, and full=0 ABSOLUTE since boot** (part=337, elide=7615 prove the counter works). The code path is real (vk/surface.c:582-601 -> physmem.c:891 async_safe_run_on_cpu + tlb_flush_all_cpus_synced, and cputlb.c:392 wipes the jump cache too) but it never fires in steady-state gameplay: `expire_old_surfaces` only evicts surfaces unused for 5+ frames, and a combat scene reuses its surfaces every frame. |

---

## E. Agent queue

1. [x] heavy-frames — DONE. Killed the surface-churn lead; found the
       reentry_insns contamination (section F); left leads 4 open.
2. [~] lazy-flags — RUNNING — full cost of x86 flag emulation, NZCV mapping
3. [ ] guest-work — spin-wait detection, HLE, MMIO volume
4. [ ] host-memory — huge pages, host dTLB, L3 contention hypothesis
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

**(a) game-caused vs (b) emulator-caused is still OPEN.**

**The fix is cheap and available:** re-run the per-frame cheap/expensive
bucketing with `debug.xemu.nochain=1` (branch android-codegen-measure), where
every TB exit returns to the dispatcher and the counter becomes an EXACT guest
instruction count. Then the cheap-vs-expensive work ratio is real. Better
still, use NV2A draw-call counters (`hw/xbox/nv2a/debug.h:71-134`) as an
independent, uncontaminated denominator.

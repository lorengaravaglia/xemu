# 01 — Heavy frames: is the 35% tail emulator-caused or game-caused?

STATUS: complete

Angle: the SHAPE of the frame-time distribution, not the average.
Combat ~32.5 ms/frame avg vs a 33.3 ms budget; ~65% of frames ~90 Mcycles,
~35% at ~150 Mcycles. Prior conclusion: "genuinely heavier game frames,
because guest instruction counts scale with the cycle ratio."

## CONCLUSION, ranked

### 0. That prior conclusion rests on a counter that is not a guest instruction count. (F7)
`xemu_guest_insn_count` is incremented in `cpu_exec_loop`'s outer dispatch loop
(accel/tcg/cpu-exec.c:1051-1055). **Chained TBs never pass through it.** The
tree itself labels it `reentry-insn` and says it is "useless as an absolute
instruction rate" (ui/xemu.c:1676-1682). It measures **TB chain breaks**, not
guest work.

And `cpu_exit()` -- which forces a chain break -- is called by
`queue_work_on_cpu` (cpu-common.c:141), i.e. by every `async_safe_run_on_cpu`,
i.e. by the exact GPU-thread mechanism under suspicion (F4). **The metric used
to exonerate the emulator is incremented by the emulator behaviour being
investigated.** The (a)-vs-(b) question is therefore still *open*, not settled.
Everything else below follows from reopening it.

### 1. Surface create/destroy costs the vCPU a full TLB wipe + full jump-cache wipe, twice, and only under TCG. (F4, F5)
Mechanism (fully verified, chain in F7): `surface_put`/`invalidate_surface` ->
`register/unregister_cpu_access_callback` (vk/surface.c:582-601, guarded by
`tcg_enabled()`) -> `mem_access_callback_insert/remove` (physmem.c:877-923) ->
**two** `async_safe_run_on_cpu` items -> two forced vCPU exits into exclusive
sections -> `tlb_flush_by_mmuidx_async_work` wipes all 22 mmu-mode TLBs **and
calls `tcg_flush_jmp_cache`** (cputlb.c:392).
Size: **0 to ~9% of frame time, entirely dependent on the per-frame surface
churn rate, which is UNMEASURED.** At 10 create/destroy per frame it is ~20
full flushes + ~40 chain breaks per frame.
Why it fits the distribution: surface churn is driven by how many render
targets the scene uses, so it is naturally higher on busy frames -- and
`expire_old_surfaces` (vk/surface.c:906-919, `max_surface_frame_time_delta = 5`,
:33) destroys and recreates any surface used less often than every 5 frames,
forever.
Cheap falsifier: **print the existing `cpu->neg.tlb.c.full_flush_count`
(cputlb.c:394) per frame. < 2/frame kills this outright.** No new code.
Cheap A/B if it survives: `max_surface_frame_time_delta` 5 -> 60, one constant.
Production fix if confirmed: the FIXME already in the code
(system/physmem.c:892, :919) -- a **ranged** flush over the surface's pages
instead of `tlb_flush_all_cpus_synced`. A 640x480x4 surface is 300 pages, under
the 16 MB threshold at cputlb.c:742, so the jump cache would survive intact
(worth up to the 2.6% the targeted-jump-cache change already banked).

### 2. The guest busy-waits, and its poll path shares two mutexes with the GPU thread. (F6)
`pgraph_read` takes `pg->lock` (pgraph.c:48-53) and `pfifo_read` takes
`pfifo.lock` (pfifo.c:43-47) on **every** guest MMIO register read -- the same
mutexes the PGRAPH thread holds while running methods. The tree records a
measurement that the guest busy-waits rather than halting (ui/xemu.c:1563-1566).
A guest spin-wait produces *exactly* the "more cycles + more instructions"
signature attributed to heavier game frames. Instruction count cannot tell
"did more" from "waited longer".
Note also: frame boundaries are set by the *render thread* receiving a new
PGRAPH frame (`bench_tick()` is called from `gl_render_frame`, ui/xemu.c:1820),
so a slow GPU thread mechanically lengthens the frame interval and the vCPU
cycles counted inside it.
Size: unbounded; unmeasured.
Cheap falsifier: count vCPU NV2A MMIO reads per frame, split cheap/expensive.
If expensive frames poll far more per draw call, it is waiting, not working.

### 3. xemu's two extra dirty-memory clients make `tlb_reset_dirty` a whole-TLB sweep over the entire 64 MB of guest RAM. (F1, F2, F3)
Xbox is UMA: `d->vram = ram` (nv2a.c:266-268) and dirty logging for
`DIRTY_MEMORY_NV2A` + `_NV2A_TEX` is armed over all of it (nv2a.c:287-290).
`tlb_reset_dirty` ignores the range length for sizing: **22 mmu modes x
(256 + 8) = 5,808 entry visits touching ~400 KB, per call** (cputlb.c:917-940,
NB_MMU_MODES=22 at cpu.h:204, 256 entries/mode from CPU_TLB_DYN_DEFAULT_BITS=8).
That is larger than Cortex-X3's 64 KB L1D and most of its 1 MB L2, and it is
called **per dirty page** from `check_texture_dirty` (vk/texture.c:477-481) and
per merged range from `sync_vertex_ram_buffer` (vk/draw.c:1623-1635) -- mostly
from the **GPU thread, writing into the vCPU's hottest data structure**.
Size: its direct vCPU cost is already known to be small (2.8% of libxemu = ~0.4%
of vCPU). The interesting, **unmeasured** part is the cache/coherence damage the
GPU thread's sweeps do to the vCPU -- which is a concrete, testable version of
the L3-contention hypothesis parked as lead #4 in 00-FINDINGS.
Cheap falsifier: count `tlb_reset_dirty` calls/frame, split cheap/expensive.

### 4. SMC invalidation is amplified by an xemu-specific change, but is probably small. (Q3)
tb-maint.c:1199-1219 removes the per-TB overlap test under `#ifdef XBOX`, so a
guest store to a code page invalidates **every TB on that 4 KB page**, not just
the overlapping ones. However the tree records ~550 `do_tb_phys_invalidate`/s
(tb-maint.c:944-946) = ~18 TBs/frame, which is ~0.1 ms/frame. **Probably not
the tail.** Per-frame variance is unmeasured; cheap to add to the same run.

### 5. Verdict on (a) vs (b)
**Not yet decidable, and specifically NOT decided by the existing evidence.**
The cleanest single discriminator is to re-run the cheap/expensive split with
**NV2A draw calls as the denominator** instead of `reentry-insn` -- draw calls
are pure guest intent and the per-frame counters already exist
(hw/xbox/nv2a/debug.h:71-134). If expensive frames issue 1.6x the draw calls,
it really is the game and this whole line closes. If they issue ~1.0x, the
extra 60% is ours. See Q5 for the full design.

## Checklist
- [x] Q1. Periodic/bursty emulator work correlating with heavy frames
- [x] Q2. DIRTY_MEMORY_NV2A / NV2A_TEX machinery + TLB_NOTDIRTY slow stores
- [x] Q3. SMC invalidation reachability + spike risk
- [x] Q4. Frame-synchronous work on the vCPU thread
- [x] Q5. Single-run experiment separating game-work from emulator-overhead

## Findings (detail)

---

## F1 (VERIFIED, code) — `tlb_reset_dirty` is an O(whole TLB) sweep, not O(range)

Call chain, all verified:

```
pgraph (GPU thread) or vCPU
  memory_region_test_and_clear_dirty()            system/memory.c
   -> physical_memory_test_and_clear_dirty()      system/physmem.c:1215
        ... clears bits ...
        if (dirty) physical_memory_dirty_bits_cleared(start,len)   physmem.c:1256-1258
         -> tlb_reset_dirty_range_all()           physmem.c:1007-1011
             -> CPU_FOREACH: tlb_reset_dirty(cpu, start1, length)  physmem.c:987-1005
                 -> accel/tcg/cputlb.c:917-940
```

`tlb_reset_dirty` (accel/tcg/cputlb.c:917-940) ignores `length` for sizing: it
loops `mmu_idx = 0 .. NB_MMU_MODES` and inside that over **every** entry of
that mode's TLB plus all 8 victim entries, calling
`tlb_reset_dirty_range_locked` (cputlb.c:888-901) which range-checks each entry
individually.

Constants (verified):
- `NB_MMU_MODES 22`                       include/hw/core/cpu.h:204
- `CPU_VTLB_SIZE 8`                       include/hw/core/cpu.h:208
- `CPU_TLB_DYN_DEFAULT_BITS 8` -> 256 entries **per mode**, allocated for *all
  22 modes* at `tlb_init` (accel/tcg/cputlb.c:301-309, 330-333). Unused x86
  modes still get full-size tables.
- `sizeof(CPUTLBEntry) == 32` (CPU_TLB_ENTRY_BITS=5, include/exec/tlb-common.h:22,43)
- `CPUTLBEntryFull` ~40-48B (include/hw/core/cpu.h:215-271)

=> **one call = 22 x (256+8) = 5,808 entry visits, touching ~176 KB of
`fast->table` + ~220 KB of `desc->fulltlb` = ~400 KB.**
Cortex-X3 L1D is 64 KB and L2 is 1 MB, so **every single call evicts the
vCPU's entire L1D and most of its L2 working set.**

It also holds `cpu->neg.tlb.c.lock` (a qemu_spin, cputlb.c:919/939) for the
whole sweep, and *writes* `ent->addr_write` in the vCPU's hottest data
structure. When the caller is the GPU thread this is a cross-core write to
lines the vCPU is actively reading -> coherence invalidations, not just
capacity misses. Note the vCPU would NOT show this as futex/wait time, so it
is fully consistent with "vCPU has no wait symbols, 1.26% kernel" in
00-FINDINGS section A.

## F2 (VERIFIED, code) — on Xbox this is armed over ALL of guest RAM

`hw/xbox/nv2a/nv2a.c:265-268`:
```c
static void nv2a_init_memory(NV2AState *d, MemoryRegion *ram)
{
    /* xbox is UMA - vram *is* ram */
    d->vram = ram;
```
and `nv2a.c:287-290`:
```c
memory_region_set_log(d->vram, true, DIRTY_MEMORY_NV2A);
memory_region_set_log(d->vram, true, DIRTY_MEMORY_NV2A_TEX);
memory_region_set_dirty(d->vram, 0, memory_region_size(d->vram));
```
`memory_region_set_log` is patched under `#ifdef XBOX` (system/memory.c:2262-2290)
to accept the two extra clients and to bump `vga_logging_count`
unconditionally.

So dirty logging for NV2A + NV2A_TEX is enabled on the **entire 64 MB of guest
RAM**, not on a separate VRAM aperture. Every guest page is a candidate for
`TLB_NOTDIRTY`.

## F3 (VERIFIED, code) — the `TLB_NOTDIRTY` slow-store rule, and why xemu makes it stickier

- Fill time: `tlb_set_page_full` sets `write_flags |= TLB_NOTDIRTY` iff
  `physical_memory_is_clean(iotlb)` (accel/tcg/cputlb.c:1080-1088).
- `physical_memory_is_clean()` (system/physmem.c:1058-1066) is
  `!(nv2a && nv2a_tex && vga && code && migration)` -- **all five** bits must
  be set for the page to count as dirty. Upstream QEMU has three clients here;
  xemu adds two. Each added client is another way for a page to be "clean" and
  therefore another way to arm TLB_NOTDIRTY.
- Store time: a guest store to a TLB entry with TLB_NOTDIRTY takes the slow
  path (cputlb.c:1428-1431, 1452-1455, 1475-1478, 1502-1517, 1723-1725,
  1895-1896) -> `notdirty_write` (cputlb.c:1340-1362), which
  (a) may call `tb_invalidate_phys_range_fast`,
  (b) `physical_memory_set_dirty_range(..., DIRTY_CLIENTS_NOCODE)`,
  (c) clears the flag via `tlb_set_dirty` **only if**
      `!physical_memory_is_clean(ram_addr)` (cputlb.c:1358-1361).
- `DIRTY_CLIENTS_NOCODE` = all bits except CODE (include/system/physmem.h:14-15).
  So after a notdirty store the CODE bit is still whatever it was. If the page
  holds translated code, CODE stays clear, `is_clean` stays true, `tlb_set_dirty`
  is **not** called, and **every subsequent store to that page also takes the
  slow path** -- that is the intended SMC mechanism, but it means a page that
  is both code-bearing and NV2A-tracked is permanently slow-path for stores.
- `tlb_set_dirty` (cputlb.c:952-971) itself loops all 22 modes x (1 + 8 vtlb).

Net rule: **each `test_and_clear_dirty` on a page re-arms TLB_NOTDIRTY for it,
and costs one full 5,808-entry TLB sweep, and the *next* guest store to that
page takes a helper call.** Both halves scale with GPU activity.

## F4 (VERIFIED, code) — every surface create/destroy costs the vCPU a FULL TLB flush + FULL jump-cache flush, and is TCG-only

`hw/xbox/nv2a/pgraph/vk/surface.c:582-601`:
```c
static void register_cpu_access_callback(NV2AState *d, SurfaceBinding *surface)
{
    if (tcg_enabled()) {                      /* <-- our configuration only */
        if (surface->width && surface->height) {
            surface->access_cb = mem_access_callback_insert(
                qemu_get_cpu(0), d->vram, surface->vram_addr, surface->size,
                &surface_access_callback, d);
```
called from `surface_put` (surface.c:685-693); the matching
`unregister_cpu_access_callback` (:595-601) is called from
`invalidate_surface` (:632-656).

`mem_access_callback_insert` (system/physmem.c:877-900) ends with:
```c
    async_safe_run_on_cpu(cpu, do_mem_access_callback_insert, ...);
    // FIXME: flush only applicable pages
    tlb_flush_all_cpus_synced(cpu);
```
`mem_access_callback_remove_by_ref` (:911-923) does the same two calls.

Unrolling that (all verified):
- `async_safe_run_on_cpu` (cpu-common.c:327-339) sets `wi->exclusive = true`;
  `process_queued_cpu_work` (cpu-common.c:352-388) does
  `bql_unlock(); start_exclusive(); fn(); end_exclusive(); bql_lock();`
  => the vCPU must **leave generated code** and enter an exclusive section.
- `tlb_flush_all_cpus_synced` -> `tlb_flush_by_mmuidx_all_cpus_synced(ALL_MMUIDX_BITS)`
  (cputlb.c:423-436) = a *second* `async_safe_run_on_cpu` -> a second forced exit
  + exclusive section.
- The work itself, `tlb_flush_by_mmuidx_async_work` (cputlb.c:369-407), wipes
  all 22 mmu-mode TLBs **and calls `tcg_flush_jmp_cache(cpu)` (cputlb.c:392)** --
  the entire TB jump cache.

**So one surface create = 2 vCPU exclusive-section stops + full softmmu TLB
wipe + full jump-cache wipe. One surface destroy = the same again.**

This matters because of two already-established facts in 00-FINDINGS:
- "targeted jump-cache invalidation" was worth -2.6%. This path blows the
  *whole* jump cache, repeatedly.
- `lookup_tb_ptr` is 14% and `qht_lookup` 8% of helper cycles. Both spike
  immediately after a jmp-cache flush.
- The "softmmu TLB miss rate 0.06%" is an average; a full flush forces a
  complete refill storm of software x86 page walks (`tlb_fill_align`).

**This is the single best structural candidate for a bursty, frame-correlated
cost:** surface churn is driven by what the game renders, so it is higher on
exactly the frames that draw more.

## F5 (VERIFIED, code) — live surfaces also force every guest store into them onto the slow path

`mem_access_callback_insert` registers a range that is folded into the
watchpoint flags at TLB fill time:
`accel/tcg/cputlb.c:1106-1112` (XBOX-only):
```c
    wp_flags = cpu_watchpoint_address_matches(cpu, addr_page, TARGET_PAGE_SIZE);
#ifdef XBOX
    wp_flags |= mem_access_callback_address_matches(cpu,
                                                    iotlb & TARGET_PAGE_MASK,
                                                    TARGET_PAGE_SIZE);
#endif
```
`mem_access_callback_address_matches` (system/physmem.c:856-868) is a **linear
walk of `cpu->mem_access_callbacks`** run on *every TLB fill*. The resulting
TLB_WATCHPOINT forces every guest access to those pages through
`mem_check_access_callback_vaddr` (cputlb.c:1507-1510, 1715-1716, 1908-1910),
another linear list walk, then `surface_access_callback` (vk/surface.c:538-579)
which takes `d->pgraph.lock` and, if the surface is `draw_dirty`, does
`qemu_event_wait(&r->downloads_complete)` -- **a genuine vCPU block waiting on
the GPU thread**.

NOTE, methodologically important: a `qemu_event_wait` block consumes **zero
cycles** and produces **zero perf samples**. It is invisible to cycle-based
profiling and to "libxemu %" breakdowns, but it is real frame time. This is
fully consistent with 00-FINDINGS section A ("no futex/mutex/wait symbols in
the vCPU thread's top 35") -- that observation does *not* rule out blocking,
it only rules out *spinning*.

## F6 (VERIFIED, code) — the vCPU and the GPU thread share two mutexes on the *guest's polling path*

- `pgraph_read` (hw/xbox/nv2a/pgraph/pgraph.c:48-53) takes `pg->lock` on
  **every** guest MMIO read of a PGRAPH register.
- `pgraph_write` (pgraph.c:89-97) takes `d->pfifo.lock` **and** `pg->lock`.
- `pfifo_read` (hw/xbox/nv2a/pfifo.c:43-47) takes `d->pfifo.lock` on every read;
  `pfifo_write` (pfifo.c:71-95) takes it and then calls `pfifo_kick`.

`pg->lock` is the same mutex the PGRAPH thread holds while running methods
(pfifo.c:136-153, 206-249). So a guest busy-poll of an NV2A register is in
direct mutex contention with the GPU thread's method processing.

Two consequences, and they look identical in the frame-time data:
1. **Block**: vCPU sleeps on the futex -> zero cycles, invisible to perf,
   real wall time.
2. **Spin**: the guest's own poll loop keeps executing x86 instructions ->
   **more guest instructions and more vCPU cycles on that frame.**

Mode 2 is the important one, because it manufactures exactly the signature
that was used to conclude "genuinely heavier game frames": heavy frames
execute ~1.6x more guest code, with instruction count scaling with cycles.
A guest spin-wait produces that signature perfectly. **Instruction count
cannot distinguish "the game did more" from "the game waited longer."**

Supporting evidence already in the tree (ui/xemu.c:1563-1566, a recorded
measurement, not a guess):
> "The guest busy-waits for vblank rather than halting -- measured 96-98%
>  vCPU utilisation at a locked 30 fps in two scenes whose work per frame
>  differs by 2.8x"

So the guest *is known to busy-wait*. The benchmark comment (ui/xemu.c:1578-1584)
assumes combat is immune because it is over the vblank budget, but that only
covers the *vblank* wait. It does not cover waits on PFIFO/PGRAPH, which are
driven by GPU-thread latency and therefore by how much the GPU thread has to do
-- which is higher on exactly the busy frames.

## Q1 sweep — other periodic/bursty candidates (verified, with verdicts)

| candidate | verdict |
|---|---|
| `tb_flush` (full) | **Unlikely.** Only from code-buffer overflow (accel/tcg/translate-all.c:312-322) and property-change refreshes (target/i386/tcg/translate.c:78, :91). Android sets `tb-size=256` MB (android/app/src/main/cpp/xemu_android.c:460-469) = ~480k TBs at the measured 533 B/TB. Not a steady-state per-frame event. Cheap check: `tb_ctx.tb_flush_count` delta over a bench run; if 0, dead. |
| `tlb_flush_all_cpus_synced` | **PRIME SUSPECT — see F4.** Driven by surface create/destroy, not by anything periodic. Counter already exists: `cpu->neg.tlb.c.full_flush_count` (accel/tcg/cputlb.c:394-396). Read it per frame. |
| `tlb_reset_dirty` | **Real but second-order — see F1/F2.** O(5808 entries / ~400 KB) per call, called per dirty page from `check_texture_dirty` (vk/texture.c:477-481) and per merged vertex range from `sync_vertex_ram_buffer` (vk/draw.c:1623-1635). Mostly on the GPU thread; its damage to the vCPU is cache/coherence, not cycles. |
| nv2a shader compilation | **Not the 35% tail.** glslang->SPIR-V + `vkCreateGraphicsPipelines` run on the PGRAPH thread (hw/xbox/nv2a/pgraph/vk/glsl.c:143-200, vk/gpuprops.c:206). Bursty and cache-warming: explains occasional huge spikes, not a stable 35% of frames. `NV2A_PROF_SHADER_GEN` already counts it per frame (hw/xbox/nv2a/debug.h:94). |
| surface create/destroy | **PRIME SUSPECT — see F4/F5.** `NV2A_PROF_SURF_CREATE` already counted (vk/surface.c:833). |
| qcow2 block I/O | **Off the vCPU.** QEMU block I/O runs in the AIO/iothread; Halo streams during level load, not during steady combat. Falsify with the existing frame-time ring + a blkstats delta. Low priority. |
| audio underruns | **Off the vCPU.** AAudio callback thread (hw/xbox/mcpx/apu/monitor.c). Would show as audio glitches, not frame cycles. Low priority. |

## Q3 — self-modifying code (VERIFIED)

Reachability of `tb_invalidate_phys_page`: only via `notdirty_write`
(accel/tcg/cputlb.c:1347-1349) -> `tb_invalidate_phys_range_fast`
(accel/tcg/tb-maint.c:1283), which fires when a guest store hits a page whose
`DIRTY_MEMORY_CODE` bit is clear, i.e. a page that has at least one TB on it
(`tb_page_add` -> `tlb_protect_code`, tb-maint.c:704-722).

**xemu diverges from upstream here and it is a pure amplifier.**
accel/tcg/tb-maint.c:1199-1219:
```c
    PAGE_FOR_EACH_TB(start, last, p, tb, n) {
#ifndef XBOX
        ... compute tb_start/tb_last ...
        if (!(tb_last < start || tb_start > last)) {
#else
        {
#endif
            ...
            tb_phys_invalidate__locked(tb);
        }
    }
```
Under `XBOX` the per-TB overlap test is **removed**: one guest store to a code
page invalidates **every TB on that 4 KB page**, not just the TBs that actually
contain the modified bytes. With the measured average TB of 6.5 guest
instructions, a 4 KB page holds on the order of a hundred TBs. One stray store
destroys all of them, and each one must be re-translated at ~533 emitted bytes.

Combined with F3: a page that holds both code and data is (a) permanently on
the store slow path and (b) nukes ~100 TBs per store. Halo decompressing or
relocating into a page adjacent to code would produce exactly a heavy frame.

Whether this actually fires in combat is **unverified**. It is cheap to
falsify: `tb_ctx.tb_flush_count` plus a counter in
`tb_invalidate_phys_page_range__locked` (tb-maint.c:1204), reported per frame
and split cheap/expensive. If the per-frame count is ~0 in combat, this dies
in one run.

## F7 (VERIFIED, code) — **the metric that killed hypothesis (b) is not a guest instruction count**

This is the most important thing in this document.

`accel/tcg/cpu-exec.c:1051-1055`, inside `cpu_exec_loop`:
```c
#if defined(__ANDROID__) || defined(ANDROID)
            xemu_tb_exec_count++;
            xemu_guest_insn_count += tb->icount;
#endif
            cpu_loop_exec_tb(cpu, tb, s.pc, &last_tb, &tb_exit);
```
The increment is in the **outer dispatch loop**. `cpu_loop_exec_tb` runs an
entire chain of directly-linked TBs and only returns when the chain breaks, so
**chained TBs never reach this counter**. It sums `tb->icount` of the *first*
TB of each chain run.

The tree says so itself (ui/xemu.c:1676-1682):
> "reentry_* count only loop re-entries -- chained TBs jump straight to one
>  another and never pass back through cpu_exec_loop -- so they are useless
>  as an absolute instruction rate but good for confirming two runs did the
>  same work."

and the bench output labels it `reentry-insn` (ui/xemu.c:1715-1719).

**So "expensive frames execute ~1.6x more guest code, and guest instruction
counts scale with the cycle ratio" is really "expensive frames break the TB
chain ~1.6x more often."** Those are very different claims. Chain breaks are
caused by:
- `cpu->exit_request` from **any** `qemu_cpu_kick` / `cpu_exit`
- guest interrupts (vblank, NV2A)
- page-spanning TBs (cpu-exec.c:1036-1041 sets `last_tb = NULL`, so no link)
- TB invalidation (`tb_jmp_unlink`)
- indirect branches with no link

...i.e. by a mix of guest behaviour **and emulator events**.

### The kill shot: the suspected mechanism directly increments the metric

`cpu-common.c:133-142`:
```c
static void queue_work_on_cpu(CPUState *cpu, struct qemu_work_item *wi)
{
    ...
    /* exit the inner loop and reach qemu_process_cpu_events_common().  */
    cpu_exit(cpu);
}
```
Every `async_safe_run_on_cpu` calls `queue_work_on_cpu` -> `cpu_exit(cpu)`.

Full verified chain:

```
GPU thread: surface_put() / invalidate_surface()        vk/surface.c:685,632
  -> register/unregister_cpu_access_callback            vk/surface.c:582,595  [tcg_enabled() only]
     -> mem_access_callback_insert / _remove_by_ref     physmem.c:877,911
        -> async_safe_run_on_cpu(...)                   -> cpu_exit(vcpu)   [chain break #1]
        -> tlb_flush_all_cpus_synced(vcpu)              cputlb.c:433
           -> async_safe_run_on_cpu(...)                -> cpu_exit(vcpu)   [chain break #2]
              -> tlb_flush_by_mmuidx_async_work         cputlb.c:369
                 - wipes all 22 mmu-mode TLBs
                 - tcg_flush_jmp_cache(cpu)             cputlb.c:392
vCPU: process_queued_cpu_work                            cpu-common.c:352
  -> bql_unlock(); start_exclusive(); fn(); end_exclusive(); bql_lock();
vCPU: re-enters cpu_exec_loop
  -> xemu_guest_insn_count += tb->icount                cpu-exec.c:1055  <-- METRIC GOES UP
```

**The emulator mechanism under suspicion is itself an input to the
measurement that was used to exonerate the emulator.** More surface churn ->
more cpu_exit -> more chain breaks -> higher `reentry-insn` -> reads as "the
game did more work", *plus* real cost (2 exclusive sections, TLB wipe,
jump-cache wipe, refill storm).

Note this is a **hypothesis about magnitude**, not yet a measurement: the code
path is verified, the per-frame rate in combat is **not**. See Q5.

## Q4 — frame-synchronous work on the vCPU that could be moved off or amortized

1. **The `async_safe_run_on_cpu` work from surface churn (F4/F7).** This is GPU
   work executed on the vCPU inside an exclusive section. Fixable three ways,
   cheapest first:
   - the FIXME at system/physmem.c:892 and :919 already says it:
     `tlb_flush_all_cpus_synced` -> a **ranged** flush over
     `[surface->vram_addr, +size)`. `tlb_flush_range_by_mmuidx_all_cpus_synced`
     exists (cputlb.c:814). A 640x480x4 surface is 300 pages, well under the
     `TARGET_PAGE_SIZE * TB_JMP_CACHE_SIZE` = 16 MB threshold at cputlb.c:742
     that would escalate back to a full `tcg_flush_jmp_cache`. So a ranged
     flush keeps the jump cache intact -- which is worth up to the 2.6% the
     targeted-jump-cache change already banked.
   - merge the two queued work items into one (the callback insert and the
     flush are queued separately: physmem.c:891-894) -> halves the chain breaks.
   - skip the churn entirely: `expire_old_surfaces` (vk/surface.c:906-919,
     called from `pgraph_vk_surface_update` at :1657 -- i.e. on *every* surface
     update) evicts surfaces unused for
     `max_surface_frame_time_delta = 5` frames (vk/surface.c:33). A surface used
     every 6th frame is destroyed and recreated forever. Raising that constant
     is a one-line A/B.
2. **`expire_old_surfaces` + `prune_invalid_surfaces` run per surface update,
   not per frame** (vk/surface.c:1657-1658). They walk the surface list each
   time. Cheap in itself, but each eviction costs the vCPU an F4 event.
3. **Guest MMIO polling of PGRAPH/PFIFO** (F6). Every poll takes a mutex shared
   with the GPU thread. `pfifo_read` (pfifo.c:43-47) takes `pfifo.lock` even for
   pure register reads that need no coherence with the FIFO thread. A
   lock-free read of `d->pfifo.regs[addr]` for the common poll registers is a
   contained change.
4. `process_vblank` already runs on its own thread (ui/xemu.c:1289-1330) and
   takes the BQL 60x/s (`xemu_main_loop_lock()` at :1328) -- competing with the
   vCPU for the BQL, but it is already off the vCPU. Low priority.

## Q5 — ONE run that separates "game did more" from "emulator cost more"

### The problem with the existing split
`bench_tick()` (ui/xemu.c:1611-1646) already buckets frames and already splits
cheap (ratio<=1.0) vs expensive (ratio>1.2), accumulating cycles and
`reentry-insn` for each. It prints `cycles x%.2f, work x%.2f` (ui/xemu.c:1715-1719).
Per F7, the `work` denominator is contaminated by emulator-caused chain breaks,
so the current split **cannot** answer the question. It needs a denominator
that only the game controls.

### The denominator: NV2A draw calls
`hw/xbox/nv2a/debug.h:71-134` already maintains per-frame counters with a
300-frame history (`frame_working` / `frame_history`, `NV2A_PROF_NUM_FRAMES 300`),
including `NV2A_PROF_BEGIN_ENDS`, `NV2A_PROF_DRAW_ARRAYS`,
`NV2A_PROF_TEX_UPLOAD`, `NV2A_PROF_SURF_CREATE`, `NV2A_PROF_SHADER_GEN`.
Draw calls are pure guest intent: the game issues them, the emulator cannot
manufacture them. **Draw calls are the honest measure of "how much work did the
game ask for on this frame".**

### The experiment
Extend the existing cheap/expensive accumulators in `bench_tick()` with these
per-frame deltas. Everything marked "exists" needs no new instrumentation.

| quantity | source | cost |
|---|---|---|
| vCPU cycles | `bench_read_cycles()` | exists |
| reentry-insn / TB dispatches | `xemu_guest_insn_count`, `xemu_tb_exec_count` | exists |
| **draw calls (denominator)** | `NV2A_PROF_BEGIN_ENDS` + `DRAW_ARRAYS` | exists |
| surface creates | `NV2A_PROF_SURF_CREATE` | exists |
| texture uploads / shader gens | `NV2A_PROF_TEX_UPLOAD`, `SHADER_GEN` | exists |
| **full TLB flushes** | `cpu->neg.tlb.c.full_flush_count` (cputlb.c:394-396) | exists |
| TLB refills | counter in `tlb_fill_align` | 1 line |
| `notdirty_write` calls | counter at cputlb.c:1340 | 1 line |
| `tlb_reset_dirty` calls | counter at cputlb.c:917 | 1 line |
| TB invalidations | counter in `do_tb_phys_invalidate` | 1 line |
| vCPU NV2A MMIO reads | counter in `pgraph_read`/`pfifo_read` | 1 line |

Report every row as **expensive/cheap ratio**, and again **normalized per draw
call**.

### Decision rule (this is what makes it one run)

- **draw-call ratio ~= 1.6** (matching the cycle ratio)
  -> the game genuinely does ~1.6x more work on those frames. Hypothesis (a).
  Close the question; it really is the game.
- **draw-call ratio ~= 1.0 while cycle ratio ~= 1.6**
  -> hypothesis (b), and whichever counter *also* rises ~1.6x names the
  mechanism:
  - `full_flush_count` / `SURF_CREATE` -> **F4** (surface churn: TLB + jmp-cache wipes)
  - MMIO reads -> **F6** (guest spin-polling a contended NV2A register)
  - `notdirty_write` / `tlb_reset_dirty` -> **F1/F3** (NV2A dirty tracking)
  - TB invalidations -> **Q3** (SMC full-page wipe)
- **intermediate** (e.g. draws x1.25, cycles x1.6) -> both are real, and the
  residual is the emulator's share. Still actionable.

### Three cheaper kills to run FIRST (each is one measurement)

1. **Print `cpu->neg.tlb.c.full_flush_count` per frame.** Existing counter,
   zero new code beyond reading it. If it is < ~2/frame in combat, **F4 is dead
   immediately** and so is the top lead. If it is 10+/frame, F4 is live and
   everything downstream follows. *Do this one first.*
2. **Run the deterministic save-state benchmark with `debug.xemu.nochain`.**
   With chaining off, `xemu_guest_insn_count` becomes an exact guest
   instruction count. If the expensive/cheap ratio collapses from ~1.6x toward
   ~1.0x, **F7 is confirmed and the "genuinely heavier game frames" conclusion
   in 00-FINDINGS is void.** If it stays ~1.6x, the game really is heavier and
   F7 is a non-issue. Requires the deterministic benchmark so the frame
   sequence is identical between the two configs.
3. **Flip `max_surface_frame_time_delta` from 5 to 60** (one constant,
   hw/xbox/nv2a/pgraph/vk/surface.c:33) and re-run. This suppresses
   surface expiry churn without touching anything else. If the >120% bucket
   shrinks, F4 is real and the production fix (ranged flush per the existing
   FIXME at system/physmem.c:892) is worth building.

All three are killable in one run each, and #1 and #3 need no new counters.

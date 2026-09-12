# Host memory system — STATUS: complete (done directly, not by an agent:
# the assigned agent was killed by a session limit before writing anything)

## Conclusions, ranked

### 1. Host TLB pressure is REAL and LARGE — but there is no lever on this device
**Measured 2026-09-12** (new counters, `PERF_TYPE_HW_CACHE`):

| per frame | count |
|---|---|
| host dTLB misses | **334k-364k** |
| host iTLB misses | 86k-120k |
| L1D read misses | 203k-232k |
| generic cache-miss (earlier) | ~213k |

**dTLB refills exceed total cache misses.** Mechanism: guest RAM is 64 MB over
16,384 host 4 KB pages; the Cortex-X3 L2 TLB (2048 entries) covers ~8 MB, so
the guest working set cannot be covered and cold pages cost a walk.

**Caveat, stated up front:** on ARM this counter is `L1D_TLB_REFILL`, which
includes refills satisfied cheaply by the L2 TLB. 334k is an UPPER BOUND on
expensive page walks, not a count of them.

**Why there is no lever:** 2 MB pages would turn 16,384 entries into 32.
- Transparent huge pages are **`[never]`** on this device
  (`/sys/kernel/mm/transparent_hugepage/enabled`) and writing that file is
  **Permission denied** without root.
- QEMU already calls `qemu_madvise(..., QEMU_MADV_HUGEPAGE)` on every RAM
  block (`system/physmem.c:2421`), so the request is made and the kernel
  ignores it under `never`.
- hugetlbfs needs root and kernel config; not available to an Android app.

**If the device were ever rooted**, `echo madvise > .../enabled` would make
QEMU's existing MADV_HUGEPAGE call effective with no code change. That is the
only known path, and it is the user's call, not a code change.

### 2. DEAD: shrinking the JIT translation buffer for iTLB locality
Theory: 256 MB of scattered 533-byte blocks blows iTLB coverage.
**Measured: tb-size 256 vs 32 MB = 32.55 vs 32.55 ms/frame, identical**, and
iTLB misses went UP with the smaller buffer (86,510 -> 120,370). Made
switchable via `debug.xemu.tb_size` (kept, default 256).

### 3. Guest RAM allocation path (verified)
`hw/xbox/xbox.c:194` `memory_region_init_ram(ram, NULL, "xbox.ram", ...)` ->
standard QEMU anonymous allocation, THP-advised at `system/physmem.c:2421`.
Nothing in xemu prevents huge pages; the device policy does.

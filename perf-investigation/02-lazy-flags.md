# 02 — Lazy condition-flag emulation: true total cost, and the NZCV opportunity

STATUS: complete
Started 2026-09-11. Exploration only — no code modified, no device touched.

## CONCLUSIONS, ranked

**The headline: the "native ARM flags" lead is mostly wrong, but the flag
question underneath it is real and points at lead #2, not at FEAT_FlagM.**

**C1. The FP-compare/`AXFLAG` item in 00-FINDINGS C.1 is factually wrong.**
`AXFLAG` is `z = Z OR V; c = C AND NOT V; N=0; V=0`. Worked through against the
four FCMP outcomes, the best `AXFLAG` sequence for the x86 `com` result is
**7 instructions -- exactly what is emitted today** (`tcg-target.c.inc:3300-3311`).
It replaces one `ORR` with itself. Expected win: zero. (Q4.B)

**C2. The hot integer pattern is already at the ARM floor.** `cmp`/`test` + `jcc`
inside one TB compiles to `CMP` + `B.cc`, or `CBZ`/`TBNZ` for the tested-bit and
compare-zero cases (`tcg-target.c.inc:1555-1620`, plus the `CCPrepare` fusion at
`translate.c:1290-1337`). That is the same code FEX or hand-written ARM would
produce. **There is no NZCV win on the dominant pattern.** (Q4.C)

**C3. A FEX-style "flags live in NZCV" design is not portable to this build.**
TCG's IR has no flag-register concept, and the softmmu TLB compare emits
`CMP`/`B.NE` (`tcg-target.c.inc:1849-1853`) on **every guest memory access** --
roughly one per 2-3 guest instructions -- so NZCV cannot survive between a flag
producer and its consumer. FEX and Rosetta 2 are user-mode with no such check.
Removing it is fastmem, already in the dead-ends table. (Q4.D)

**C4. The real leaks are structural, and both are consequences of the 6.5-guest-
instruction TB:**
* **L1** -- `cc_op` is reset to `CC_OP_DYNAMIC` at every TB entry
  (`translate.c:4501`), and from DYNAMIC **6 of the 8 Jcc condition groups** go to
  the full `helper_cc_compute_all` (only JCC_B and JCC_Z get cheaper helpers).
* **L2** -- the last flag producer in every TB always pays ~3 env stores, because
  at TB end globals are `TS_DEAD|TS_MEM` (`tcg/tcg.c:3930-3948`) so the ops are
  not removed (`:4382`) -- **even when nothing ever reads them.**
Inside a TB, QEMU's dead-flag elimination already works properly and removes both
the computation and the store (`translate.c:540-568` + `tcg/tcg.c:4261, 4379-4391`).
**Bigger TBs fix both leaks. That is lead #2, not lead #1.** (Q2)

**C5. Secondary, cheap, and worth doing on its own:** `gen_prepare_cc`'s fast
paths exist only for `CC_OP_SUB*` and `CC_OP_LOGIC*`, so `add`/`inc`/`dec`/`shl`
followed by an *ordering* branch always calls the helper (leak L3); and
`helper_cc_compute_all` always computes PF and AF (`helper-tcg.h:92`) when the
caller wanted four flags (leak L4). ~200 lines of frontend-only work, plausibly
**1.5-3% of vCPU**. (Q5-ii)

**C6. Total cost of x86 flag emulation: I estimate ~7-9% of vCPU** -- 4.3%
measured in `cc_compute_all`, ~1.7% inferred in per-TB env stores, the rest in the
consume-side `setcond`/`ext` ops and register pressure. **The lower half is
inference, not measurement.** Q5 gives ~70 lines of counters (M0-M3) that turn it
into a number in one benchmark run, including a direct answer to "what fraction of
computed flags is ever consumed", with the decision rule written down in advance.

**Order of work: M0 (free) -> M1 + M3 (~55 lines, one run) -> decide.** Do not
build anything before M3; its result routes the work either to `gen_prepare_cc`
(~80 lines) or to the superblock project, and those are very different budgets.

---

## Checklist
- [x] Q1. How the lazy-flag scheme works (translate.c, CCOp enum, gen_compute_eflags/gen_cc_op) — with file:line
- [x] Q2. Where flag computation IS elided, and where it FAILS to be
- [x] Q3. TCG-op accounting: flag bookkeeping vs real work per ALU insn; cross-check vs 82B/20.5 insns per guest insn, 6.5-insn TB
- [x] Q4. AArch64 NZCV mapping: how far can it go, what breaks (AF/PF, SUB carry sense, no IR flag concept), prior art (Rosetta2 / FEX / MobiSys'25)
- [x] Q5. Realistic win+work estimates (i) FP-compare fix (ii) in-TB NZCV (iii) ambitious; and a CHEAP pre-build measurement that sizes the prize

---

## Q1 — how the lazy-flag scheme actually works (VERIFIED, code)

### The state
Four TCG **globals** (env-backed, so they are register-allocated inside a TB and
spilled to `CPUX86State` at every boundary), created in
`target/i386/tcg/translate.c:4415-4422`:

| global | env field | meaning |
|---|---|---|
| `cpu_cc_op`  | `env->cc_op`  | which `CCOp` recipe recreates EFLAGS |
| `cpu_cc_dst` | `env->cc_dst` | usually the ALU result |
| `cpu_cc_src` | `env->cc_src` | usually one operand (or the whole EFLAGS word) |
| `cpu_cc_src2`| `env->cc_src2`| third input (ADC/SBB/ADOX) |

plus `s->cc_srcT` (`translate.c:328`), a **plain TCG temp, not a global** — the
pre-subtraction LHS kept so `cmp`+`jbe/jl/jle` can be done as a direct host
compare. Because it is a temp it does not survive a TB, which is why the
CC_OP_SUB fast path only exists inside one TB.

`CCOp` enum: `target/i386/cpu.h:1443-1524`. ~60 values = {recipe} x {operand
size B/W/L/Q}. `CC_OP_EFLAGS=0` means "already materialised, in `cc_src`";
`CC_OP_DYNAMIC` (last) means "not known at translation time, read `env->cc_op`".
`cc_op_size()` at `cpu.h:1529` relies on the enum being 4-aligned per size.

### The write side (producing flags)
A flag-setting insn does **not** compute flags. It records inputs. New decoder:
`prepare_update1_cc/2_cc/3_cc` (`emit.c.inc:392-416`) just stash pointers into
`decode->cc_dst/cc_src/cc_src2/cc_op`; e.g. `gen_ADD` (`emit.c.inc:1259-1270`)
is literally `add T0,T0,T1` + `prepare_update2_cc(..., CC_OP_ADDB+ot)`.
The actual IR is emitted once, after the memory writeback, at
`decode-new.c.inc:2879-2893`:

```c
if (decode.cc_dst)  tcg_gen_mov_tl(cpu_cc_dst,  decode.cc_dst);
if (decode.cc_src)  tcg_gen_mov_tl(cpu_cc_src,  decode.cc_src);
if (decode.cc_src2) tcg_gen_mov_tl(cpu_cc_src2, decode.cc_src2);
set_cc_op(s, decode.cc_op);
```

`cc_op` itself is **not** written here. `set_cc_op` (`translate.c:570`) only
marks `s->cc_op_dirty`; the `movi cc_op, <const>` is deferred to
`gen_update_cc_op` (`translate.c:584-590`), which runs only where the value can
actually be observed: before a helper call, before a branch/TB exit, at
`i386_tr_tb_stop` (`translate.c:4634`). That deferral is the single best part of
the scheme — most `cc_op` writes collapse to one per TB.

`SUB`/`CMP` additionally emit `mov cc_srcT, T0` before the subtract
(`emit.c.inc:4137`), i.e. a 3rd bookkeeping mov.

### The read side (materialising flags)
* `gen_mov_eflags` (`translate.c:1056-1093`) — the general path. If
  `cc_op==CC_OP_EFLAGS` it is a no-op mov. Otherwise it calls
  **`gen_helper_cc_compute_all(reg, dst, src1, src2, cc_op)`**
  (`translate.c:1092`) — the helper at `cc_helper.c:76`, a ~60-way `switch`.
  Note it passes `tcg_constant_i32(s->cc_op)` when the op is statically known
  (`translate.c:1087`): **we call a runtime switch even when the answer was a
  compile-time constant.** TCG cannot constant-fold into a helper.
* `gen_compute_eflags` (`translate.c:1096-1100`) = `gen_mov_eflags` into
  `cc_src` + `set_cc_op(CC_OP_EFLAGS)`, i.e. it also *memoises*: the next reader
  in the same TB is free.
* Single-flag fast paths that avoid the helper entirely:
  `gen_prepare_eflags_c/p/s/o/z` (`translate.c:1130-1277`) return a `CCPrepare`
  {cond, reg, reg2, imm} describing a host compare instead of a value.
  `gen_prepare_cc` (`translate.c:1281-1385`) turns a Jcc/SETcc/CMOVcc condition
  into that `CCPrepare`, and `gen_jcc_noeob`/`gen_setcc` emit one
  `brcond`/`setcond`.
* The two smaller helpers: `helper_cc_compute_c` (`cc_helper.c:212`) and
  `helper_cc_compute_nz` (`cc_helper.c:62`), used only from the `CC_OP_DYNAMIC`
  default arms of `gen_prepare_eflags_c`/`_z`.

### Why the helper is expensive beyond the call itself
`compute_all_cout` (`cc_helper_template.h.inc:47-66`) computes PF, ZF, SF from the
result and AF/CF/OF from a carry-out vector, reached through
`helper_cc_compute_all`'s ~60-way switch. **It always computes all six flags,
including PF (`compute_pf`, `helper-tcg.h:92`, a `parity8`) and AF, even though
almost every caller wants only Z/S/C/O.**

CORRECTION to an assumption I started with: the three cc helpers are declared
`TCG_CALL_NO_RWG_SE` (`target/i386/helper.h:1-3`), so they do **not** force a
global spill wave, and a call whose result is unused is dead-code-eliminated.
On AArch64 the globals live in X20-X28 (`tcg-target.c.inc:47-50`), which the call
does not clobber (`:3878-3889`). So the call cost is the call/return, the switch,
the six-flag computation, and register-allocation pressure -- not a spill storm.

## Q2 — where flags ARE elided, and where the scheme leaks (VERIFIED, code)

### What already works (and works well)
1. **Dead-flag detection.** `cc_op_live_[]` (`translate.c:504-522`) says which of
   dst/src/src2/srcT each `CCOp` needs. `set_cc_op_1` (`translate.c:540-568`)
   emits `tcg_gen_discard_tl` for every component the *new* cc_op does not need.
2. **TCG then really deletes the work.** `discard` sets the temp state to plain
   `TS_DEAD` (`tcg/tcg.c:4261`), and the liveness pass removes any op whose
   outputs are all `TS_DEAD` (`tcg/tcg.c:4379-4391`). So an overwritten
   `mov cc_dst, …` and the ALU op feeding it both vanish. **Flags computed and
   then overwritten inside the same TB are already free.**
3. **`cc_op` write deferral** (`gen_update_cc_op`) — typically 1 `movi` per TB
   instead of 1 per ALU insn.
4. **Condition fusion**: for `CC_OP_SUB*`, `gen_prepare_cc` (`translate.c:1290-1315`)
   turns `cmp`+`jbe/jl/jle/jg/jge/ja` into a single host compare against
   `cc_srcT`. For `CC_OP_LOGIC*` (`:1318-1337`) `test`+`jbe/jl/jle` are rewritten
   to jz/js/signed-<=0. This is why the classic `cmp; jcc` costs nothing.
5. **SHR/RCR and SHL/RCL chains** dodge `cc_compute_all` specially
   (`emit.c.inc:3344-3369`).

### Where it leaks — ranked by my estimate of how much real code hits it

**L1. `cc_op` is reset to `CC_OP_DYNAMIC` at every TB entry**
(`translate.c:4501`). With a **6.5-guest-instruction average TB** this happens
every 6.5 instructions. From `CC_OP_DYNAMIC`, `gen_prepare_cc` sends **6 of the
8 condition groups** to the full helper: JCC_O (`:1247`), JCC_BE (`:1357`),
JCC_S (`:1219`), JCC_P (`:1209`), JCC_L (`:1368`), JCC_LE (`:1378`) all call
`gen_compute_eflags` → `helper_cc_compute_all`. Only JCC_B and JCC_Z get the
cheaper `cc_compute_c`/`cc_compute_nz`. Any `setcc`, `cmovcc`, `adc`, `sbb`,
`jcc`, `rcl/rcr`, `pushf`, `lahf`, `sahf` that is the first flag *reader* in its
TB and whose producer was in the previous TB pays this.

**L2. The last flag producer in every TB always pays its env stores.**
At TB end globals are `TS_DEAD|TS_MEM` (`la_bb_end`, `tcg/tcg.c:3930-3948`) —
dead, but the memory copy must be current, so the ops are *not* removed
(`tcg/tcg.c:4382`). So `cc_dst`, `cc_src` (+`cc_src2`) and the `movi cc_op` are
stored at every TB exit **even when no instruction anywhere ever reads them.**
Roughly 3 host stores per TB / 6.5 guest insns. The translator cannot prove
they are dead because it cannot see the successor TB.

**L3. The fast condition paths only exist for `CC_OP_SUB*` and `CC_OP_LOGIC*`.**
`gen_prepare_cc`'s `slow_jcc` catches `CC_OP_ADD*`, `CC_OP_INC*/DEC*`,
`CC_OP_SHL*/SAR*`, `CC_OP_MUL*` for JCC_BE/L/LE/O → full helper. So
`add`/`inc`/`dec`/`shl` followed by a signed or unsigned *ordering* branch (as
opposed to eq/ne/sign) always materialises all six flags.

**L4. Statically-known `cc_op` still goes through a runtime switch.**
`translate.c:1087` passes a constant; `helper_cc_compute_all` still dispatches on
it at run time and computes **all six flags including AF and PF** when the
consumer wanted one bit. There is no "inline the known recipe" path at all.

**L5. `cc_srcT` is a temp, not a global** (`translate.c:328`), so the `cmp`
fast path is dead the moment the `cmp` and the `jcc` land in different TBs.

**L6. Flag globals inflate register pressure.** `cc_dst`/`cc_src`/`cc_src2`/
`cc_op` are 4 of the ~13 i386 globals competing for host registers. Their cost
shows up as *extra spills of other values* and will therefore be **invisible to
any per-op attribution** — a caveat for the measurement in Q5.

## Q3 — TCG-op accounting for a typical ALU instruction (VERIFIED by reading the generators; the host-instruction mapping is INFERRED)

`add eax, ebx` on a 32-bit guest, following the code path above:

| TCG op | purpose |
|---|---|
| `mov T0, regs[EAX]` | operand load (`gen_load`, `emit.c.inc:242`) |
| `mov T1, regs[EBX]` | operand load |
| `add T0, T0, T1` | **the actual work** |
| `mov regs[EAX], T0` | writeback (`gen_writeback`, `emit.c.inc:337`) |
| `mov cc_src, T1` | flag bookkeeping (`decode-new.c.inc:2884`) |
| `mov cc_dst, T0` | flag bookkeeping (`decode-new.c.inc:2881`) |
| (`movi cc_op, CC_OP_ADDL`) | flag bookkeeping, deferred, ~1 per TB |

**2 of 6 ops (33%) are flag bookkeeping; 1 of 6 is the arithmetic.**
`cmp`/`sub` is worse — 3 of 7 (`mov cc_srcT, T0` as well, `emit.c.inc:4137`).
`inc`/`dec` is worse still: `prepare_update_cc_incdec` (`emit.c.inc:404-409`)
calls `gen_compute_eflags_c` first, which from a non-trivial `cc_op` is a
`setcond` or a whole `cc_compute_c` helper call — `inc` can cost a helper call.

Cross-check against the measured totals (82 bytes / **20.5 host instructions per
guest instruction**, **133 host instructions per 533-byte TB**):
* The two flag `mov`s usually cost **0 host instructions each** — `tcg_reg_alloc_mov`
  aliases the destination global onto the source's host register. So the naive
  "33% of ops" does *not* become 33% of instructions.
* What it does cost is a **deferred store**: ~3 stores per TB (L2) = **~2.3% of
  the 133 host instructions per TB**, against a measured `env stores` bucket of
  **3.3% of JIT cycles**. So flags plausibly account for **over half of the env-store
  bucket** — call it ~2% of JIT cycles ≈ 1.7% of vCPU. (INFERRED, not measured.)
* Plus register pressure (L6), which lands in other buckets.
* Plus the consume side (`setcond`/`ext`/`brcond` from `CCPrepare`) inside the
  14.2% `alu/other` bucket. Unquantified.
* Plus `helper_cc_compute_all` itself at **4.3% of vCPU** (measured), and the
  global spill wave each of those calls forces.

**Working total for x86 flag emulation: ~7-9% of vCPU, of which only 4.3% is
currently visible.** This is an estimate with a soft lower half; Q5 proposes how
to pin it down for about 30 lines of instrumentation.

---

## Q4 — how far can x86 flags be mapped onto host NZCV here?

### A. The AArch64 backend gap is real but smaller than it looks (VERIFIED)
Re-confirmed: `RMIF`, `CFINV`, `AXFLAG`, `XAFLAG`, `SETF8`, `SETF16` all grep to
**0** in `tcg/aarch64/tcg-target.c.inc`; the only host feature detected is LSE2
(`:1791`). That part of the 00-FINDINGS entry stands.

### B. **The FP-compare claim in 00-FINDINGS is WRONG. AXFLAG does not save five instructions; it saves at most one.** (VERIFIED)
AXFLAG's exact semantics (Arm ARM, FEAT_FlagM2):
`z = Z OR V; c = C AND NOT V; N=0; Z=z; C=c; V=0`.
After `FCMP` the four outcomes are (N,Z,C,V) = less 1000, equal 0110,
greater 0010, unordered 0011. Applying AXFLAG gives (Z,C) =
less (0,0), equal (1,1), greater (0,1), unordered (1,0). So
**x86 ZF = ARM Z, x86 CF = NOT ARM C, x86 PF = Z AND NOT C** -- PF is still not
a single AArch64 condition, so we still need three `CSET`s.

Current sequence (`tcg-target.c.inc:3300-3311`): FCMP + 3 CSET + 3 ORR = **7**.
Best AXFLAG sequence: FCMP + CSET vs + AXFLAG + CSET cc + CSET eq + 2 ORR = **7**.
AXFLAG replaces one ORR with itself. **Net zero.** I worked through the CSEL-from-
constants and MRS-NZCV-plus-table alternatives too; both are longer.

What *is* wasteful on that path is the glue, not the flag synthesis:
`gen_fcom` (`target/i386/ops_fpu.h:187-219`) then does `and 0x45` (redundant --
`com` only ever sets bits 0/2/6), `shl 8`, `ld16u fpus`, `and ~0x4500`, `or`,
`st16`. The backend could emit the `com` result pre-shifted by 8 (ORR with
LSL #8/#10/#14) and the `and 0x45` could be dropped: **~2 instructions of ~13**.
Real but tiny. **Recommendation: do not do this, and fix the FINDINGS entry.**

### C. The hot integer patterns are ALREADY optimal (VERIFIED) -- this is the big one
`tgen_brcond` (`tcg-target.c.inc:1555-1561`) emits `CMP` + `B.cc`.
`tgen_brcondi` (`:1564-1620`) turns `x==0`/`x!=0` into `CBZ/CBNZ` and single-bit
tests into `TBZ/TBNZ` -- **one instruction**.
Combined with the `CCPrepare` fusion in `gen_prepare_cc`, a guest
`cmp eax,ebx ; jl L` inside one TB compiles to exactly

```
CMP  w_eax, w_ebx
B.LT L
```

which is what the original x86 did, what FEX would emit, and what hand-written
ARM would be. **There is no NZCV win available on the pattern that dominates.**
Likewise `test eax,eax ; jz` becomes `CBZ`. The lazy scheme's fast paths already
reach the floor for `CC_OP_SUB*` and `CC_OP_LOGIC*`.

### D. Why a FEX-style "flags live in NZCV" design cannot be ported to TCG here
1. **TCG's IR has no flag-register concept.** Every `brcond`/`setcond` re-emits
   its own `CMP`; nothing can express "NZCV currently holds the result of the
   previous op". Adding one means a new IR notion of a clobberable implicit
   register, plus every backend and the register allocator learning it.
2. **The softmmu load/store sequence clobbers NZCV on every guest memory access.**
   `prepare_host_addr` emits `CMP`/`B.NE` for the TLB compare
   (`tcg-target.c.inc:1849-1853`) and `ANDS`/`B.NE` for alignment (`:2022-2026`).
   Guest memory ops are ~18% of emitted instructions and 71% of JIT cycles, i.e.
   roughly one per 2-3 guest instructions. **Flags physically cannot stay resident
   in NZCV across them**, and fastmem -- the only way to remove that sequence --
   is already in the dead-ends table.
   FEX and Rosetta 2 both run *user mode* with a flat host mapping and no TLB
   check between the flag producer and its consumer. That is the whole difference.
3. **AF and PF have no ARM equivalent.** Rosetta 2 gets them from an undocumented
   Apple extension that puts PF/AF in NZCV bits 26/27, and falls back to software
   inside Linux VMs (dougallj). FEX "dedicated two registers to these flags".
   Neither option is a small change here.
4. The SUB carry-sense inversion (x86 borrow vs ARM carry) is a non-issue for us:
   QEMU never materialises C from NZCV, it computes `cc_srcT <u cc_src`, which the
   backend turns into `CMP` + `CSET lo` -- already correct and already minimal.
   `CFINV` has nothing to fix.

### E. Prior art, and what it does and does not transfer
* **Rosetta 2** (dougallj, "Why is Rosetta 2 fast?"): CFINV canonicalises inverted
  carry; RMIF moves an arbitrary register bit into an arbitrary flag (used for
  fixed shifts); SETF8/SETF16 emulate narrow flag-setting; AXFLAG/XAFLAG for FP.
  Plus an "unused flags" optimisation. **AOT, user mode, private silicon.**
* **FEX-Emu 2312**: maps SF/ZF/CF/OF onto N/Z/C/V natively and dedicates two host
  registers to PF and AF; reports "17.6%" on Geekbench, "up to 60%" on bytemark,
  ~2x FPS on CPU-bound games. **User mode, own IR with flag liveness, own RA.**
  The headline numbers are the closest thing to an upper bound in the literature
  -- and they are measured against a baseline that had *no* lazy-flag elision at
  all, which QEMU does have.
* **MobiSys'25, "ARMing x86 Games: Accelerating Binary Translation Using
  Software-Only Validated Flag Speculation"** (Yen, Wang, Huang, ... Qi;
  doi:10.1145/3711875.3729163). Right workload class, right hardware class,
  explicitly *software-only* (no FlagM dependency) -- speculate the flag
  computation away and validate. **I could not retrieve the paper body (ACM 403),
  so I have no numbers and no mechanism detail.** Worth buying/obtaining before
  committing engineering time; it is the single most relevant reference.

### F. Verdict on Q4
**The "use native ARM flags" framing is mostly a dead end for this codebase.** The
common paths already compile to the ideal ARM instructions; the paths that do not
cannot be fixed by FlagM instructions, because TCG has no way to keep flags in
NZCV across a softmmu access. The remaining opportunity is **not** NZCV -- it is
(a) not calling a six-flag helper when four flags were wanted, and (b) not
resetting `cc_op` to DYNAMIC every 6.5 instructions.

---

## Q5 — what to build, what it is worth, and (first) how to size it for ~40 lines

### Where the existing tooling is
The per-TCG-op cycle attribution lives on branch **`android-codegen-measure`**, not
on the branch currently checked out (`android-surface-churn`):
* `tcg/tcg.c` `xm_*` recorder, per-TB `(end_off, opc)` ranges, gated on
  `debug.xemu.cycle_map` (commit `5e2f76d446`); the record point is inside the
  `QTAILQ_FOREACH(op, &s->ops, link)` codegen loop at `tcg/tcg.c:7364-7375`
  **with `op` in scope**, so operands are inspectable there.
* `android/tools/tcg-cycle-attrib.py` correlates it with `simpleperf dump`.
* `debug.xemu.nochain` (commit `320819dc62`) forces every TB exit back through
  `cpu_exec_loop`, where `xemu_guest_insn_count += tb->icount` already lives
  (`accel/tcg/cpu-exec.c:1055`). That is the exact dynamic-weighting hook.
* Deterministic benchmark: `adb shell am broadcast -a com.xemu.action.BENCHMARK
  --ei frames 500`, reports vCPU ms/frame, repeatable to ~1%.

### M0 — FREE. Read two numbers out of the attribution run you already have.
No code at all. In the existing 300-frame `tcg-cycle-attrib.py` per-opcode table:
* the `com_f32`/`com_f64` line **is the entire size of opportunity (i)**. If it is
  under ~0.3% of JIT cycles, close out the FP-compare idea permanently.
* the `mov`, `st`, `call` lines bound the inline bookkeeping from above.
Do this before writing anything.

### M1 — ~15 lines. Dynamic `CCOp` histogram inside the helper. **Decides which fix.**
In `target/i386/tcg/cc_helper.c:76`, gated on a `debug.xemu.cc_hist` property,
`g_cc_hist[op]++` at the top of `helper_cc_compute_all` (and the same in
`cc_compute_c` / `cc_compute_nz`), dumped next to `tbmap.bin` at benchmark end.
Answers, per frame:
* **calls/frame** -- turns 4.3% of vCPU into an absolute rate;
* **how many are `CC_OP_DYNAMIC`** (leak L1: short TBs) **vs `CC_OP_ADD*/INC*/
  DEC*/SHL*`** (leak L3: missing fast paths) **vs everything else**.
These two buckets need completely different fixes, and nothing else distinguishes
them. Cost of the instrumentation: one increment inside a function already at
4.3%, i.e. invisible; and it is behind a property.

### M2 — ~15 lines. Tag the flag traffic in the cycle map. **Sizes the hidden cost.**
At `tcg/tcg.c:7364-7375`, before recording, walk
`op->args[0 .. nb_oargs+nb_iargs)`; if any `arg_temp()` has
`kind == TEMP_GLOBAL` and a name in {`cc_op`,`cc_dst`,`cc_src`,`cc_src2`}, set
bit 15 of the recorded `opc` (`NB_OPS` is far below 32768). Split that bit out in
`tcg-cycle-attrib.py`. Output: **"flag bookkeeping is X% of JIT cycles"** --
directly converting the part of "env stores 3.3%" and "alu/other 14.2%" that the
task was set to find.
Caveat to record with the result: this **undercounts**, because leak L6 (four
extra globals inflating register pressure) surfaces as spills attributed to
*other* ops. Treat the number as a floor.

### M3 — ~40 lines. **The measurement that actually sizes the prize: what fraction of computed flags is ever consumed.**
This is the one I would insist on before any implementation.

1. In `DisasContext` add four `uint16_t` counters. In `set_cc_op_1`
   (`translate.c:540`) you already know, at the moment the flags are replaced,
   exactly what was discarded -- increment `flag_writes` on every producer and
   `flag_writes_dead` when the whole previous `cc_op_live()` set is discarded with
   no intervening read. Track "intervening read" with a single bool cleared in
   `set_cc_op_1` and set in `gen_mov_eflags`/`gen_prepare_eflags_*`/`gen_prepare_cc`.
   Increment `flag_reads` there too. At `i386_tr_tb_stop` record whether flags
   were still dirty at exit (`flags_live_out`).
2. Stash the four counters in the `TranslationBlock` (or a side array indexed the
   same way `tb->tc.size` is read).
3. Accumulate them in `cpu_exec_loop` beside `xemu_guest_insn_count`
   (`accel/tcg/cpu-exec.c:1055`), run with `debug.xemu.nochain=1` so every exit
   passes through and the totals are exact. Print in the benchmark summary.

**Decision rule, fixed in advance:**

| result | meaning | action |
|---|---|---|
| consumed/produced **> 40%** | lazy flags is already doing its job; the flags really are needed | abandon this whole line; the 4.3% is irreducible work |
| consumed/produced **< 20%** AND most producers are `flags_live_out` | the waste is leak **L2** -- stores emitted because the translator cannot see the successor | the fix is **superblocks (lead #2)**, not flags. Fold into that. |
| `CC_OP_DYNAMIC` dominates M1 | the waste is leak **L1** -- `cc_op` resets every 6.5 insns | cross-TB `cc_op` propagation, or again superblocks |
| `CC_OP_ADD*/INC*/SHL*` dominates M1 | leak **L3** -- missing `CCPrepare` fast paths | ~80 lines in `gen_prepare_cc`, do it immediately |

Total instrumentation for M0-M3: **~70 lines, all behind debug properties, all
counters only, zero correctness risk, one benchmark run.**

### Estimates of win and work (all contingent on the above)

**(i) The FP-compare / AXFLAG fix -- DO NOT DO IT.**
Win ~0 (Q4.B: AXFLAG is a wash on that sequence). The only real saving there is
~2 instructions of ~13 by dropping the redundant `and 0x45` and pre-shifting the
`com` result, and only on x87 FCOM, whose share M0 will show is small.
Work: 40 lines + on-device semantic re-validation via
`scripts/aarch64-fp-com-semantics.c`. **Negative expected value.**

**(ii) "NZCV mapping within a TB" -- already done, by accident.**
`cmp`/`test` + `jcc` in one TB already compiles to `CMP`+`B.cc` / `CBZ` / `TBNZ`
(Q4.C). There is nothing left to win on the hot pattern. What is left, and is
worth doing, is *frontend-only* and has nothing to do with ARM flags:
* **(ii-a)** Extend `gen_prepare_cc`'s fast paths from `CC_OP_SUB*`/`LOGIC*` to
  `CC_OP_ADD*`, `INC*`, `DEC*`, `SHL*`, `SAR*` for JCC_BE/L/LE/O. ~80 lines in
  `translate.c`, no IR or backend change, mechanically verifiable against
  `cc_helper_template.h.inc`. Win = whatever share M1 assigns to those `CCOp`s.
* **(ii-b)** A `Z/S/C/O`-only materialisation that skips PF and AF and does not
  memoise `CC_OP_EFLAGS` (it must not -- a later `PUSHF` would read a lie).
  Used from the `CC_OP_DYNAMIC` arms of `gen_prepare_cc`. ~120 lines across
  `translate.c` + `cc_helper.c`. Plausibly removes 30-60% of the 4.3%.
* Combined realistic win: **1.5-3% of vCPU. Work: 1-2 weeks.** Falsified early by
  M1 if `CC_OP_DYNAMIC` and the ADD/INC family together are a small minority of
  helper calls.

**(iii) Anything more ambitious -- fold it into lead #2 instead.**
A FEX-style NZCV-resident design is **not available** (Q4.D: no flag concept in
the TCG IR, and the softmmu TLB compare clobbers NZCV roughly every 2-3 guest
instructions; fastmem, the only way to remove that, is already a dead end).
What *is* available is cross-TB `cc_op` propagation -- carry the entry `cc_op` in
`tb->flags` so a TB is not forced to start at `CC_OP_DYNAMIC`. That multiplies TB
variants (up to ~60 `CCOp` values) and worsens `qht`/jump-cache pressure, which
00-FINDINGS already shows is sensitive. **Superblocks (lead #2) subsume it:** a
longer TB both removes the DYNAMIC restart (L1) and amortises the per-TB flag
spill (L2). So the honest recommendation is that **the second and third tiers of
the flag work are really the superblock project**, and should be budgeted there,
not here.

### Correction to submit to 00-FINDINGS section C.1
The entry currently says the FP-compare path is "six instructions where `AXFLAG`
does one" and sizes native ARM flags at 2-4%. The first half is wrong (Q4.B) and
the second half is right by accident, for different reasons (Q4.C/D). Suggested
replacement is in the ranked conclusions at the top of this file.

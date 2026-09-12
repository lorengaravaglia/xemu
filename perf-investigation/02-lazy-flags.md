# 02 — Lazy condition-flag emulation: true total cost, and the NZCV opportunity

STATUS: in progress
Started 2026-09-11. Exploration only — no code modified, no device touched.

## Checklist
- [x] Q1. How the lazy-flag scheme works (translate.c, CCOp enum, gen_compute_eflags/gen_cc_op) — with file:line
- [x] Q2. Where flag computation IS elided, and where it FAILS to be
- [x] Q3. TCG-op accounting: flag bookkeeping vs real work per ALU insn; cross-check vs 82B/20.5 insns per guest insn, 6.5-insn TB
- [ ] Q4. AArch64 NZCV mapping: how far can it go, what breaks (AF/PF, SUB carry sense, no IR flag concept), prior art (Rosetta2 / FEX / MobiSys'25)
- [ ] Q5. Realistic win+work estimates (i) FP-compare fix (ii) in-TB NZCV (iii) ambitious; and a CHEAP pre-build measurement that sizes the prize

## Log
(append findings here as they land)

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
`compute_all_cout` (`cc_helper_template.h.inc:47-66`) computes PF (table/popcount),
ZF, SF from the result and AF/CF/OF from a carry-out vector. It is called through
`helper_cc_compute_all`'s switch. On top of that, **every helper call forces TCG
to sync all live globals to `env`** (`la_global_sync`, `tcg/tcg.c:3955`), so an
`cc_compute_all` call costs a spill wave as well as the call.

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

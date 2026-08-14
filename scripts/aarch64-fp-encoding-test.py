#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-2.0-or-later
"""
Validate the AArch64 scalar floating-point encodings in the TCG backend.

tcg/aarch64/tcg-target.c.inc encodes the scalar FP instructions (used by the
TCG_OPF_FP opcodes) as raw 32-bit constants plus emit helpers that OR in the
register fields.  A wrong constant or a misplaced field produces a silent
miscompile of guest FP code, which is very hard to debug from the symptom.

This script parses those constants out of the backend source, applies the same
emit formulas, disassembles the result with llvm-objdump, and compares against
independently written expectations.  It also checks that tcg_out_op() routes
each opcode to the correspondingly named encoding (catching transpositions such
as add -> FSUB) and that every emitted opcode has a constraint entry.

Register numbers deliberately use TCG numbering, where TCG_REG_V0 = 32, so that
the "& 0x1f" masking in the emit helpers is actually exercised -- assembling a
mnemonic by hand only ever checks small register numbers.

Usage:
    scripts/aarch64-fp-encoding-test.py [--objdump PATH]

llvm-objdump is located via --objdump, then $LLVM_OBJDUMP, then $PATH.
Requires an llvm-objdump that knows aarch64; GNU objdump will not work.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO_ROOT, "tcg", "aarch64", "tcg-target.c.inc")

# TCG register numbering (tcg/aarch64/tcg-target.h): X0-X30 = 0-30, SP = 31,
# V0-V31 = 32-63.  V31 in particular checks that masking maps 63 -> d31.
V0, V1, V2, V31 = 32, 33, 34, 63
X0, X1, X30 = 0, 1, 30


def find_tool(explicit, envvar, *names):
    cands = [explicit, os.environ.get(envvar)]
    cands += [shutil.which(n) for n in names]
    for cand in cands:
        if cand and os.path.exists(cand):
            return cand
    sys.exit(f"error: {names[0]} not found; pass the flag or set ${envvar}")


def parse_encodings():
    enc = {}
    with open(SRC) as f:
        for line in f:
            m = re.match(r'\s+(I370[123]_[A-Z0-9_]+)\s*=\s*(0x[0-9a-f]+),', line)
            if m:
                enc[m.group(1)] = int(m.group(2), 16)
    if not enc:
        sys.exit(f"error: parsed no I370x encodings from {SRC}")
    return enc


def build_cases(enc):
    """Return [(word, expected_disassembly)]."""
    def e1(name, rd, rn):
        return enc[name] | (rn & 0x1f) << 5 | (rd & 0x1f)

    def e2(name, rd, rn, rm):
        return (enc[name] | (rm & 0x1f) << 16
                | (rn & 0x1f) << 5 | (rd & 0x1f))

    def e3(name, rn, rm):
        return enc[name] | (rm & 0x1f) << 16 | (rn & 0x1f) << 5

    cases = []
    for nm, mn in [("FADD", "fadd"), ("FSUB", "fsub"),
                   ("FMUL", "fmul"), ("FDIV", "fdiv")]:
        cases += [
            (e2(f"I3702_{nm}D", V0, V1, V2), f"{mn} d0, d1, d2"),
            (e2(f"I3702_{nm}D", V31, V31, V31), f"{mn} d31, d31, d31"),
            (e2(f"I3702_{nm}D", V2, V31, V0), f"{mn} d2, d31, d0"),
            (e2(f"I3702_{nm}S", V0, V1, V2), f"{mn} s0, s1, s2"),
            (e2(f"I3702_{nm}S", V31, V31, V31), f"{mn} s31, s31, s31"),
        ]
    for nm, mn in [("FABS", "fabs"), ("FNEG", "fneg"),
                   ("FSQRT", "fsqrt"), ("FMOV", "fmov")]:
        cases += [
            (e1(f"I3701_{nm}D", V0, V1), f"{mn} d0, d1"),
            (e1(f"I3701_{nm}D", V31, V0), f"{mn} d31, d0"),
            (e1(f"I3701_{nm}S", V0, V1), f"{mn} s0, s1"),
            (e1(f"I3701_{nm}S", V31, V0), f"{mn} s31, s0"),
        ]
    cases += [
        # bit-for-bit reinterprets: one operand is a general register
        (e1("I3701_FMOV_DX", V0, X1), "fmov d0, x1"),
        (e1("I3701_FMOV_DX", V31, X30), "fmov d31, x30"),
        (e1("I3701_FMOV_XD", X0, V1), "fmov x0, d1"),
        (e1("I3701_FMOV_XD", X30, V31), "fmov x30, d31"),
        (e1("I3701_FMOV_SW", V0, X1), "fmov s0, w1"),
        (e1("I3701_FMOV_WS", X0, V1), "fmov w0, s1"),
        # conversions
        (e1("I3701_FCVT_SD", V0, V1), "fcvt s0, d1"),
        (e1("I3701_FCVT_DS", V0, V1), "fcvt d0, s1"),
        (e1("I3701_SCVTFD_W", V0, X1), "scvtf d0, w1"),
        (e1("I3701_SCVTFD_X", V0, X1), "scvtf d0, x1"),
        (e1("I3701_SCVTFS_W", V0, X1), "scvtf s0, w1"),
        (e1("I3701_SCVTFS_X", V0, X1), "scvtf s0, x1"),
        # compares write NZCV and have no destination
        (e3("I3703_FCMPD", V0, V1), "fcmp d0, d1"),
        (e3("I3703_FCMPD", V31, V31), "fcmp d31, d31"),
        (e3("I3703_FCMPS", V0, V1), "fcmp s0, s1"),
    ]
    return cases


def disassemble(cc, objdump, words):
    """
    Assemble the raw instruction words via .inst directives and disassemble the
    result.  llvm-objdump has no "-b binary" mode, so going through the
    assembler is the portable way to get a disassembly of arbitrary words.
    """
    src = "".join(f".inst 0x{w:08x}\n" for w in words)
    tmpdir = tempfile.mkdtemp()
    try:
        spath = os.path.join(tmpdir, "t.s")
        opath = os.path.join(tmpdir, "t.o")
        with open(spath, "w") as f:
            f.write(src)
        r = subprocess.run([cc, "-c", spath, "-o", opath],
                           capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"error: assembling test words failed:\n{r.stderr}")
        out = subprocess.run([objdump, "-d", opath],
                             capture_output=True, text=True).stdout
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    got = []
    for line in out.splitlines():
        # "       0: 1e622820     \tfadd\td0, d1, d2"
        m = re.match(r'\s+[0-9a-f]+:\s+[0-9a-f]{8}\s+(.*)', line)
        if m:
            got.append(re.sub(r'\s+', ' ', m.group(1).replace('\t', ' ').strip()))
    return got


def check_dispatch(src_text):
    """tcg_out_op() must route each opcode to the matching encoding."""
    body = src_text.split("static inline void tcg_out_op(")[1]
    body = body.split("static void tcg_out_vec_op(")[0]
    pairs = re.findall(r'case INDEX_op_(\w+):\s*\n\s*tcg_out_insn\(s,\s*'
                       r'(\d+),\s*(\w+),', body)
    mnem = {"add": "FADD", "sub": "FSUB", "mul": "FMUL", "div": "FDIV",
            "abs": "FABS", "chs": "FNEG", "sqrt": "FSQRT", "mov": "FMOV"}
    reint = {"mov32i_f32": "FMOV_SW", "mov64i_f64": "FMOV_DX",
             "mov32f_i32": "FMOV_WS", "mov64f_i64": "FMOV_XD"}
    bad = 0
    for op, _fmt, insn in pairs:
        if op in reint:
            want = reint[op]
        else:
            base, _, prec = op.rpartition("_")
            if base not in mnem:
                continue
            want = mnem[base] + ("D" if prec == "f64" else "S")
        if insn != want:
            print(f"  MISMATCH: INDEX_op_{op} -> {insn}, expected {want}")
            bad += 1
    cons = src_text.split("tcg_target_op_def(TCGOpcode op")[1].split("\n}")[0]
    missing = [op for op, _, _ in pairs if f"INDEX_op_{op}:" not in cons]
    return pairs, bad, missing


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--objdump", help="path to llvm-objdump")
    ap.add_argument("--cc", help="path to an aarch64 C compiler/assembler")
    args = ap.parse_args()
    objdump = find_tool(args.objdump, "LLVM_OBJDUMP", "llvm-objdump")
    cc = find_tool(args.cc, "CC", "aarch64-linux-android28-clang",
                   "aarch64-linux-gnu-gcc", "clang")

    enc = parse_encodings()
    print(f"parsed {len(enc)} encodings from tcg/aarch64/tcg-target.c.inc")

    cases = build_cases(enc)
    got = disassemble(cc, objdump, [w for w, _ in cases])
    if len(got) != len(cases):
        sys.exit(f"error: disassembled {len(got)} of {len(cases)} words")

    fails = 0
    for (word, want), actual in zip(cases, got):
        if actual != want:
            print(f"  MISMATCH {word:08x}: want '{want}', got '{actual}'")
            fails += 1
    print(f"encodings:            {len(cases) - fails}/{len(cases)} correct")

    src_text = open(SRC).read()
    pairs, bad, missing = check_dispatch(src_text)
    print(f"opcode -> encoding:   {len(pairs) - bad}/{len(pairs)} correct")
    print(f"constraint entries:   {'all present' if not missing else missing}")

    if fails or bad or missing:
        return 1
    print("\nOK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

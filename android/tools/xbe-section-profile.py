#!/usr/bin/env python3
"""Attribute vCPU cycle samples to XBE sections.

Answers "how much of the emulated CPU is the game itself, versus Xbox library
and kernel code". .text is the game's own logic; D3D/DSOUND/XNET/... are Xbox
libraries statically linked into the XBE; guest PCs >= 0x80000000 are the
kernel. Used to bound what an HLE design could remove (see FINDINGS section
AH), and useful for any question of the form "what code is actually hot".

Two things this gets wrong if you are not careful, both hit in practice:

  * The vCPU is NOT necessarily the busiest thread -- on the slot-5 scene the
    NV2A thread out-samples it. Select the thread with generated-code samples.
  * Halo idles in a clock spin at guest 0x000bb0df, which lands in .text and
    is not game work. Check the top PCs before reading the .text number.

Inputs, all from the SAME run (JIT addresses differ between runs):
  guestmap.bin   written with debug.xemu.guest_map=1 at benchmark end
  halo_sections.json  from xbe-sections.py
  simpleperf dump text  (record with --app, not -p; shell cannot attach by pid)
"""
import bisect, collections, json, re, struct, sys

D = "/Users/lorengaravaglia/.claude/jobs/16b69e39/tmp"

def load_guestmap(p):
    b = open(p, 'rb').read(); assert b[:5] == b'XGPC1', 'bad magic'
    n, = struct.unpack_from('<I', b, 5)
    return sorted(struct.unpack_from('<QII', b, 9 + 16*i) for i in range(n))

def load_samples(path):
    ip = None
    for line in open(path, errors='replace'):
        line = line.strip()
        if line.startswith('ip 0x'):
            ip = int(line[3:], 16)
        elif line.startswith('pid ') and ip is not None:
            m = re.match(r'pid (\d+), tid (\d+)', line)
            if m: yield int(m.group(2)), ip
            ip = None

recs   = load_guestmap(f"{D}/guestmap.bin")
starts = [r[0] for r in recs]
secs   = json.load(open(f"{D}/halo_sections.json"))
secs   = [s for s in secs if s['vsize'] > 0]

# HLE would replace these; .text is the game itself.
LIB = {'D3D','D3DX','DSOUND','XNET','XPP','DOLBY'}
BINK = lambda n: n.startswith('BINK')

def section_of(gpc):
    for s in secs:
        if s['va'] <= gpc < s['va'] + s['vsize']:
            return s['name']
    return 'KERNEL/other' if gpc >= 0x80000000 else f'unmapped'

def tb_for(ip):
    i = bisect.bisect_right(starts, ip) - 1
    j = i
    while j >= 0 and j > i - 64:
        host, size, g = recs[j]
        if host <= ip < host + size: return g
        j -= 1
    return None

samples = list(load_samples(f"{D}/hle_dump.txt"))
# The vCPU is the thread executing generated code -- NOT simply the busiest
# thread. On this scene the NV2A thread out-samples it, and picking by volume
# attributed the whole profile to the wrong thread.
jit_by_tid = collections.Counter(t for t, ip in samples if tb_for(ip) is not None)
vcpu_tid, vcpu_n = jit_by_tid.most_common(1)[0]
by_tid = collections.Counter(t for t, _ in samples)
vcpu_n = by_tid[vcpu_tid]
print(f"{len(samples)} samples; busiest tid {vcpu_tid} = {vcpu_n} ({100*vcpu_n/len(samples):.1f}%)")

sect = collections.Counter()
in_jit = 0
for tid, ip in samples:
    if tid != vcpu_tid: continue
    g = tb_for(ip)
    if g is None:
        sect['(not in JIT: helpers, devices, QEMU C)'] += 1
    else:
        in_jit += 1
        sect[section_of(g)] += 1

tot = sum(sect.values())
print(f"\nvCPU thread: {tot} samples, {in_jit} inside generated code "
      f"({100*in_jit/tot:.1f}%)\n")
print(f"{'bucket':<42}{'samples':>9}{'% vCPU':>9}{'% of JIT':>10}")
for name, n in sect.most_common():
    jitpct = f"{100*n/in_jit:8.2f}%" if 'not in JIT' not in name else "        -"
    print(f"{name:<42}{n:>9}{100*n/tot:8.2f}%{jitpct:>10}")

lib = sum(n for k, n in sect.items() if k in LIB or BINK(k))
game = sect.get('.text', 0)
print(f"\n--- the HLE bound ---")
print(f"game .text                  {game:>7} = {100*game/tot:5.2f}% of vCPU, {100*game/in_jit:5.2f}% of JIT")
print(f"Xbox libraries (HLE target) {lib:>7} = {100*lib/tot:5.2f}% of vCPU, {100*lib/in_jit:5.2f}% of JIT")

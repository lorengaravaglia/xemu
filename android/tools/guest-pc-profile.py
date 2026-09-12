#!/usr/bin/env python3
"""Attribute perf samples to GUEST program counters.

Every other profile in this project is host-side -- which TCG op, which cache
level.  This one answers "which Halo code is hot", by mapping each sampled
host PC back through the JIT code range of its translation block to the guest
PC that block was translated from.

  argv[1] guestmap.bin  (written on device with debug.xemu.guest_map=1)
  argv[2] text output of `simpleperf dump` for a capture from the same run
"""
import bisect, collections, re, struct, sys

def load_map(path):
    b = open(path, 'rb').read()
    assert b[:5] == b'XGPC1', 'bad magic'
    n, = struct.unpack_from('<I', b, 5)
    recs, off = [], 9
    for _ in range(n):
        host, size, gpc = struct.unpack_from('<QII', b, off)
        off += 16
        recs.append((host, size, gpc))
    return recs

def load_samples(path):
    tid = ip = None
    for line in open(path, errors='replace'):
        line = line.strip()
        if line.startswith('ip 0x'):
            ip = int(line[3:], 16)
        elif line.startswith('pid ') and ip is not None:
            m = re.match(r'pid (\d+), tid (\d+)', line)
            if m:
                yield int(m.group(2)), ip
            ip = None

def main():
    recs = sorted(load_map(sys.argv[1]))
    starts = [r[0] for r in recs]
    print('map: %d translation blocks' % len(recs))

    samples = list(load_samples(sys.argv[2]))
    by_tid = collections.Counter(t for t, _ in samples)

    def in_map(ip):
        i = bisect.bisect_right(starts, ip) - 1
        j = i
        while j >= 0 and j > i - 64:
            host, size, g = recs[j]
            if host <= ip < host + size:
                return g
            j -= 1
        return None

    # Pick the vCPU by which thread's samples actually land in JIT code, not
    # by sample count: a capture can be dominated by the render or GPU thread,
    # whose IPs are in driver code and would score zero here.
    best, vcpu = -1, None
    for t, _n in by_tid.most_common(8):
        got = sum(1 for tt, ip in samples if tt == t and in_map(ip) is not None)
        print('  tid %-8d %6d samples, %6d in JIT code' % (t, _n, got))
        if got > best:
            best, vcpu = got, t
    print('samples: %d total, vCPU tid %d (%d samples)'
          % (len(samples), vcpu, by_tid[vcpu]))

    per_pc, per_page, per_64k = (collections.Counter() for _ in range(3))
    hit = miss = 0
    for tid, ip in samples:
        if tid != vcpu:
            continue
        i = bisect.bisect_right(starts, ip) - 1
        gpc = None
        j = i
        while j >= 0 and j > i - 64:
            host, size, g = recs[j]
            if host <= ip < host + size:
                gpc = g
                break
            j -= 1
        if gpc is None:
            miss += 1
            continue
        hit += 1
        per_pc[gpc] += 1
        per_page[gpc & ~0xFFF] += 1
        per_64k[gpc & ~0xFFFF] += 1

    tot = hit + miss
    print('in JIT code: %d | outside the map: %d (%.1f%% -- helpers, runtime)\n'
          % (hit, miss, 100.0 * miss / tot if tot else 0.0))
    if not hit:
        return

    def table(title, ctr, n):
        print('%-14s %10s %11s   %s' % ('guest addr', '%% of JIT', 'cumulative', title))
        cum = 0
        for k, v in ctr.most_common(n):
            cum += v
            print('  0x%08x %9.2f%% %10.2f%%' % (k, 100.0*v/hit, 100.0*cum/hit))
        print()

    table('individual block entry points', per_pc, 20)
    table('4 KB pages -- a hot FUNCTION shows up here', per_page, 15)
    table('64 KB regions -- a hot SUBSYSTEM shows up here', per_64k, 10)

    top = per_page.most_common(1)[0]
    print('VERDICT: hottest 4 KB of guest code is %.1f%% of JIT time.' %
          (100.0*top[1]/hit))
    print('  concentrated (>10%%) -> a targeted native replacement is worth costing')
    print('  flat (<5%%)          -> no hotspot; generic techniques only, and those are bounded')

main()

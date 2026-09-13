#!/usr/bin/env python3
"""Split the guest-PC profile by frame cost.

A clock spin-wait means the frame finished early, so it should concentrate in
CHEAP frames and be absent from the expensive ones that actually miss 30 fps.
If that holds, eliding the spin buys headroom rather than frame rate.  This
decides it: assign each perf sample to a frame by timestamp, classify the
frame by its cycle cost, and report the guest-PC breakdown for each class.

  argv[1] guestmap.bin   argv[2] framelog.bin   argv[3] simpleperf dump text
"""
import bisect, collections, re, struct, sys

BUDGET = 98_000_000        # cycles for 33.3 ms at ~2.94 GHz

def load_guestmap(p):
    b = open(p, 'rb').read(); assert b[:5] == b'XGPC1'
    n, = struct.unpack_from('<I', b, 5)
    return sorted(struct.unpack_from('<QII', b, 9 + 16*i) for i in range(n))

def load_framelog(p):
    b = open(p, 'rb').read(); assert b[:5] == b'XFRM1', 'bad framelog magic'
    n, = struct.unpack_from('<I', b, 5)
    ts = list(struct.unpack_from('<%dQ' % n, b, 9))
    cyc = list(struct.unpack_from('<%dQ' % n, b, 9 + 8*n))
    # The recorded cycle counts are unusable while simpleperf is running: both
    # read the same hardware PMU and the kernel multiplexes them, so our counts
    # come back scaled down (~40% observed).  Wall time between frames is
    # immune to that, and is the better discriminator anyway -- a frame that
    # takes longer than 33.3 ms of wall time IS a dropped frame.
    wall = [0] + [(ts[i] - ts[i-1]) / 1e6 for i in range(1, len(ts))]
    return ts, cyc, wall

def load_samples(p):
    ip = t = None
    for line in open(p, errors='replace'):
        line = line.strip()
        if line.startswith('ip 0x'):
            ip = int(line[3:], 16)
        elif line.startswith('time ') and ip is not None:
            t = int(line[5:])
        elif line.startswith('pid ') and ip is not None and t is not None:
            m = re.match(r'pid (\d+), tid (\d+)', line)
            if m:
                yield int(m.group(2)), ip, t
            ip = t = None

def main():
    recs = load_guestmap(sys.argv[1])
    starts = [r[0] for r in recs]
    fts, fcyc, fwall = load_framelog(sys.argv[2])
    print('map %d TBs | framelog %d frames' % (len(recs), len(fts)))

    def gpc_of(ip):
        i = bisect.bisect_right(starts, ip) - 1
        j = i
        while j >= 0 and j > i - 64:
            h, sz, g = recs[j]
            if h <= ip < h + sz:
                return g
            j -= 1
        return None

    samples = list(load_samples(sys.argv[3]))
    by_tid = collections.Counter(t for t, _, _ in samples)
    best, vcpu = -1, None
    for tid, _ in by_tid.most_common(8):
        got = sum(1 for tt, ip, _ in samples if tt == tid and gpc_of(ip) is not None)
        if got > best:
            best, vcpu = got, tid
    print('vCPU tid %d (%d samples in JIT)\n' % (vcpu, best))

    cheap = collections.Counter(); exp = collections.Counter()
    nc = ne = 0
    for tid, ip, t in samples:
        if tid != vcpu:
            continue
        g = gpc_of(ip)
        if g is None:
            continue
        k = bisect.bisect_right(fts, t) - 1
        if k < 0 or k >= len(fcyc):
            continue
        ratio = fwall[k] / 33.33
        if ratio <= 1.0:
            cheap[g & ~0xFFF] += 1; nc += 1
        elif ratio > 1.2:
            exp[g & ~0xFFF] += 1; ne += 1

    print('samples assigned: %d in cheap frames, %d in expensive frames\n' % (nc, ne))
    if not nc or not ne:
        print('  not enough of one class to compare'); return
    HOT = 0x000bb000
    print('%-12s %14s %14s' % ('guest page', 'cheap frames', 'expensive'))
    pages = set(list(cheap) + list(exp))
    for pg in sorted(pages, key=lambda p: -(cheap[p] + exp[p]))[:10]:
        mark = '   <== the spin' if pg == HOT else ''
        print('  0x%08x %12.2f%% %13.2f%%%s'
              % (pg, 100.0*cheap[pg]/nc, 100.0*exp[pg]/ne, mark))
    c = 100.0*cheap[HOT]/nc; e = 100.0*exp[HOT]/ne
    print('\nSPIN PAGE 0x%08x: %.1f%% of cheap-frame time, %.1f%% of expensive-frame time'
          % (HOT, c, e))
    if e < c * 0.4:
        print('  -> concentrated in cheap frames: eliding it buys HEADROOM, not frame rate.')
    elif e > c * 0.75:
        print('  -> present in expensive frames too: eliding it attacks the DIPS directly.')
    else:
        print('  -> partial; size the win from the expensive-frame share.')

main()

"""Dump XBE section VIRTUAL address ranges, so a guest PC can be attributed
to the library it belongs to (.text = game, D3D/DSOUND/... = Xbox libraries)."""
import sys; sys.argv=[sys.argv[0]]
exec(open("/Users/lorengaravaglia/.claude/jobs/16b69e39/tmp/xiso.py").read())
import struct, json

def section_vas(path):
    b = find_base(path)
    vd = rd(path, b+32*SEC, 0x800)
    rsec, rsz = struct.unpack_from("<II", vd, 20)
    s, sz, _ = walk(path, b, rsec, rsz, "default.xbe")["default.xbe"]
    info = xbe_info(path, b, s, sz)
    m_base, nsec, hdrs = info['m_base'], info['nsec'], info['sec_hdrs']
    raw = rd(path, b+s*SEC+(hdrs-m_base), nsec*56)
    out=[]
    for i in range(nsec):
        o=i*56
        flags, va, vsize, ra, rsize, name_addr = struct.unpack_from("<IIIIII", raw, o)
        nm = rd(path, b+s*SEC+(name_addr-m_base), 24).split(b"\x00")[0].decode("latin-1","replace")
        out.append(dict(name=nm, va=va, vsize=vsize, rsize=rsize))
    return info, sorted(out, key=lambda d: d['va'])

if __name__ == "__main__":
    path="/sdcard/ROMs/xbox/Halo - Combat Evolved (USA) (Rev 2)/Halo - Combat Evolved (USA) (Rev 2).xiso.iso"
    info, secs = section_vas(path)
    print(f"# {info['title']}  titleid 0x{info['titleid']:08X}  base 0x{info['m_base']:X}")
    print(f"{'section':<14}{'va_start':>10}{'va_end':>10}{'KB':>8}")
    for d in secs:
        if d['vsize']==0: continue
        print(f"{d['name']:<14}0x{d['va']:08X} 0x{d['va']+d['vsize']:08X} {d['vsize']/1024:7.1f}")
    json.dump(secs, open("/Users/lorengaravaglia/.claude/jobs/16b69e39/tmp/halo_sections.json","w"))

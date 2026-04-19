/*
 * QEMU Geforce NV2A implementation
 *
 * Copyright (c) 2012 espes
 * Copyright (c) 2015 Jannik Vogel
 * Copyright (c) 2018-2021 Matt Borgerson
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see <http://www.gnu.org/licenses/>.
 */

#include "nv2a_int.h"
#if defined(__ANDROID__) || defined(ANDROID)
#include <android/log.h>
#define ALOGI_PCRTC(...) ((void)__android_log_print(ANDROID_LOG_INFO, "xemu-pgraph", __VA_ARGS__))
#else
#define ALOGI_PCRTC(...) ((void)0)
#endif

/* Return the current linear PC of the first VCPU (for diagnostic ISR attribution) */
static uint32_t pcrtc_get_vcpu_pc(void)
{
#if defined(__ANDROID__) || defined(ANDROID)
    CPUState *vcpu = first_cpu;
    if (vcpu) {
        CPUX86State *env = cpu_env(vcpu);
        return (uint32_t)(env->segs[R_CS].base + env->eip);
    }
#endif
    return 0;
}

uint64_t pcrtc_read(void *opaque, hwaddr addr, unsigned int size)
{
    NV2AState *d = (NV2AState *)opaque;

    uint64_t r = 0;
    switch (addr) {
        case NV_PCRTC_INTR_0:
            r = d->pcrtc.pending_interrupts;
            ALOGI_PCRTC("pcrtc: READ INTR_0=0x%x en=0x%x PC=0x%08x",
                        (unsigned)r, (unsigned)d->pcrtc.enabled_interrupts,
                        pcrtc_get_vcpu_pc());
            break;
        case NV_PCRTC_INTR_EN_0:
            r = d->pcrtc.enabled_interrupts;
            break;
        case NV_PCRTC_START:
            r = d->pcrtc.start;
            break;
        case NV_PCRTC_RASTER:
            r = d->pcrtc.raster++;
            break;
        default:
            break;
    }

    nv2a_reg_log_read(NV_PCRTC, addr, size, r);
    return r;
}

void pcrtc_write(void *opaque, hwaddr addr, uint64_t val, unsigned int size)
{
    NV2AState *d = (NV2AState *)opaque;

    nv2a_reg_log_write(NV_PCRTC, addr, size, val);

    switch (addr) {
    case NV_PCRTC_INTR_0:
        ALOGI_PCRTC("pcrtc: WRITE INTR_0 clear=0x%x pending_after=0x%x en=0x%x PC=0x%08x",
                    (unsigned)val,
                    (unsigned)(d->pcrtc.pending_interrupts & ~val),
                    (unsigned)d->pcrtc.enabled_interrupts,
                    pcrtc_get_vcpu_pc());
        d->pcrtc.pending_interrupts &= ~val;
        nv2a_update_irq(d);
        break;
    case NV_PCRTC_INTR_EN_0:
        ALOGI_PCRTC("pcrtc: WRITE INTR_EN_0=0x%x (was 0x%x) PC=0x%08x",
                    (unsigned)val, (unsigned)d->pcrtc.enabled_interrupts,
                    pcrtc_get_vcpu_pc());
        d->pcrtc.enabled_interrupts = val;
        nv2a_update_irq(d);
        break;
    case NV_PCRTC_START:
        val &= 0x07FFFFFF;
        // assert(val < memory_region_size(d->vram));
        ALOGI_PCRTC("pcrtc: NV_PCRTC_START 0x%x -> 0x%x", (unsigned)d->pcrtc.start, (unsigned)val);
        d->pcrtc.start = val;

        NV2A_DPRINTF("PCRTC_START - %x %x %x %x\n",
                d->vram_ptr[val+64], d->vram_ptr[val+64+1],
                d->vram_ptr[val+64+2], d->vram_ptr[val+64+3]);
        break;
    default:
        break;
    }
}

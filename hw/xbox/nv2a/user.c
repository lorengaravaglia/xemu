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

/* USER - PFIFO MMIO and DMA submission area */
#if defined(__ANDROID__) || defined(ANDROID)
#include <sys/system_properties.h>
#endif

/* Polls of the USER block, for the pfifo-thread stats line (FINDINGS BL). */
uint64_t g_nv2a_user_reads;
Nv2aLockStat g_nv2a_lock_stats[NV2A_LK__COUNT];
Nv2aAddrStat g_nv2a_addr_stats[32];
__thread int64_t g_nv2a_last_wait_ns;

int g_nv2a_lockfree = 1;

uint64_t user_read(void *opaque, hwaddr addr, unsigned int size)
{
    NV2AState *d = (NV2AState *)opaque;

    unsigned int channel_id = addr >> 16;
    assert(channel_id < NV2A_NUM_CHANNELS);

    qatomic_set(&g_nv2a_user_reads, g_nv2a_user_reads + 1);

    /*
     * Guests poll DMA_GET / REF here in a loop to watch the GPU progress.
     * Taking pfifo.lock for that made every poll queue behind the pfifo
     * thread, which holds the lock for nearly all of its pushbuffer
     * parsing and re-takes it straight after each method -- an unfair
     * handoff the polling vCPU usually lost.  The vCPU was blocked ~9% of
     * its time in this function alone (FINDINGS BK/BL).
     *
     * None of it needs the lock.  Each value is one aligned 32-bit word;
     * DMA_PUT and REF are written only by the guest (user_write, on this
     * same vCPU); DMA_GET is published by the pusher with a release store
     * once the methods before it have been processed, so the acquire load
     * here cannot see progress ahead of the work behind it.  MODE and PUSH1
     * change only on channel setup.
     */
    if (qatomic_read(&g_nv2a_lockfree)) {
        uint32_t channel_modes = qatomic_read(&d->pfifo.regs[NV_PFIFO_MODE]);
        uint64_t r = 0;

        if (!(channel_modes & (1 << channel_id))) {
            assert(!"Failed to enter DMA mode - entered PIO mode");
        }
        unsigned int cur_channel_id =
            GET_MASK(qatomic_read(&d->pfifo.regs[NV_PFIFO_CACHE1_PUSH1]),
                     NV_PFIFO_CACHE1_PUSH1_CHID);
        if (channel_id != cur_channel_id) {
            assert(!"Unsupported: channel_id != cur_channel_id");
        }
        switch (addr & 0xFFFF) {
        case NV_USER_DMA_PUT:
            r = qatomic_load_acquire(&d->pfifo.regs[NV_PFIFO_CACHE1_DMA_PUT]);
            break;
        case NV_USER_DMA_GET:
            r = qatomic_load_acquire(&d->pfifo.regs[NV_PFIFO_CACHE1_DMA_GET]);
            break;
        case NV_USER_REF:
            r = qatomic_load_acquire(&d->pfifo.regs[NV_PFIFO_CACHE1_REF]);
            break;
        default:
            break;
        }
        nv2a_reg_log_read(NV_USER, addr, size, r);
        return r;
    }

    nv2a_timed_lock(&d->pfifo.lock, NV2A_LK_USER_RD);

    uint32_t channel_modes = d->pfifo.regs[NV_PFIFO_MODE];

    uint64_t r = 0;
    if (channel_modes & (1 << channel_id)) {
        /* DMA Mode */

        unsigned int cur_channel_id =
            GET_MASK(d->pfifo.regs[NV_PFIFO_CACHE1_PUSH1],
                     NV_PFIFO_CACHE1_PUSH1_CHID);

        if (channel_id == cur_channel_id) {
            switch (addr & 0xFFFF) {
            case NV_USER_DMA_PUT:
                r = d->pfifo.regs[NV_PFIFO_CACHE1_DMA_PUT];
                break;
            case NV_USER_DMA_GET:
                r = d->pfifo.regs[NV_PFIFO_CACHE1_DMA_GET];
                break;
            case NV_USER_REF:
                r = d->pfifo.regs[NV_PFIFO_CACHE1_REF];
                break;
            default:
                break;
            }
        } else {
            /* ramfc */
            assert(!"Unsupported: channel_id != cur_channel_id");
        }
    } else {
        /* PIO Mode */
        assert(!"Failed to enter DMA mode - entered PIO mode");
    }

    qemu_mutex_unlock(&d->pfifo.lock);

    nv2a_reg_log_read(NV_USER, addr, size, r);
    return r;
}

void user_write(void *opaque, hwaddr addr, uint64_t val, unsigned int size)
{
    NV2AState *d = (NV2AState *)opaque;

    nv2a_reg_log_write(NV_USER, addr, size, val);

    unsigned int channel_id = addr >> 16;
    assert(channel_id < NV2A_NUM_CHANNELS);

    /*
     * The DMA_PUT kick, without pfifo.lock (FINDINGS BL).  The pusher holds
     * that lock for nearly all its parsing, so a locked kick -- made holding
     * the BQL -- waited up to ~6 ms, ~0.6 ms a frame on slot 1.
     *
     * Publish PUT (release: the pushbuffer words written before it are
     * visible first), then kick; pfifo_kick_lockless() wakes the thread only
     * if it is asleep.
     */
    if (qatomic_read(&g_nv2a_lockfree) && (addr & 0xFFFF) == NV_USER_DMA_PUT) {
        uint32_t modes = qatomic_read(&d->pfifo.regs[NV_PFIFO_MODE]);
        unsigned int cur = GET_MASK(qatomic_read(
            &d->pfifo.regs[NV_PFIFO_CACHE1_PUSH1]), NV_PFIFO_CACHE1_PUSH1_CHID);

        if ((modes & (1 << channel_id)) && channel_id == cur) {
            g_nv2a_lock_stats[NV2A_LK_USER_WR].n++;
            qatomic_store_release(&d->pfifo.regs[NV_PFIFO_CACHE1_DMA_PUT],
                                  (uint32_t)val);
            pfifo_kick_lockless(d);
            return;
        }
        /* anything unusual takes the locked path below, with its asserts */
    }

    nv2a_timed_lock(&d->pfifo.lock, NV2A_LK_USER_WR);

    uint32_t channel_modes = d->pfifo.regs[NV_PFIFO_MODE];
    if (channel_modes & (1 << channel_id)) {
        /* DMA Mode */
        unsigned int cur_channel_id =
            GET_MASK(d->pfifo.regs[NV_PFIFO_CACHE1_PUSH1],
                     NV_PFIFO_CACHE1_PUSH1_CHID);

        if (channel_id == cur_channel_id) {
            switch (addr & 0xFFFF) {
            case NV_USER_DMA_PUT:
                d->pfifo.regs[NV_PFIFO_CACHE1_DMA_PUT] = val;
                break;
            case NV_USER_DMA_GET:
                d->pfifo.regs[NV_PFIFO_CACHE1_DMA_GET] = val;
                break;
            case NV_USER_REF:
                d->pfifo.regs[NV_PFIFO_CACHE1_REF] = val;
                break;
            default:
                NV2A_DPRINTF(true, "Unsupported NV_USER write: channel=%u offset=0x%04x\n", channel_id, (unsigned)(addr & 0xFFFF));
                assert(!"Unsupported NV_USER DMA register write offset");
                break;
            }

            pfifo_kick(d);

        } else {
            /* ramfc */
            assert(!"Invalid channel id");
        }
    } else {
        /* PIO Mode */
        assert(!"Failed to enter DMA mode - entered PIO mode");
    }

    qemu_mutex_unlock(&d->pfifo.lock);

}

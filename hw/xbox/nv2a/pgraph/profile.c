/*
 * QEMU Geforce NV2A profiling helpers
 *
 * Copyright (c) 2020-2024 Matt Borgerson
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

#include "hw/xbox/nv2a/nv2a_int.h"
#include <time.h>

NV2AStats g_nv2a_stats;

void nv2a_profile_increment(void)
{
    int64_t now = qemu_clock_get_us(QEMU_CLOCK_REALTIME);
    const int64_t fps_update_interval = 250000;
    g_nv2a_stats.last_flip_time = now;

    static int64_t frame_count = 0;
    frame_count++;

    static int64_t ts = 0;
    int64_t delta = now - ts;
    if (delta >= fps_update_interval) {
        g_nv2a_stats.increment_fps = frame_count * 1000000 / delta;
        ts = now;
        frame_count = 0;
    }
}

void nv2a_profile_flip_stall(void)
{
    int64_t now = qemu_clock_get_us(QEMU_CLOCK_REALTIME);
    int64_t render_time = (now-g_nv2a_stats.last_flip_time)/1000;

    g_nv2a_stats.frame_working.mspf = render_time;
    {
        /* Called on the PFIFO thread, so the thread clock is NV2A's. */
        static int64_t prev_cpu_us;
        struct timespec ts;
        int64_t cpu_us;

        clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
        cpu_us = ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
        g_nv2a_stats.frame_working.cpu_us = prev_cpu_us ? (int)(cpu_us - prev_cpu_us) : 0;
        prev_cpu_us = cpu_us;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        g_nv2a_stats.frame_working.t_us = ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
    }
    g_nv2a_stats.frame_history[g_nv2a_stats.frame_ptr] =
        g_nv2a_stats.frame_working;
    g_nv2a_stats.frame_ptr =
        (g_nv2a_stats.frame_ptr + 1) % NV2A_PROF_NUM_FRAMES;
    g_nv2a_stats.frame_count++;
    memset(&g_nv2a_stats.frame_working, 0, sizeof(g_nv2a_stats.frame_working));
}

/*
 * C5: print the last `frames` NV2A frames, oldest first -- flip-stall time,
 * the NV2A thread's CPU and fence-wait time, and the counters that say what
 * the frame did.  Read beside the render thread's framelog.bin, it shows
 * whether a late displayed frame was late on the NV2A side and why.
 */
void nv2a_profile_dump_history(int frames)
{
    int n = frames < NV2A_PROF_NUM_FRAMES ? frames : NV2A_PROF_NUM_FRAMES;
    for (int i = n; i >= 1; i--) {
        int idx = (g_nv2a_stats.frame_ptr + NV2A_PROF_NUM_FRAMES - i) %
                  NV2A_PROF_NUM_FRAMES;
        typeof(g_nv2a_stats.frame_history[0]) *f = &g_nv2a_stats.frame_history[idx];
        const int *c = f->counters;
        fprintf(stderr, "nvf: t %lld.%06lld mspf %d cpu %d fence %d | fin vb %d sc %d sd %d nbs %d fbd %d pres %d flip %d fl %d st %d | "
                "submit %d draws %d tex %d surfdown %d surfup %d s2t %d s2tfb %d pgen %d sgen %d clr %d\n",
                (long long)(f->t_us / 1000000), (long long)(f->t_us % 1000000),
                f->mspf, f->cpu_us, f->fence_us,
                c[NV2A_PROF_FINISH_VERTEX_BUFFER_DIRTY], c[NV2A_PROF_FINISH_SURFACE_CREATE],
                c[NV2A_PROF_FINISH_SURFACE_DOWN], c[NV2A_PROF_FINISH_NEED_BUFFER_SPACE],
                c[NV2A_PROF_FINISH_FRAMEBUFFER_DIRTY], c[NV2A_PROF_FINISH_PRESENTING],
                c[NV2A_PROF_FINISH_FLIP_STALL], c[NV2A_PROF_FINISH_FLUSH],
                c[NV2A_PROF_FINISH_STALLED], c[NV2A_PROF_QUEUE_SUBMIT],
                c[NV2A_PROF_BEGIN_ENDS], c[NV2A_PROF_TEX_UPLOAD],
                c[NV2A_PROF_SURF_DOWNLOAD], c[NV2A_PROF_SURF_UPLOAD],
                c[NV2A_PROF_SURF_TO_TEX], c[NV2A_PROF_SURF_TO_TEX_FALLBACK],
                c[NV2A_PROF_PIPELINE_GEN], c[NV2A_PROF_SHADER_GEN], c[NV2A_PROF_CLEAR]);
    }
}

const char *nv2a_profile_get_counter_name(unsigned int cnt)
{
    const char *default_names[NV2A_PROF__COUNT] = {
        #define _X(x) stringify(x),
        NV2A_PROF_COUNTERS_XMAC
        #undef _X
    };

    assert(cnt < NV2A_PROF__COUNT);
    return default_names[cnt] + 10; /* 'NV2A_PROF_' */
}

int nv2a_profile_get_counter_value(unsigned int cnt)
{
    assert(cnt < NV2A_PROF__COUNT);
    unsigned int idx = (g_nv2a_stats.frame_ptr + NV2A_PROF_NUM_FRAMES - 1) %
                       NV2A_PROF_NUM_FRAMES;
    return g_nv2a_stats.frame_history[idx].counters[cnt];
}

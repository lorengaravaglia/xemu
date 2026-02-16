/*
 * QEMU MCPX Audio Processing Unit implementation
 *
 * Copyright (c) 2019-2025 Matt Borgerson
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

#include "apu_int.h"

#if defined(__ANDROID__) || defined(ANDROID)
/*
 * On Android, SDL audio is not initialized (SDL_Init skipped for stability).
 * Route MCPx APU output directly to AAudio (NDK) using a callback-mode stream
 * and a ring buffer that bridges the APU thread (producer) and the AAudio
 * callback thread (consumer).  The APU produces 48000 Hz stereo S16LE audio.
 */
#include <aaudio/AAudio.h>
#include <pthread.h>
#include <string.h>
#include <android/log.h>
#define APU_LOG_TAG "xemu-apu-audio"
#define APU_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  APU_LOG_TAG, __VA_ARGS__)
#define APU_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, APU_LOG_TAG, __VA_ARGS__)

/* Power-of-2 ring buffer; ~340ms at 48kHz stereo S16 */
#define APU_RING_SIZE  65536u
#define APU_RING_MASK  (APU_RING_SIZE - 1)

static uint8_t       g_apu_ring[APU_RING_SIZE];
static size_t        g_apu_ring_rd = 0;   /* consumer (AAudio callback) */
static size_t        g_apu_ring_wr = 0;   /* producer (APU thread)      */
static pthread_mutex_t g_apu_mutex;
static AAudioStream *g_apu_stream = NULL;

static aaudio_data_callback_result_t apu_data_cb(
    AAudioStream *stream, void *user, void *audio_data, int32_t num_frames)
{
    (void)stream; (void)user;
    uint8_t *dst  = audio_data;
    size_t   need = (size_t)num_frames * 4; /* 2 ch * 2 bytes */

    pthread_mutex_lock(&g_apu_mutex);
    size_t avail = g_apu_ring_wr - g_apu_ring_rd;
    size_t copy  = avail < need ? avail : need;
    size_t rd0   = g_apu_ring_rd & APU_RING_MASK;
    size_t tail  = APU_RING_SIZE - rd0;
    if (tail >= copy) {
        memcpy(dst, g_apu_ring + rd0, copy);
    } else {
        memcpy(dst,        g_apu_ring + rd0, tail);
        memcpy(dst + tail, g_apu_ring,       copy - tail);
    }
    g_apu_ring_rd += copy;
    pthread_mutex_unlock(&g_apu_mutex);

    if (copy < need) {
        memset(dst + copy, 0, need - copy); /* silence on underrun */
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
#endif /* ANDROID */

void mcpx_apu_monitor_init(MCPXAPUState *d, Error **errp)
{
    d->monitor.stream = NULL;

#if defined(__ANDROID__) || defined(ANDROID)
    pthread_mutex_init(&g_apu_mutex, NULL);
    g_apu_ring_rd = g_apu_ring_wr = 0;

    AAudioStreamBuilder *builder;
    aaudio_result_t r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK) {
        APU_LOGE("createStreamBuilder: %s", AAudio_convertResultToText(r));
        error_setg(errp, "AAudio_createStreamBuilder failed");
        return;
    }

    AAudioStreamBuilder_setDirection(builder,       AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder,      48000);
    AAudioStreamBuilder_setChannelCount(builder,    2);
    AAudioStreamBuilder_setFormat(builder,          AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder,    apu_data_cb, NULL);

    r = AAudioStreamBuilder_openStream(builder, &g_apu_stream);
    AAudioStreamBuilder_delete(builder);
    if (r != AAUDIO_OK) {
        APU_LOGE("openStream: %s", AAudio_convertResultToText(r));
        error_setg(errp, "AAudioStreamBuilder_openStream failed");
        return;
    }

    r = AAudioStream_requestStart(g_apu_stream);
    if (r != AAUDIO_OK) {
        APU_LOGE("requestStart: %s", AAudio_convertResultToText(r));
        /* non-fatal: stream opened but may not play */
    }
    APU_LOGI("APU audio stream opened: 48000 Hz, 2 ch, S16LE");
#else
    SDL_AudioSpec spec = {
        .freq = 48000,
        .format = SDL_AUDIO_S16LE,
        .channels = 2,
    };

    if (!SDL_Init(SDL_INIT_AUDIO)) {
        error_setg(errp, "SDL_Init failed: %s", SDL_GetError());
        return;
    }

    d->monitor.stream = SDL_OpenAudioDeviceStream(
        SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, NULL, NULL);
    if (d->monitor.stream == NULL) {
        error_setg(errp, "SDL_OpenAudioDeviceStream failed: %s",
                   SDL_GetError());
        return;
    }

    SDL_AudioDeviceID dev = SDL_GetAudioStreamDevice(d->monitor.stream);

    SDL_AudioSpec dev_spec;
    int dev_buf_frames = 0;
    int dev_drain_bytes = 0;
    if (SDL_GetAudioDeviceFormat(dev, &dev_spec, &dev_buf_frames)) {
        dev_drain_bytes = dev_buf_frames * spec.channels *
                          SDL_AUDIO_BYTESIZE(spec.format) *
                          spec.freq / dev_spec.freq;
    }
    int frame_bytes = sizeof(d->monitor.frame_buf);
    int drain = MAX(dev_drain_bytes, frame_bytes);
    d->monitor.queued_bytes_low = drain;
    d->monitor.queued_bytes_high = 3 * drain;

    SDL_ResumeAudioDevice(dev);
#endif
}

void mcpx_apu_monitor_finalize(MCPXAPUState *d)
{
#if defined(__ANDROID__) || defined(ANDROID)
    if (g_apu_stream) {
        AAudioStream_requestStop(g_apu_stream);
        AAudioStream_close(g_apu_stream);
        g_apu_stream = NULL;
    }
    pthread_mutex_destroy(&g_apu_mutex);
#else
    if (d->monitor.stream) {
        SDL_DestroyAudioStream(d->monitor.stream);
    }
#endif
    (void)d;
}

void mcpx_apu_monitor_frame(MCPXAPUState *d)
{
    if ((d->ep_frame_div + 1) % 8) {
        return;
    }

#if defined(__ANDROID__) || defined(ANDROID)
    if (g_apu_stream) {
        const uint8_t *src  = (const uint8_t *)d->monitor.frame_buf;
        size_t         size = sizeof(d->monitor.frame_buf);

        pthread_mutex_lock(&g_apu_mutex);
        size_t used  = g_apu_ring_wr - g_apu_ring_rd;
        size_t space = APU_RING_SIZE - used;
        if (space > 0) space--;
        size_t copy  = size < space ? size : space;
        size_t wr0   = g_apu_ring_wr & APU_RING_MASK;
        size_t tail  = APU_RING_SIZE - wr0;
        if (tail >= copy) {
            memcpy(g_apu_ring + wr0, src, copy);
        } else {
            memcpy(g_apu_ring + wr0, src,                tail);
            memcpy(g_apu_ring,       src + tail, copy - tail);
        }
        g_apu_ring_wr += copy;
        pthread_mutex_unlock(&g_apu_mutex);
    }
#else
    if (d->monitor.stream) {
        float vu = pow(fmax(0.0, fmin(g_config.audio.volume_limit, 1.0)), M_E);
        SDL_SetAudioStreamGain(d->monitor.stream, vu);
        SDL_PutAudioStreamData(d->monitor.stream, d->monitor.frame_buf,
                            sizeof(d->monitor.frame_buf));
    }
#endif

    memset(d->monitor.frame_buf, 0, sizeof(d->monitor.frame_buf));
}

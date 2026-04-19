/*
 * AAudio backend for Android (QEMU audio subsystem)
 *
 * Routes QEMU PCM output to Android AAudio using a callback-mode stream
 * and a power-of-2 ring buffer to bridge the QEMU audio thread (producer)
 * and the AAudio callback thread (consumer).
 *
 * Registered explicitly via aaudio_register_driver() called from
 * xemu_android.c before xemu_core_main(), rather than with type_init(),
 * because this file is compiled by CMake (not Meson) to access NDK headers.
 *
 * Input voices are not implemented (Xbox games do not use mic input).
 */

#include "qemu/osdep.h"
#include "qemu/module.h"
#include "qemu/audio.h"

#define AUDIO_CAP "aaudio"
#include "audio_int.h"

#include <aaudio/AAudio.h>
#include <pthread.h>
#include <android/log.h>

#define AAUDIO_LOG_TAG "xemu-audio"
#define ALOGI_A(...) __android_log_print(ANDROID_LOG_INFO,  AAUDIO_LOG_TAG, __VA_ARGS__)
#define ALOGE_A(...) __android_log_print(ANDROID_LOG_ERROR, AAUDIO_LOG_TAG, __VA_ARGS__)

/*
 * Ring buffer: power-of-2 size, ~340ms at 48kHz stereo 16-bit.
 * ever-increasing rd/wr counters (not masked on update) allow simple
 * unsigned subtraction to compute occupancy without wrap-around issues.
 */
#define RING_SIZE  65536u   /* must be power of 2 */
#define RING_MASK  (RING_SIZE - 1)

typedef struct AAudioVoiceOut {
    HWVoiceOut  hw;
    AAudioStream *stream;
    uint8_t     ring[RING_SIZE];
    size_t      ring_rd;        /* advanced by AAudio callback (consumer) */
    size_t      ring_wr;        /* advanced by QEMU audio thread (producer) */
    pthread_mutex_t mutex;
} AAudioVoiceOut;

/* ── AAudio callback ──────────────────────────────────────────────────────── */

static aaudio_data_callback_result_t aaudio_data_cb(
    AAudioStream *stream, void *user, void *audio_data, int32_t num_frames)
{
    AAudioVoiceOut *a   = user;
    uint8_t        *dst = audio_data;
    size_t          need = (size_t)num_frames * (size_t)a->hw.info.bytes_per_frame;

    pthread_mutex_lock(&a->mutex);

    size_t avail = a->ring_wr - a->ring_rd;        /* unsigned; wr >= rd always */
    size_t copy  = avail < need ? avail : need;

    /* Two-segment memcpy around the ring */
    size_t rd0   = a->ring_rd & RING_MASK;
    size_t tail  = RING_SIZE - rd0;
    if (tail >= copy) {
        memcpy(dst, a->ring + rd0, copy);
    } else {
        memcpy(dst,        a->ring + rd0, tail);
        memcpy(dst + tail, a->ring,       copy - tail);
    }
    a->ring_rd += copy;

    pthread_mutex_unlock(&a->mutex);

    /* Pad with silence on underrun */
    if (copy < need) {
        memset(dst + copy, 0, need - copy);
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

/* ── Voice init / fini / enable ──────────────────────────────────────────── */

static int aaudio_init_out(HWVoiceOut *hw, struct audsettings *as,
                           void *drv_opaque)
{
    AAudioVoiceOut *a = (AAudioVoiceOut *)hw;

    audio_pcm_init_info(&hw->info, as);
    hw->samples = RING_SIZE / hw->info.bytes_per_frame;

    a->ring_rd = a->ring_wr = 0;
    pthread_mutex_init(&a->mutex, NULL);

    /* Map QEMU format to AAudio format; fall back to S16 */
    aaudio_format_t fmt = (as->fmt == AUDIO_FORMAT_F32)
                          ? AAUDIO_FORMAT_PCM_FLOAT
                          : AAUDIO_FORMAT_PCM_I16;

    AAudioStreamBuilder *builder;
    aaudio_result_t r = AAudio_createStreamBuilder(&builder);
    if (r != AAUDIO_OK) {
        ALOGE_A("createStreamBuilder: %s", AAudio_convertResultToText(r));
        pthread_mutex_destroy(&a->mutex);
        return -1;
    }

    AAudioStreamBuilder_setDirection(builder,       AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder,      as->freq);
    AAudioStreamBuilder_setChannelCount(builder,    as->nchannels);
    AAudioStreamBuilder_setFormat(builder,          fmt);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder,    aaudio_data_cb, a);

    r = AAudioStreamBuilder_openStream(builder, &a->stream);
    AAudioStreamBuilder_delete(builder);

    if (r != AAUDIO_OK) {
        ALOGE_A("openStream: %s", AAudio_convertResultToText(r));
        pthread_mutex_destroy(&a->mutex);
        return -1;
    }

    ALOGI_A("init_out: %d Hz, %d ch, fmt=%d", as->freq, as->nchannels, fmt);
    return 0;
}

static void aaudio_fini_out(HWVoiceOut *hw)
{
    AAudioVoiceOut *a = (AAudioVoiceOut *)hw;
    if (a->stream) {
        AAudioStream_close(a->stream);
        a->stream = NULL;
    }
    pthread_mutex_destroy(&a->mutex);
}

static void aaudio_enable_out(HWVoiceOut *hw, bool enable)
{
    AAudioVoiceOut *a = (AAudioVoiceOut *)hw;
    if (!a->stream) return;
    aaudio_result_t r;
    if (enable) {
        r = AAudioStream_requestStart(a->stream);
        if (r != AAUDIO_OK) {
            ALOGE_A("requestStart: %s", AAudio_convertResultToText(r));
        }
    } else {
        AAudioStream_requestPause(a->stream);
    }
}

/* ── Data transfer ───────────────────────────────────────────────────────── */

static size_t aaudio_write(HWVoiceOut *hw, void *buf, size_t size)
{
    AAudioVoiceOut *a = (AAudioVoiceOut *)hw;

    pthread_mutex_lock(&a->mutex);

    size_t used  = a->ring_wr - a->ring_rd;
    size_t space = RING_SIZE - used;           /* never negative: used <= RING_SIZE */
    /* Reserve 1 byte so ring_wr != ring_rd never becomes ambiguous (full vs empty) */
    if (space > 0) space--;
    size_t copy  = size < space ? size : space;

    size_t wr0   = a->ring_wr & RING_MASK;
    size_t tail  = RING_SIZE - wr0;
    if (tail >= copy) {
        memcpy(a->ring + wr0, buf, copy);
    } else {
        memcpy(a->ring + wr0, buf,                  tail);
        memcpy(a->ring,       (uint8_t *)buf + tail, copy - tail);
    }
    a->ring_wr += copy;

    pthread_mutex_unlock(&a->mutex);
    return copy;
}

static size_t aaudio_buffer_get_free(HWVoiceOut *hw)
{
    AAudioVoiceOut *a = (AAudioVoiceOut *)hw;
    pthread_mutex_lock(&a->mutex);
    size_t used  = a->ring_wr - a->ring_rd;
    size_t space = RING_SIZE - used;
    if (space > 0) space--;
    pthread_mutex_unlock(&a->mutex);
    return space;
}

/* ── Driver registration ─────────────────────────────────────────────────── */

static void *aaudio_audio_init(Audiodev *dev, Error **errp)
{
    return (void *)1;   /* non-NULL = success; no global driver state needed */
}

static void aaudio_audio_fini(void *opaque) {}

static struct audio_pcm_ops aaudio_pcm_ops = {
    .init_out        = aaudio_init_out,
    .fini_out        = aaudio_fini_out,
    .write           = aaudio_write,
    .buffer_get_free = aaudio_buffer_get_free,
    .run_buffer_out  = audio_generic_run_buffer_out,
    .enable_out      = aaudio_enable_out,
};

static struct audio_driver aaudio_driver = {
    .name           = "aaudio",
    .init           = aaudio_audio_init,
    .fini           = aaudio_audio_fini,
    .pcm_ops        = &aaudio_pcm_ops,
    .max_voices_out = 1,
    .max_voices_in  = 0,
    .voice_size_out = sizeof(AAudioVoiceOut),
    .voice_size_in  = 0,
};

/*
 * Called from xemu_android.c before xemu_core_main() so QEMU's audio
 * subsystem can find the "aaudio" driver by name.
 */
void aaudio_register_driver(void)
{
    audio_driver_register(&aaudio_driver);
}

/*
 * HRTF Filter
 *
 * Copyright (c) 2025 Matt Borgerson
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

#ifndef HW_XBOX_MCPX_HRTF_H
#define HW_XBOX_MCPX_HRTF_H

#include <string.h>
#include <stddef.h>
#include <math.h>

// vaddvq_f32 (horizontal add) requires ARMv8/AArch64 NEON, not available on
// 32-bit ARM NEON; this project only targets aarch64 (macOS Apple Silicon,
// Android arm64), so gate strictly on __aarch64__.
#if defined(__aarch64__)
#include <arm_neon.h>
#define HRTF_USE_NEON 1
#endif

#include "hw/xbox/mcpx/apu/apu_regs.h"

#define HRTF_SAMPLES_PER_FRAME  NUM_SAMPLES_PER_FRAME
#define HRTF_NUM_TAPS           31
#define HRTF_MAX_DELAY_SAMPLES  42
#define HRTF_BUFLEN             (HRTF_NUM_TAPS + HRTF_MAX_DELAY_SAMPLES)
#define HRTF_PARAM_SMOOTH_ALPHA 0.01f

// Round HRTF_BUFLEN up to the next power of 2 so the ring buffer write
// position can wrap with bitwise AND instead of integer modulo.
// HRTF_BUF_SIZE must satisfy: HRTF_BUF_SIZE >= HRTF_BUFLEN and is a power of 2.
#define HRTF_BUF_SIZE           128
#define HRTF_BUF_MASK           (HRTF_BUF_SIZE - 1)

// The per-channel sample buffer is mirrored: buf[pos] and buf[pos +
// HRTF_BUF_SIZE] always hold the same value. This lets any read window up to
// HRTF_BUF_SIZE samples wide be addressed as one contiguous run (by reading
// from the mirrored half when the window would otherwise wrap), avoiding a
// per-tap mask/modulo in hrtf_filter_process's inner convolution loop and
// making that loop auto-vectorizable / NEON-friendly.
#define HRTF_PHYS_BUF_SIZE      (2 * HRTF_BUF_SIZE)

// Per-frame equivalent of HRTF_PARAM_SMOOTH_ALPHA applied per sample.
// alpha_frame = 1 - (1 - alpha_sample)^HRTF_SAMPLES_PER_FRAME
// = 1 - 0.99^32 ≈ 0.275f. This yields the same exponential time constant
// when applied once per frame instead of once per sample.
#define HRTF_PARAM_SMOOTH_ALPHA_FRAME 0.275f

typedef struct {
    int buf_pos;
    struct {
        float buf[HRTF_PHYS_BUF_SIZE];
        float hrir_coeff_cur[HRTF_NUM_TAPS];
        float hrir_coeff_tar[HRTF_NUM_TAPS];
        // hrir_coeff_cur reversed (coeff_rev[j] = hrir_coeff_cur[NUM_TAPS-1-j]),
        // recomputed once per frame in hrtf_filter_step_parameters. Lets the
        // per-sample convolution read both the coefficients and the sample
        // window forward/contiguously instead of indexing coeff backward.
        float hrir_coeff_cur_rev[HRTF_NUM_TAPS];
    } ch[2];
    float itd_cur;
    float itd_tar;
} HrtfFilter;

static inline void hrtf_filter_init(HrtfFilter *f)
{
    memset(f, 0, sizeof(*f));
}

static inline void hrtf_filter_clear_history(HrtfFilter *f)
{
    f->buf_pos = 0;
    memset(f->ch[0].buf, 0, sizeof(f->ch[0].buf));
    memset(f->ch[1].buf, 0, sizeof(f->ch[1].buf));
}

static inline void
hrtf_filter_set_target_params(HrtfFilter *f, float hrir_coeff[2][HRTF_NUM_TAPS],
                              float itd)
{
    f->itd_tar =
        fmaxf(-HRTF_MAX_DELAY_SAMPLES, fminf(itd, HRTF_MAX_DELAY_SAMPLES));

    for (int ch = 0; ch < 2; ch++) {
        float *coeff = f->ch[ch].hrir_coeff_tar;
        memcpy(coeff, hrir_coeff[ch], sizeof(f->ch[ch].hrir_coeff_tar));

        // Normalize coefficients for unity filter gain
        float s = 0.0f;
        for (int k = 0; k < HRTF_NUM_TAPS; k++) {
            s += fabsf(coeff[k]);
        }
        if (s == 0.0f || s == 1.0f) {
            break;
        }
        for (int k = 0; k < HRTF_NUM_TAPS; k++) {
            coeff[k] /= s;
        }
    }
}

static inline float hrtf_filter_smooth_param(float cur, float tar)
{
    // FIXME: Match hardware parameter transition
    // Uses per-frame alpha; called once per frame, not per sample.
    return cur + HRTF_PARAM_SMOOTH_ALPHA_FRAME * (tar - cur);
}

static inline void hrtf_filter_step_parameters(HrtfFilter *f)
{
    for (int ch = 0; ch < 2; ch++) {
        float *coeff_cur = f->ch[ch].hrir_coeff_cur;
        float *coeff_tar = f->ch[ch].hrir_coeff_tar;
        float *coeff_rev = f->ch[ch].hrir_coeff_cur_rev;
        for (int k = 0; k < HRTF_NUM_TAPS; k++) {
            coeff_cur[k] = hrtf_filter_smooth_param(coeff_cur[k], coeff_tar[k]);
        }
        for (int k = 0; k < HRTF_NUM_TAPS; k++) {
            coeff_rev[k] = coeff_cur[HRTF_NUM_TAPS - 1 - k];
        }
    }
    f->itd_cur = hrtf_filter_smooth_param(f->itd_cur, f->itd_tar);
}

// Dot product of two contiguous HRTF_NUM_TAPS-length float arrays.
static inline float hrtf_dot(const float *a, const float *b)
{
#ifdef HRTF_USE_NEON
    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);
    int k = 0;
    for (; k + 8 <= HRTF_NUM_TAPS; k += 8) {
        acc0 = vmlaq_f32(acc0, vld1q_f32(&a[k]), vld1q_f32(&b[k]));
        acc1 = vmlaq_f32(acc1, vld1q_f32(&a[k + 4]), vld1q_f32(&b[k + 4]));
    }
    for (; k + 4 <= HRTF_NUM_TAPS; k += 4) {
        acc0 = vmlaq_f32(acc0, vld1q_f32(&a[k]), vld1q_f32(&b[k]));
    }
    float acc = vaddvq_f32(vaddq_f32(acc0, acc1));
    for (; k < HRTF_NUM_TAPS; k++) {
        acc += a[k] * b[k];
    }
    return acc;
#else
    float acc = 0.0f;
    for (int k = 0; k < HRTF_NUM_TAPS; k++) {
        acc += a[k] * b[k];
    }
    return acc;
#endif
}

static inline void hrtf_filter_process(HrtfFilter *f,
                                       float in[HRTF_SAMPLES_PER_FRAME][2],
                                       float out[HRTF_SAMPLES_PER_FRAME][2])
{
    // Step parameters once per frame (equivalent time constant to per-sample
    // with HRTF_PARAM_SMOOTH_ALPHA via HRTF_PARAM_SMOOTH_ALPHA_FRAME).
    hrtf_filter_step_parameters(f);

    for (int n = 0; n < HRTF_SAMPLES_PER_FRAME; n++) {
        for (int ch = 0; ch < 2; ch++) {
            float *buf = f->ch[ch].buf;
            float *coeff_rev = f->ch[ch].hrir_coeff_cur_rev;

            // Push new sample, mirrored so any HRTF_BUF_SIZE-wide read
            // window starting at [f->buf_pos, f->buf_pos + HRTF_BUF_SIZE)
            // is contiguous in physical memory (see HRTF_PHYS_BUF_SIZE).
            buf[f->buf_pos] = in[n][ch];
            buf[f->buf_pos + HRTF_BUF_SIZE] = in[n][ch];

            // Interaural time difference (channel delay)
            float d = f->itd_cur * (ch == 0 ? +1.0f : -1.0f);
            if (d < 0.0f) {
                d = 0.0f;
            }
            int di = (int)d;
            float dfrac = d - di;

            // HRIR convolution: acc = sum_{k=0}^{N-1} coeff[k] * buf[phys_base - k].
            // Substituting j = N-1-k turns this into a plain dot product of
            // coeff_rev (coeff_rev[j] = coeff[N-1-j]) against the contiguous
            // window buf[phys_base-(N-1) .. phys_base], no per-tap index math.
            // The dfrac term reads the same window shifted back by 1 sample,
            // which by the same substitution starts at phys_base - N.
            // Range check: phys_base = f->buf_pos - di + HRTF_BUF_SIZE spans
            // [HRTF_BUF_SIZE - HRTF_MAX_DELAY_SAMPLES, 2*HRTF_BUF_SIZE - 1]
            // = [86, 255], so the dfrac window's lowest index is
            // 86 - HRTF_NUM_TAPS = 55, always within [0, HRTF_PHYS_BUF_SIZE).
            int phys_base = f->buf_pos - di + HRTF_BUF_SIZE;
            float acc;
            if (dfrac > 0.0f) {
                float w0 = 1.0f - dfrac;
                acc = w0 * hrtf_dot(coeff_rev, &buf[phys_base - (HRTF_NUM_TAPS - 1)]) +
                      dfrac * hrtf_dot(coeff_rev, &buf[phys_base - HRTF_NUM_TAPS]);
            } else {
                acc = hrtf_dot(coeff_rev, &buf[phys_base - (HRTF_NUM_TAPS - 1)]);
            }

            out[n][ch] = acc;
        }

        f->buf_pos = (f->buf_pos + 1) & HRTF_BUF_MASK;
    }
}

#endif

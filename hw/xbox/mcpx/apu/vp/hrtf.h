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

#include "hw/xbox/mcpx/apu/apu_regs.h"

#define HRTF_SAMPLES_PER_FRAME  NUM_SAMPLES_PER_FRAME
#define HRTF_NUM_TAPS           31
#define HRTF_MAX_DELAY_SAMPLES  42
#define HRTF_BUFLEN             (HRTF_NUM_TAPS + HRTF_MAX_DELAY_SAMPLES)
#define HRTF_PARAM_SMOOTH_ALPHA 0.01f

// Round HRTF_BUFLEN up to the next power of 2 so the ring buffer wrap can use
// bitwise AND instead of integer modulo (% 73 requires a divide on every tap).
// HRTF_BUF_SIZE must satisfy: HRTF_BUF_SIZE >= HRTF_BUFLEN and is a power of 2.
// Validity: max look-back = HRTF_MAX_DELAY_SAMPLES + HRTF_NUM_TAPS - 1 = 72,
// which is < 128, so the unused entries [73..127] are never accessed.
#define HRTF_BUF_SIZE           128
#define HRTF_BUF_MASK           (HRTF_BUF_SIZE - 1)

// Per-frame equivalent of HRTF_PARAM_SMOOTH_ALPHA applied per sample.
// alpha_frame = 1 - (1 - alpha_sample)^HRTF_SAMPLES_PER_FRAME
// = 1 - 0.99^32 ≈ 0.275f. This yields the same exponential time constant
// when applied once per frame instead of once per sample.
#define HRTF_PARAM_SMOOTH_ALPHA_FRAME 0.275f

typedef struct {
    int buf_pos;
    struct {
        float buf[HRTF_BUF_SIZE];
        float hrir_coeff_cur[HRTF_NUM_TAPS];
        float hrir_coeff_tar[HRTF_NUM_TAPS];
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
        for (int k = 0; k < HRTF_NUM_TAPS; k++) {
            coeff_cur[k] = hrtf_filter_smooth_param(coeff_cur[k], coeff_tar[k]);
        }
    }
    f->itd_cur = hrtf_filter_smooth_param(f->itd_cur, f->itd_tar);
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
            float *coeff = f->ch[ch].hrir_coeff_cur;

            // Push new sample
            buf[f->buf_pos] = in[n][ch];

            // Interaural time difference (channel delay)
            float d = f->itd_cur * (ch == 0 ? +1.0f : -1.0f);
            if (d < 0.0f) {
                d = 0.0f;
            }
            int di = (int)d;
            float dfrac = d - di;

            // HRIR Convolution — two paths to hoist the dfrac branch out of
            // the inner loop. Indices wrap via bitwise AND (HRTF_BUF_MASK=127,
            // power-of-2 size) instead of % 73, eliminating integer division.
            float acc = 0.0f;
            int base = f->buf_pos - di;
            if (dfrac > 0.0f) {
                float w0 = 1.0f - dfrac;
                for (int k = 0; k < HRTF_NUM_TAPS; k++) {
                    int idx1 = (base - k) & HRTF_BUF_MASK;
                    int idx2 = (idx1 - 1) & HRTF_BUF_MASK;
                    acc += coeff[k] * (buf[idx1] * w0 + buf[idx2] * dfrac);
                }
            } else {
                for (int k = 0; k < HRTF_NUM_TAPS; k++) {
                    acc += coeff[k] * buf[(base - k) & HRTF_BUF_MASK];
                }
            }

            out[n][ch] = acc;
        }

        f->buf_pos = (f->buf_pos + 1) & HRTF_BUF_MASK;
    }
}

#endif

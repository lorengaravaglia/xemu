/*
 * Geforce NV2A PGRAPH GLSL Shader Generator
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

#include "hw/xbox/nv2a/pgraph/pgraph.h"
#include "shaders.h"

/*
 * Masked dirty check (debug.xemu.shader_dirty, read by the renderer):
 *   0  any change to a watched register rebuilds the state (upstream)
 *   1  only a change to the bits the state setters below read (default)
 *   2  as 1, and when the upstream check alone would have rebuilt, rebuild
 *      anyway and count states that differ (misses: a mask is wrong)
 * A register's dirty bit only says it changed since the last draw, and many
 * of these registers mix shader inputs with fixed-function state (CONTROL_0
 * holds the depth func and colour mask, TEXFMT the mip count).  Mode 1
 * compares the read bits against their values when the state was last
 * built.  Keep the masks in step with pgraph_glsl_set_{vsh,geom,psh}_state.
 */
int g_shader_dirty_mode = 1;
uint64_t g_shader_dirty_missed;

#define TEXCTL0_SHADER_MASK                                          \
    (NV_PGRAPH_TEXCTL0_0_ENABLE | NV_PGRAPH_TEXCTL0_0_ALPHAKILLEN |  \
     NV_PGRAPH_TEXCTL0_0_COLORKEYMODE)
#define TEXFILTER_SHADER_MASK                                        \
    (NV_PGRAPH_TEXFILTER0_MIN | NV_PGRAPH_TEXFILTER0_CONVOLUTION_KERNEL)

static const struct {
    unsigned int reg;
    uint32_t mask;
} shader_regs[] = {
    { NV_PGRAPH_COMBINECTL, 0xFFFFFFFF },
    { NV_PGRAPH_COMBINESPECFOG0, 0xFFFFFFFF },
    { NV_PGRAPH_COMBINESPECFOG1, 0xFFFFFFFF },
    { NV_PGRAPH_CONTROL_0, NV_PGRAPH_CONTROL_0_ALPHAFUNC |
                           NV_PGRAPH_CONTROL_0_ALPHATESTENABLE |
                           NV_PGRAPH_CONTROL_0_Z_PERSPECTIVE_ENABLE },
    { NV_PGRAPH_CONTROL_3, NV_PGRAPH_CONTROL_3_PROVOKING_VERTEX |
                           NV_PGRAPH_CONTROL_3_SHADEMODE |
                           NV_PGRAPH_CONTROL_3_FOGENABLE |
                           NV_PGRAPH_CONTROL_3_FOG_MODE },
    { NV_PGRAPH_CSV0_C, 0xFFFFFFFF },
    { NV_PGRAPH_CSV0_D, 0xFFFFFFFF },
    { NV_PGRAPH_CSV1_A, 0xFFFFFFFF },
    { NV_PGRAPH_CSV1_B, 0xFFFFFFFF },
    { NV_PGRAPH_POINTSIZE, 0xFFFFFFFF },
    { NV_PGRAPH_SETUPRASTER, NV_PGRAPH_SETUPRASTER_FRONTFACEMODE |
                             NV_PGRAPH_SETUPRASTER_BACKFACEMODE |
                             NV_PGRAPH_SETUPRASTER_POINTSMOOTHENABLE |
                             NV_PGRAPH_SETUPRASTER_Z_FORMAT |
                             NV_PGRAPH_SETUPRASTER_WINDOWCLIPTYPE },
    { NV_PGRAPH_SHADERCLIPMODE, 0xFFFFFFFF },
    { NV_PGRAPH_SHADERCTL, 0xFFFFFFFF },
    { NV_PGRAPH_SHADERPROG, 0xFFFFFFFF },
    { NV_PGRAPH_SHADOWCTL, NV_PGRAPH_SHADOWCTL_SHADOW_ZFUNC },
    { NV_PGRAPH_ZCOMPRESSOCCLUDE, NV_PGRAPH_ZCOMPRESSOCCLUDE_ZCLAMP_EN },
};

/* The watched registers' values when the current state was built. */
static uint32_t shader_regs_snap[0x2000 / sizeof(uint32_t)];

static void snapshot_shader_regs(PGRAPHState *pg)
{
    for (int i = 0; i < ARRAY_SIZE(shader_regs); i++) {
        unsigned int r = shader_regs[i].reg;
        shader_regs_snap[r / 4] = pgraph_reg_r(pg, r);
    }
    for (int i = 0; i < 8; i++) {
        unsigned int regs[] = {
            NV_PGRAPH_COMBINEALPHAI0, NV_PGRAPH_COMBINEALPHAO0,
            NV_PGRAPH_COMBINECOLORI0, NV_PGRAPH_COMBINECOLORO0,
        };
        for (int j = 0; j < ARRAY_SIZE(regs); j++) {
            unsigned int r = regs[j] + i * 4;
            shader_regs_snap[r / 4] = pgraph_reg_r(pg, r);
        }
    }
    for (int i = 0; i < 4; i++) {
        unsigned int regs[] = {
            NV_PGRAPH_TEXCTL0_0, NV_PGRAPH_TEXFILTER0, NV_PGRAPH_TEXFMT0,
        };
        for (int j = 0; j < ARRAY_SIZE(regs); j++) {
            unsigned int r = regs[j] + i * 4;
            shader_regs_snap[r / 4] = pgraph_reg_r(pg, r);
        }
    }
}

/*
 * The bits of a texture stage's registers that pgraph_glsl_set_psh_state
 * reads: none while the stage is disabled (the state is left zeroed);
 * otherwise the base size only when the border comes from the texture, and
 * of the colour format only whether it is linear, X8Y24 or depth.
 */
typedef struct TexShaderBits {
    uint32_t ctl, filter, fmt;
} TexShaderBits;

static TexShaderBits tex_shader_bits(uint32_t shaderprog, uint32_t ctl,
                                     uint32_t filter, uint32_t fmt, int stage)
{
    TexShaderBits b = { 0, 0, 0 };
    uint32_t mode = (shaderprog >> (stage * 5)) & 0x1F;
    if (mode == 0 || mode == 4 || !(ctl & NV_PGRAPH_TEXCTL0_0_ENABLE)) {
        return b; /* as pgraph_is_texture_stage_active() */
    }
    /* The colour format only for the properties the setter takes from it */
    unsigned int color = GET_MASK(fmt, NV_PGRAPH_TEXFMT0_COLOR);
    BasicColorFormatInfo f = kelvin_color_format_info_map[color];
    bool x8y24 =
        color == NV097_SET_TEXTURE_FORMAT_COLOR_LU_IMAGE_DEPTH_X8_Y24_FIXED ||
        color == NV097_SET_TEXTURE_FORMAT_COLOR_LU_IMAGE_DEPTH_X8_Y24_FLOAT;
    uint32_t fmt_mask = NV_PGRAPH_TEXFMT0_CUBEMAPENABLE |
                        NV_PGRAPH_TEXFMT0_BORDER_SOURCE |
                        NV_PGRAPH_TEXFMT0_DIMENSIONALITY;
    if (GET_MASK(fmt, NV_PGRAPH_TEXFMT0_BORDER_SOURCE) !=
        NV_PGRAPH_TEXFMT0_BORDER_SOURCE_COLOR) {
        fmt_mask |= NV_PGRAPH_TEXFMT0_BASE_SIZE_U |
                    NV_PGRAPH_TEXFMT0_BASE_SIZE_V |
                    NV_PGRAPH_TEXFMT0_BASE_SIZE_P;
    }
    b.ctl = ctl & TEXCTL0_SHADER_MASK;
    b.filter = filter & TEXFILTER_SHADER_MASK;
    /* COLOR's bits (8-14) are free once it is masked out */
    b.fmt = (fmt & fmt_mask) | (f.linear << 8) | (x8y24 << 9) | (f.depth << 10);
    return b;
}

static inline bool reg_changed(PGRAPHState *pg, unsigned int reg,
                               uint32_t mask, bool masked)
{
    if (!pgraph_is_reg_dirty(pg, reg)) {
        return false;
    }
    return !masked || ((pgraph_reg_r(pg, reg) ^ shader_regs_snap[reg / 4]) &
                       mask);
}

ShaderState pgraph_glsl_get_shader_state(PGRAPHState *pg)
{
    pg->program_data_dirty = false; /* fixme */

    ShaderState state;

    // We will hash it, so make sure any padding is zeroed
    memset(&state, 0, sizeof(ShaderState));

    pgraph_glsl_set_vsh_state(pg, &state.vsh);
    pgraph_glsl_set_geom_state(pg, &state.geom);
    pgraph_glsl_set_psh_state(pg, &state.psh);

    snapshot_shader_regs(pg);

    return state;
}

/*
 * Which inputs say the state is stale, as a mask of SHADER_DIRTY_* bits
 * (0 = clean).  The bits are a diagnostic (why a rebuild happened); callers
 * only test for non-zero.
 */
uint32_t pgraph_glsl_shader_state_dirty_why(PGRAPHState *pg,
                                            const ShaderState *state,
                                            bool masked, bool all)
{
    uint32_t why = 0;

    if (pg->program_data_dirty) {
        why |= SHADER_DIRTY_PROGRAM;
        if (!all) {
            return why;
        }
    }

    for (int i = 0; i < ARRAY_SIZE(shader_regs); i++) {
        if (reg_changed(pg, shader_regs[i].reg, shader_regs[i].mask, masked)) {
            why |= SHADER_DIRTY_REG0 << i;
            if (!all) {
                return why;
            }
        }
    }

    int num_stages = pgraph_reg_r(pg, NV_PGRAPH_COMBINECTL) & 0xFF;
    for (int i = 0; i < num_stages; i++) {
        if (reg_changed(pg, NV_PGRAPH_COMBINEALPHAI0 + i * 4, ~0u, masked) ||
            reg_changed(pg, NV_PGRAPH_COMBINEALPHAO0 + i * 4, ~0u, masked) ||
            reg_changed(pg, NV_PGRAPH_COMBINECOLORI0 + i * 4, ~0u, masked) ||
            reg_changed(pg, NV_PGRAPH_COMBINECOLORO0 + i * 4, ~0u, masked)) {
            why |= SHADER_DIRTY_COMBINER;
            if (!all) {
                return why;
            }
            break;
        }
    }

    if (pg->uniform_attrs != state->vsh.uniform_attrs ||
        pg->swizzle_attrs != state->vsh.swizzle_attrs ||
        pg->compressed_attrs != state->vsh.compressed_attrs ||
        pg->primitive_mode != state->geom.primitive_mode ||
        pg->surface_scale_factor != state->vsh.surface_scale_factor ||
        pg->surface_shape.zeta_format != state->psh.surface_zeta_format) {
        why |= SHADER_DIRTY_ATTRS;
        if (!all) {
            return why;
        }
    }

    for (int i = 0; i < 4; i++) {
        unsigned int ctl = NV_PGRAPH_TEXCTL0_0 + i * 4;
        unsigned int filter = NV_PGRAPH_TEXFILTER0 + i * 4;
        unsigned int fmt = NV_PGRAPH_TEXFMT0 + i * 4;
        if (!masked) {
            why |= pgraph_is_reg_dirty(pg, ctl) ? SHADER_DIRTY_TEXCTL : 0;
            why |= pgraph_is_reg_dirty(pg, filter) ? SHADER_DIRTY_TEXFILTER : 0;
            why |= pgraph_is_reg_dirty(pg, fmt) ? SHADER_DIRTY_TEXFMT : 0;
        } else if (pgraph_is_reg_dirty(pg, ctl) ||
                   pgraph_is_reg_dirty(pg, filter) ||
                   pgraph_is_reg_dirty(pg, fmt)) {
            TexShaderBits now = tex_shader_bits(
                pgraph_reg_r(pg, NV_PGRAPH_SHADERPROG), pgraph_reg_r(pg, ctl),
                pgraph_reg_r(pg, filter), pgraph_reg_r(pg, fmt), i);
            TexShaderBits then = tex_shader_bits(
                shader_regs_snap[NV_PGRAPH_SHADERPROG / 4],
                shader_regs_snap[ctl / 4], shader_regs_snap[filter / 4],
                shader_regs_snap[fmt / 4], i);
            why |= now.ctl != then.ctl ? SHADER_DIRTY_TEXCTL : 0;
            why |= now.filter != then.filter ? SHADER_DIRTY_TEXFILTER : 0;
            why |= now.fmt != then.fmt ? SHADER_DIRTY_TEXFMT : 0;
        }
        if (pg->texture_matrix_enable[i] !=
            state->vsh.fixed_function.texture_matrix_enable[i]) {
            why |= SHADER_DIRTY_TEXMAT;
        }
        if (why && !all) {
            return why;
        }
    }

    return why;
}

bool pgraph_glsl_check_shader_state_dirty(PGRAPHState *pg,
                                          const ShaderState *state)
{
    return pgraph_glsl_shader_state_dirty_why(
               pg, state, qatomic_read(&g_shader_dirty_mode) != 0, false) != 0;
}

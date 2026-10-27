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

#ifndef HW_XBOX_NV2A_PGRAPH_GLSL_SHADERS_H
#define HW_XBOX_NV2A_PGRAPH_GLSL_SHADERS_H

#include "vsh.h"
#include "geom.h"
#include "psh.h"

typedef struct ShaderState {
    VshState vsh;
    GeomState geom;
    PshState psh;
} ShaderState;

typedef struct PGRAPHState PGRAPHState;

ShaderState pgraph_glsl_get_shader_state(PGRAPHState *pg);

bool pgraph_glsl_check_shader_state_dirty(PGRAPHState *pg,
                                          const ShaderState *state);

/* Reasons, for pgraph_glsl_shader_state_dirty_why (a diagnostic). */
enum {
    SHADER_DIRTY_PROGRAM = 1 << 0,
    SHADER_DIRTY_REG0 = 1 << 1, /* + index in the watched register list (16) */
    SHADER_DIRTY_COMBINER = 1 << 17,
    SHADER_DIRTY_ATTRS = 1 << 18,
    SHADER_DIRTY_TEXCTL = 1 << 19,
    SHADER_DIRTY_TEXFILTER = 1 << 20,
    SHADER_DIRTY_TEXFMT = 1 << 21,
    SHADER_DIRTY_TEXMAT = 1 << 22,
    SHADER_DIRTY__COUNT = 23,
};

uint32_t pgraph_glsl_shader_state_dirty_why(PGRAPHState *pg,
                                            const ShaderState *state,
                                            bool masked, bool all);

extern int g_shader_dirty_mode;
extern uint64_t g_shader_dirty_missed;

#endif

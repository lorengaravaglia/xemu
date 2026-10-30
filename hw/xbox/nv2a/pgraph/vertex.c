/*
 * QEMU Geforce NV2A implementation
 *
 * Copyright (c) 2012 espes
 * Copyright (c) 2015 Jannik Vogel
 * Copyright (c) 2018-2024 Matt Borgerson
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
#include "nv2a_vsh_emulator.h"
#include "nv2a_vsh_disassembler.h"

void pgraph_update_inline_value(VertexAttribute *attr, const uint8_t *data)
{
    assert(attr->count <= 4);
    attr->inline_value[0] = 0.0f;
    attr->inline_value[1] = 0.0f;
    attr->inline_value[2] = 0.0f;
    attr->inline_value[3] = 1.0f;

    switch (attr->format) {
        case NV097_SET_VERTEX_DATA_ARRAY_FORMAT_TYPE_UB_D3D:
        case NV097_SET_VERTEX_DATA_ARRAY_FORMAT_TYPE_UB_OGL:
            for (uint32_t i = 0; i < attr->count; ++i) {
                attr->inline_value[i] = (float)data[i] / 255.0f;
            }
            break;
        case NV097_SET_VERTEX_DATA_ARRAY_FORMAT_TYPE_S1: {
            const int16_t *val = (const int16_t *) data;
            for (uint32_t i = 0; i < attr->count; ++i, ++val) {
                attr->inline_value[i] = MAX(-1.0f, (float) *val / 32767.0f);
            }
            break;
        }
        case NV097_SET_VERTEX_DATA_ARRAY_FORMAT_TYPE_F:
            memcpy(attr->inline_value, data, attr->size * attr->count);
            break;
        case NV097_SET_VERTEX_DATA_ARRAY_FORMAT_TYPE_S32K: {
            const int16_t *val = (const int16_t *) data;
            for (uint32_t i = 0; i < attr->count; ++i, ++val) {
                attr->inline_value[i] = (float)*val;
            }
            break;
        }
        case NV097_SET_VERTEX_DATA_ARRAY_FORMAT_TYPE_CMP: {
            /* 3 signed, normalized components packed in 32-bits. (11,11,10) */
            const int32_t val = *(const int32_t *)data;
            int32_t x = val & 0x7FF;
            if (x & 0x400) {
                x |= 0xFFFFF800;
            }
            int32_t y = (val >> 11) & 0x7FF;
            if (y & 0x400) {
                y |= 0xFFFFF800;
            }
            int32_t z = (val >> 22) & 0x7FF;
            if (z & 0x200) {
                z |= 0xFFFFFC00;
            }

            attr->inline_value[0] = MAX(-1.0f, (float)x / 1023.0f);
            attr->inline_value[1] = MAX(-1.0f, (float)y / 1023.0f);
            attr->inline_value[2] = MAX(-1.0f, (float)z / 511.0f);
            break;
        }
    default:
        fprintf(stderr, "Unknown vertex attribute type: for format 0x%x\n",
                attr->format);
        assert(!"Unsupported attribute type");
        break;
    }
}

void pgraph_get_inline_values(PGRAPHState *pg, uint16_t attrs,
                               float values[NV2A_VERTEXSHADER_ATTRIBUTES][4],
                               int *count)
{
    int num_attributes = 0;

    for (int i = 0; i < NV2A_VERTEXSHADER_ATTRIBUTES; i++) {
        if (attrs & (1 << i)) {
            memcpy(values[num_attributes],
                   pg->vertex_attributes[i].inline_value, 4 * sizeof(float));
            num_attributes += 1;
        }
    }

    if (count) {
        *count = num_attributes;
    }
}


void pgraph_allocate_inline_buffer_vertices(PGRAPHState *pg, unsigned int attr)
{
    VertexAttribute *attribute = &pg->vertex_attributes[attr];

    if (attribute->inline_buffer_populated || pg->inline_buffer_length == 0) {
        return;
    }

    /* Now upload the previous attribute value */
    attribute->inline_buffer_populated = true;
    for (int i = 0; i < pg->inline_buffer_length; i++) {
        memcpy(&attribute->inline_buffer[i * 4], attribute->inline_value,
               sizeof(float) * 4);
    }
}

void pgraph_finish_inline_buffer_vertex(PGRAPHState *pg)
{
    pgraph_check_within_begin_end_block(pg);
    assert(pg->inline_buffer_length < NV2A_MAX_BATCH_LENGTH);

    for (int i = 0; i < NV2A_VERTEXSHADER_ATTRIBUTES; i++) {
        VertexAttribute *attribute = &pg->vertex_attributes[i];
        if (attribute->inline_buffer_populated) {
            memcpy(&attribute->inline_buffer[pg->inline_buffer_length * 4],
                   attribute->inline_value, sizeof(float) * 4);
        }
    }

    pg->inline_buffer_length++;
}

void pgraph_reset_inline_buffers(PGRAPHState *pg)
{
    pg->inline_elements_length = 0;
    pg->inline_array_length = 0;
    pg->inline_buffer_length = 0;
    pgraph_reset_draw_arrays(pg);
}

void pgraph_reset_draw_arrays(PGRAPHState *pg)
{
    pg->draw_arrays_length = 0;
    pg->draw_arrays_min_start = -1;
    pg->draw_arrays_max_count = 0;
    pg->draw_arrays_prevent_connect = false;
}

void pgraph_vsh_carryover_invalidate_cache(PGRAPHState *pg)
{
    for (int i = 0; i < ARRAY_SIZE(pg->vsh_carry_cached_programs); ++i) {
        if (pg->vsh_carry_cached_programs[i]) {
            nv2a_vsh_program_destroy(pg->vsh_carry_cached_programs[i]);
            g_free(pg->vsh_carry_cached_programs[i]);
            pg->vsh_carry_cached_programs[i] = NULL;
        }
    }
}

void pgraph_vsh_carryover_reset(PGRAPHState *pg)
{
    pg->vsh_carry_fog[0] = 0.0f;
    pg->vsh_carry_fog[1] = 0.0f;
    pg->vsh_carry_fog[2] = 0.0f;
    pg->vsh_carry_fog[3] = 1.0f;
    pgraph_vsh_carryover_invalidate_cache(pg);
    pg->vsh_carry_cache_dirty = false;
    pg->vsh_carry_dirty_lo = UINT16_MAX;
    pg->vsh_carry_dirty_hi = 0;
}

/*
 * Android port: the carryover runs on the NV2A thread for every draw, ~1,600
 * per frame in Halo, and as upstream wrote it each run cleared and copied the
 * whole 4 KB register file and executed the whole program for the one value
 * it keeps, oFog -- +1.2 ms per frame (FINDINGS CQ).  Programs have no
 * branches, so each cached program is sliced once, when parsed, down to the
 * operations whose results reach oFog, and a run copies in only the constants
 * the slice reads.  The result is exact: a removed operation writes only
 * registers that nothing kept reads afterwards.
 *
 * debug.xemu.vsh_carry: 0 skips the runs, for an A/B of their cost; 1 (the
 * default) runs the slices; 2 also runs upstream's full program and counts
 * any oFog that differs (vk-vshcarry "missed", printed every 10 s).
 */
typedef struct VshCarrySlice {
    Nv2aVshProgram program;     /* first: freed as a program by invalidate */
    uint16_t length;            /* program slots up to the final step */
    bool skip;                  /* never writes oFog: the value passes through */
    bool all_consts;            /* reads c[a0 + n]: copy every constant */
    uint8_t n_consts;
    uint8_t consts[NV2A_VERTEXSHADER_CONSTANTS];
} VshCarrySlice;

int g_vsh_carry = 1;
uint64_t g_vsh_carry_runs, g_vsh_carry_parses, g_vsh_carry_skips,
    g_vsh_carry_missed;

typedef struct SliceLiveness {
    bool out[13];
    uint32_t temps;
    bool a0;
    bool ctx_relative;
    bool ctx[NV2A_VERTEXSHADER_CONSTANTS];
} SliceLiveness;

static bool slice_op_needed(const SliceLiveness *l, const Nv2aVshOperation *op)
{
    if (op->opcode == NV2AOP_NOP) {
        return false;
    }
    for (int i = 0; i < 2; i++) {
        const Nv2aVshOutput *o = &op->outputs[i];
        switch (o->type) {
        case NV2ART_OUTPUT:
            if (o->index < 13 && l->out[o->index]) {
                return true;
            }
            break;
        case NV2ART_TEMPORARY:
            if (l->temps & (1u << o->index)) {
                return true;
            }
            break;
        case NV2ART_ADDRESS:
            if (l->a0) {
                return true;
            }
            break;
        case NV2ART_CONTEXT:
            if (l->ctx_relative ||
                (o->index < NV2A_VERTEXSHADER_CONSTANTS && l->ctx[o->index])) {
                return true;
            }
            break;
        default:
            break;
        }
    }
    return false;
}

static void slice_add_inputs(SliceLiveness *l, const Nv2aVshOperation *op,
                             int n_inputs)
{
    for (int i = 0; i < n_inputs; i++) {
        const Nv2aVshInput *in = &op->inputs[i];
        if (in->type == NV2ART_NONE) {
            break;
        }
        if (in->type == NV2ART_TEMPORARY) {
            if (in->index == 12) {
                l->out[NV2AOR_POS] = true;   /* r12 reads oPos */
            } else {
                l->temps |= 1u << in->index;
            }
        } else if (in->type == NV2ART_CONTEXT) {
            if (in->is_relative) {
                l->a0 = true;
                l->ctx_relative = true;
            } else if (in->index < NV2A_VERTEXSHADER_CONSTANTS) {
                l->ctx[in->index] = true;
            }
        }
    }
}

/* Slice a parsed program in place to the operations that reach oFog. */
static void slice_program(VshCarrySlice *s)
{
    Nv2aVshStep *steps = s->program.steps;
    int n = 0;
    while (!steps[n].is_final) {
        n++;
    }
    n++;
    s->length = n;

    /*
     * Backwards, with no kills: a register is live if any kept operation
     * after this point reads it.  That over-approximates (partial write
     * masks need no special case) and only ever keeps more.  Both operations
     * of a step are judged before either's inputs are added, since a step
     * reads its inputs before it writes.
     */
    SliceLiveness l = { 0 };
    l.out[NV2AOR_FOG_COORD] = true;
    for (int i = n - 1; i >= 0; i--) {
        bool mac = slice_op_needed(&l, &steps[i].mac);
        bool ilu = slice_op_needed(&l, &steps[i].ilu);
        if (mac) {
            slice_add_inputs(&l, &steps[i].mac, 3);
        } else {
            steps[i].mac.opcode = NV2AOP_NOP;
        }
        if (ilu) {
            slice_add_inputs(&l, &steps[i].ilu, 1);
        } else {
            steps[i].ilu.opcode = NV2AOP_NOP;
        }
    }

    int kept = 0;
    for (int i = 0; i < n; i++) {
        if (steps[i].mac.opcode != NV2AOP_NOP ||
            steps[i].ilu.opcode != NV2AOP_NOP) {
            steps[kept] = steps[i];
            steps[kept].is_final = false;
            kept++;
        }
    }
    s->skip = !kept;
    if (kept) {
        steps[kept - 1].is_final = true;
    }

    s->all_consts = l.ctx_relative;
    s->n_consts = 0;
    for (int i = 0; i < NV2A_VERTEXSHADER_CONSTANTS && !s->all_consts; i++) {
        if (l.ctx[i]) {
            s->consts[s->n_consts++] = i;
        }
    }
}

static void carry_init_state(PGRAPHState *pg, Nv2aVshCPUFullExecutionState *es)
{
    memset(es->temp_regs, 0, sizeof(es->temp_regs));
    memset(es->address_reg, 0, sizeof(es->address_reg));
    for (int i = 0; i < ARRAY_SIZE(pg->vertex_attributes); ++i) {
        memcpy(&es->input_regs[i * 4], pg->vertex_attributes[i].inline_value,
               sizeof(float) * 4);
    }
    for (int i = 0; i < ARRAY_SIZE(es->output_regs) / 4; ++i) {
        es->output_regs[i * 4 + 0] = 0.0f;
        es->output_regs[i * 4 + 1] = 0.0f;
        es->output_regs[i * 4 + 2] = 0.0f;
        es->output_regs[i * 4 + 3] = 1.0f;
    }
    memcpy(&es->output_regs[NV2AOR_FOG_COORD * 4], pg->vsh_carry_fog,
           sizeof(pg->vsh_carry_fog));
}

/* Upstream's run of the whole program, for debug.xemu.vsh_carry=2. */
static void carry_run_full(PGRAPHState *pg, uint32_t program_start,
                           float fog[4])
{
    Nv2aVshProgram program;
    if (nv2a_vsh_parse_program(&program, pg->program_data[program_start],
                               NV2A_MAX_TRANSFORM_PROGRAM_LENGTH -
                                   program_start) != NV2AVPR_SUCCESS) {
        memcpy(fog, pg->vsh_carry_fog, sizeof(pg->vsh_carry_fog));
        return;
    }
    Nv2aVshCPUFullExecutionState es;
    Nv2aVshExecutionState state =
        nv2a_vsh_emu_initialize_full_execution_state(&es);
    carry_init_state(pg, &es);
    memcpy(es.context_regs, pg->vsh_constants, sizeof(es.context_regs));
    nv2a_vsh_emu_execute(&state, &program);
    nv2a_vsh_program_destroy(&program);
    memcpy(fog, &es.output_regs[NV2AOR_FOG_COORD * 4], sizeof(float) * 4);
}

static void carry_invalidate_dirty(PGRAPHState *pg)
{
    if (pg->vsh_carry_cache_dirty) {
        pgraph_vsh_carryover_invalidate_cache(pg);
        pg->vsh_carry_cache_dirty = false;
    } else if (pg->vsh_carry_dirty_lo <= pg->vsh_carry_dirty_hi) {
        /* Drop only the programs that span a rewritten slot. */
        for (int i = 0; i <= pg->vsh_carry_dirty_hi; i++) {
            VshCarrySlice *s =
                (VshCarrySlice *)pg->vsh_carry_cached_programs[i];
            if (s && i + s->length > pg->vsh_carry_dirty_lo) {
                nv2a_vsh_program_destroy(&s->program);
                g_free(s);
                pg->vsh_carry_cached_programs[i] = NULL;
            }
        }
    }
    pg->vsh_carry_dirty_lo = UINT16_MAX;
    pg->vsh_carry_dirty_hi = 0;
}

void pgraph_vsh_carryover_update(PGRAPHState *pg)
{
    if (!pg->draw_arrays_length && !pg->inline_elements_length &&
        !pg->inline_buffer_length && !pg->inline_array_length) {
        return;
    }

    carry_invalidate_dirty(pg);

    int mode = qatomic_read(&g_vsh_carry);
    bool is_vertex_program = GET_MASK(pgraph_reg_r(pg, NV_PGRAPH_CSV0_D),
                                      NV_PGRAPH_CSV0_D_MODE) == 2;
    if (!is_vertex_program || !mode) {
        return;
    }

    uint32_t program_start = GET_MASK(pgraph_reg_r(pg, NV_PGRAPH_CSV0_C),
                                      NV_PGRAPH_CSV0_C_CHEOPS_PROGRAM_START);
    if (program_start >= NV2A_MAX_TRANSFORM_PROGRAM_LENGTH) {
        return;
    }

    VshCarrySlice *s =
        (VshCarrySlice *)pg->vsh_carry_cached_programs[program_start];
    if (!s) {
        s = g_new0(VshCarrySlice, 1);
        Nv2aVshParseResult result = nv2a_vsh_parse_program(
            &s->program, pg->program_data[program_start],
            NV2A_MAX_TRANSFORM_PROGRAM_LENGTH - program_start);
        if (result != NV2AVPR_SUCCESS) {
            g_free(s);
            return;
        }
        slice_program(s);
        pg->vsh_carry_cached_programs[program_start] = &s->program;
        g_vsh_carry_parses++;
    }

    float full[4];
    if (mode == 2) {
        carry_run_full(pg, program_start, full);
    }

    if (s->skip) {
        g_vsh_carry_skips++;
    } else {
        g_vsh_carry_runs++;
        Nv2aVshCPUFullExecutionState es;
        Nv2aVshExecutionState state = {
            .input_regs = es.input_regs,
            .output_regs = es.output_regs,
            .temp_regs = es.temp_regs,
            .context_regs = es.context_regs,
            .address_reg = es.address_reg,
        };
        carry_init_state(pg, &es);

        QEMU_BUILD_BUG_MSG(sizeof(es.context_regs) != sizeof(pg->vsh_constants),
                           "context_regs and vsh_constants size mismatch");
        if (s->all_consts) {
            memcpy(es.context_regs, pg->vsh_constants, sizeof(es.context_regs));
        } else {
            for (int i = 0; i < s->n_consts; i++) {
                memcpy(&es.context_regs[s->consts[i] * 4],
                       pg->vsh_constants[s->consts[i]], sizeof(float) * 4);
            }
        }

        nv2a_vsh_emu_execute(&state, &s->program);

        memcpy(pg->vsh_carry_fog, &es.output_regs[NV2AOR_FOG_COORD * 4],
               sizeof(pg->vsh_carry_fog));
    }

    if (mode == 2 && memcmp(full, pg->vsh_carry_fog, sizeof(full))) {
        g_vsh_carry_missed++;
    }
}

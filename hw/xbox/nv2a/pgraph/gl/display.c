/*
 * Geforce NV2A PGRAPH OpenGL Renderer
 *
 * Copyright (c) 2012 espes
 * Copyright (c) 2015 Jannik Vogel
 * Copyright (c) 2018-2025 Matt Borgerson
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

#include "qemu/osdep.h"
#include "qemu/timer.h"
#include "hw/display/vga_int.h"
#include "hw/xbox/nv2a/nv2a_int.h"
#include "hw/xbox/nv2a/pgraph/util.h"
#include "renderer.h"

#include <math.h>
#if defined(__ANDROID__) || defined(ANDROID)
#include <android/log.h>
#define ALOGI_DISP(...) ((void)__android_log_print(ANDROID_LOG_INFO, "xemu-pgraph", __VA_ARGS__))
#else
#define ALOGI_DISP(...) ((void)0)
#endif

void pgraph_gl_init_display(NV2AState *d)
{
    struct PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    glo_set_current(g_nv2a_context_display);

    glGenTextures(1, &r->gl_display_buffer);
    r->gl_display_buffer_internal_format = 0;
    r->gl_display_buffer_width = 0;
    r->gl_display_buffer_height = 0;
    r->gl_display_buffer_format = 0;
    r->gl_display_buffer_type = 0;

    const char *vs =
        "#version 330\n"
        "void main()\n"
        "{\n"
        "    float x = -1.0 + float((gl_VertexID & 1) << 2);\n"
        "    float y = -1.0 + float((gl_VertexID & 2) << 1);\n"
        "    gl_Position = vec4(x, y, 0, 1);\n"
        "}\n";
    /* FIXME: improve interlace handling, pvideo */

    const char *fs =
        "#version 330\n"
        "uniform sampler2D tex;\n"
        "uniform bool pvideo_enable;\n"
        "uniform sampler2D pvideo_tex;\n"
        "uniform vec2 pvideo_in_pos;\n"
        "uniform vec4 pvideo_pos;\n"
        "uniform vec3 pvideo_scale;\n"
        "uniform bool pvideo_color_key_enable;\n"
        "uniform vec3 pvideo_color_key;\n"
        "uniform vec2 display_size;\n"
        "uniform float line_offset;\n"
        "layout(location = 0) out vec4 out_Color;\n"
        "void main()\n"
        "{\n"
        "    vec2 texCoord = gl_FragCoord.xy/display_size;\n"
        "    float rel = display_size.y/textureSize(tex, 0).y/line_offset;\n"
        "    texCoord.y = rel*(1.0f - texCoord.y);\n"
        "    out_Color.rgba = texture(tex, texCoord);\n"
        "    if (pvideo_enable) {\n"
        "        vec2 screenCoord = gl_FragCoord.xy - 0.5;\n"
        "        vec4 output_region = vec4(pvideo_pos.xy, pvideo_pos.xy + pvideo_pos.zw);\n"
        "        bvec4 clip = bvec4(lessThan(screenCoord, output_region.xy),\n"
        "                           greaterThan(screenCoord, output_region.zw));\n"
        "        if (!any(clip) && (!pvideo_color_key_enable || out_Color.rgb == pvideo_color_key)) {\n"
        "            vec2 out_xy = (screenCoord - pvideo_pos.xy) * pvideo_scale.z;\n"
        "            vec2 in_st = (pvideo_in_pos + out_xy * pvideo_scale.xy) / textureSize(pvideo_tex, 0);\n"
        "            in_st.y *= -1.0;\n"
        "            out_Color.rgba = texture(pvideo_tex, in_st);\n"
        "        }\n"
        "    }\n"
        "}\n";

    r->disp_rndr.prog = pgraph_gl_compile_shader(vs, fs);
    r->disp_rndr.tex_loc = glGetUniformLocation(r->disp_rndr.prog, "tex");
    r->disp_rndr.pvideo_enable_loc = glGetUniformLocation(r->disp_rndr.prog, "pvideo_enable");
    r->disp_rndr.pvideo_tex_loc = glGetUniformLocation(r->disp_rndr.prog, "pvideo_tex");
    r->disp_rndr.pvideo_in_pos_loc = glGetUniformLocation(r->disp_rndr.prog, "pvideo_in_pos");
    r->disp_rndr.pvideo_pos_loc = glGetUniformLocation(r->disp_rndr.prog, "pvideo_pos");
    r->disp_rndr.pvideo_scale_loc = glGetUniformLocation(r->disp_rndr.prog, "pvideo_scale");
    r->disp_rndr.pvideo_color_key_enable_loc = glGetUniformLocation(r->disp_rndr.prog, "pvideo_color_key_enable");
    r->disp_rndr.pvideo_color_key_loc = glGetUniformLocation(r->disp_rndr.prog, "pvideo_color_key");
    r->disp_rndr.display_size_loc = glGetUniformLocation(r->disp_rndr.prog, "display_size");
    r->disp_rndr.line_offset_loc = glGetUniformLocation(r->disp_rndr.prog, "line_offset");

    glGenVertexArrays(1, &r->disp_rndr.vao);
    glBindVertexArray(r->disp_rndr.vao);
    glGenBuffers(1, &r->disp_rndr.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, r->disp_rndr.vbo);
    glBufferData(GL_ARRAY_BUFFER, 0, NULL, GL_STATIC_DRAW);
    glGenFramebuffers(1, &r->disp_rndr.fbo);
    glGenTextures(1, &r->disp_rndr.pvideo_tex);
#if defined(__ANDROID__) || defined(ANDROID)
    {
        GLenum _err = glGetError();
        if (_err != GL_NO_ERROR) {
            fprintf(stderr, "pgraph_gl_init_display: GL error 0x%x\n", _err);
        }
    }
#else
    assert(glGetError() == GL_NO_ERROR);
#endif

    glo_set_current(g_nv2a_context_render);
}

void pgraph_gl_finalize_display(PGRAPHState *pg)
{
    PGRAPHGLState *r = pg->gl_renderer_state;

    glo_set_current(g_nv2a_context_display);

    glDeleteTextures(1, &r->gl_display_buffer);
    r->gl_display_buffer = 0;

    glDeleteProgram(r->disp_rndr.prog);
    r->disp_rndr.prog = 0;

    glDeleteVertexArrays(1, &r->disp_rndr.vao);
    r->disp_rndr.vao = 0;

    glDeleteBuffers(1, &r->disp_rndr.vbo);
    r->disp_rndr.vbo = 0;

    glDeleteFramebuffers(1, &r->disp_rndr.fbo);
    r->disp_rndr.fbo = 0;

    glDeleteTextures(1, &r->disp_rndr.pvideo_tex);
    r->disp_rndr.pvideo_tex = 0;

    glo_set_current(g_nv2a_context_render);
}

static uint8_t *convert_texture_data__CR8YB8CB8YA8(const uint8_t *data,
                                                   unsigned int width,
                                                   unsigned int height,
                                                   unsigned int pitch)
{
    uint8_t *converted_data = (uint8_t *)g_malloc(width * height * 4);
    int x, y;
    for (y = 0; y < height; y++) {
        const uint8_t *line = &data[y * pitch];
        const uint32_t row_offset = y * width;
        for (x = 0; x < width; x++) {
            uint8_t *pixel = &converted_data[(row_offset + x) * 4];
            convert_yuy2_to_rgb(line, x, &pixel[0], &pixel[1], &pixel[2]);
            pixel[3] = 255;
        }
    }
    return converted_data;
}

static float pvideo_calculate_scale(unsigned int din_dout,
                                           unsigned int output_size)
{
    float calculated_in = din_dout * (output_size - 1);
    calculated_in = floorf(calculated_in / (1 << 20) + 0.5f);
    return (calculated_in + 1.0f) / output_size;
}

static void render_display_pvideo_overlay(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    // FIXME: This check against PVIDEO_SIZE_IN does not match HW behavior.
    // Many games seem to pass this value when initializing or tearing down
    // PVIDEO. On its own, this generally does not result in the overlay being
    // hidden, however there are certain games (e.g., Ultimate Beach Soccer)
    // that use an unknown mechanism to hide the overlay without explicitly
    // stopping it.
    // Since the value seems to be set to 0xFFFFFFFF only in cases where the
    // content is not valid, it is probably good enough to treat it as an
    // implicit stop.
    bool enabled = (d->pvideo.regs[NV_PVIDEO_BUFFER] & NV_PVIDEO_BUFFER_0_USE)
        && d->pvideo.regs[NV_PVIDEO_SIZE_IN] != 0xFFFFFFFF;
    glUniform1i(r->disp_rndr.pvideo_enable_loc, enabled ? 1 : 0);
#if defined(__ANDROID__) || defined(ANDROID)
    {
        static bool s_pvideo_last_enabled = false;
        static int s_pvideo_log_count = 0;
        if (enabled != s_pvideo_last_enabled || (enabled && s_pvideo_log_count % 30 == 0)) {
            ALOGI_DISP("pvideo_overlay: enabled=%d buf=0x%x size_in=0x%x size_out=0x%x "
                       "fmt=0x%x base=0x%x offset=0x%x enable_loc=%d",
                       (int)enabled,
                       (unsigned)d->pvideo.regs[NV_PVIDEO_BUFFER],
                       (unsigned)d->pvideo.regs[NV_PVIDEO_SIZE_IN],
                       (unsigned)d->pvideo.regs[NV_PVIDEO_SIZE_OUT],
                       (unsigned)d->pvideo.regs[NV_PVIDEO_FORMAT],
                       (unsigned)d->pvideo.regs[NV_PVIDEO_BASE],
                       (unsigned)d->pvideo.regs[NV_PVIDEO_OFFSET],
                       r->disp_rndr.pvideo_enable_loc);
            s_pvideo_last_enabled = enabled;
        }
        if (enabled) { s_pvideo_log_count++; }
    }
#endif
    if (!enabled) {
        return;
    }

    hwaddr base = d->pvideo.regs[NV_PVIDEO_BASE];
    hwaddr limit = d->pvideo.regs[NV_PVIDEO_LIMIT];
    hwaddr offset = d->pvideo.regs[NV_PVIDEO_OFFSET];

    int in_width =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_SIZE_IN], NV_PVIDEO_SIZE_IN_WIDTH);
    int in_height =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_SIZE_IN], NV_PVIDEO_SIZE_IN_HEIGHT);

    int in_s = GET_MASK(d->pvideo.regs[NV_PVIDEO_POINT_IN],
                        NV_PVIDEO_POINT_IN_S);
    int in_t = GET_MASK(d->pvideo.regs[NV_PVIDEO_POINT_IN],
                        NV_PVIDEO_POINT_IN_T);

    int in_pitch =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_FORMAT], NV_PVIDEO_FORMAT_PITCH);
    int in_color =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_FORMAT], NV_PVIDEO_FORMAT_COLOR);

    unsigned int out_width =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_SIZE_OUT], NV_PVIDEO_SIZE_OUT_WIDTH);
    unsigned int out_height =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_SIZE_OUT], NV_PVIDEO_SIZE_OUT_HEIGHT);

    float scale_x = 1.0f;
    float scale_y = 1.0f;
    unsigned int ds_dx = d->pvideo.regs[NV_PVIDEO_DS_DX];
    unsigned int dt_dy = d->pvideo.regs[NV_PVIDEO_DT_DY];
    if (ds_dx != NV_PVIDEO_DIN_DOUT_UNITY) {
        scale_x = pvideo_calculate_scale(ds_dx, out_width);
    }
    if (dt_dy != NV_PVIDEO_DIN_DOUT_UNITY) {
        scale_y = pvideo_calculate_scale(dt_dy, out_height);
    }

    // On HW, setting NV_PVIDEO_SIZE_IN larger than NV_PVIDEO_SIZE_OUT results
    // in them being capped to the output size, content is not scaled. This is
    // particularly important as NV_PVIDEO_SIZE_IN may be set to 0xFFFFFFFF
    // during initialization or teardown.
    if (in_width > out_width) {
        in_width = floorf((float)out_width * scale_x + 0.5f);
    }
    if (in_height > out_height) {
        in_height = floorf((float)out_height * scale_y + 0.5f);
    }

    /* TODO: support other color formats */
    assert(in_color == NV_PVIDEO_FORMAT_COLOR_LE_CR8YB8CB8YA8);

    unsigned int out_x =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_POINT_OUT], NV_PVIDEO_POINT_OUT_X);
    unsigned int out_y =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_POINT_OUT], NV_PVIDEO_POINT_OUT_Y);

    unsigned int color_key_enabled =
        GET_MASK(d->pvideo.regs[NV_PVIDEO_FORMAT], NV_PVIDEO_FORMAT_DISPLAY);
    glUniform1i(r->disp_rndr.pvideo_color_key_enable_loc,
                color_key_enabled ? 1 : 0);

    unsigned int color_key = d->pvideo.regs[NV_PVIDEO_COLOR_KEY] & 0xFFFFFF;
    glUniform3f(r->disp_rndr.pvideo_color_key_loc,
                GET_MASK(color_key, NV_PVIDEO_COLOR_KEY_RED) / 255.0,
                GET_MASK(color_key, NV_PVIDEO_COLOR_KEY_GREEN) / 255.0,
                GET_MASK(color_key, NV_PVIDEO_COLOR_KEY_BLUE) / 255.0);

    assert(offset + in_pitch * in_height <= limit);
    hwaddr end = base + offset + in_pitch * in_height;
    assert(end <= memory_region_size(d->vram));

    pgraph_apply_scaling_factor(pg, &out_x, &out_y);
    pgraph_apply_scaling_factor(pg, &out_width, &out_height);

    // Translate for the GL viewport origin.
    out_y = MAX(r->gl_display_buffer_height - 1 - (int)(out_y + out_height), 0);

    glActiveTexture(GL_TEXTURE0 + 1);
    glBindTexture(GL_TEXTURE_2D, r->disp_rndr.pvideo_tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    uint8_t *tex_rgba = convert_texture_data__CR8YB8CB8YA8(
        d->vram_ptr + base + offset, in_width, in_height, in_pitch);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, in_width, in_height, 0, GL_RGBA,
                 GL_UNSIGNED_BYTE, tex_rgba);
    g_free(tex_rgba);
    glUniform1i(r->disp_rndr.pvideo_tex_loc, 1);
    glUniform2f(r->disp_rndr.pvideo_in_pos_loc, in_s / 16.f, in_t / 8.f);
    glUniform4f(r->disp_rndr.pvideo_pos_loc,
                out_x, out_y, out_width, out_height);
    glUniform3f(r->disp_rndr.pvideo_scale_loc,
                scale_x, scale_y, 1.0f / pg->surface_scale_factor);
}

static void render_display(NV2AState *d, SurfaceBinding *surface)
{
    struct PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    unsigned int width, height;
    VGADisplayParams vga_display_params;
    d->vga.get_resolution(&d->vga, (int*)&width, (int*)&height);
    d->vga.get_params(&d->vga, &vga_display_params);
    int line_offset = vga_display_params.line_offset ? surface->pitch / vga_display_params.line_offset : 1;

    /* Adjust viewport height for interlaced mode, used only in 1080i */
    if (d->vga.cr[NV_PRMCIO_INTERLACE_MODE] != NV_PRMCIO_INTERLACE_MODE_DISABLED) {
        height *= 2;
    }

    pgraph_apply_scaling_factor(pg, &width, &height);

    glBindFramebuffer(GL_FRAMEBUFFER, r->disp_rndr.fbo);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, r->gl_display_buffer);
    bool recreate = (
        surface->fmt.gl_internal_format != r->gl_display_buffer_internal_format
        || width != r->gl_display_buffer_width
        || height != r->gl_display_buffer_height
        || surface->fmt.gl_format != r->gl_display_buffer_format
        || surface->fmt.gl_type != r->gl_display_buffer_type
        );

    if (recreate) {
        /* XXX: There's apparently a bug in some Intel OpenGL drivers for
         * Windows that will leak this texture when its orphaned after use in
         * another context, apparently regardless of which thread it's created
         * or released on.
         *
         * Driver: 27.20.100.8729 9/11/2020 W10 x64
         * Track: https://community.intel.com/t5/Graphics/OpenGL-Windows-drivers-for-Intel-HD-630-leaking-GPU-memory-when/td-p/1274423
         */
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        r->gl_display_buffer_internal_format = surface->fmt.gl_internal_format;
        r->gl_display_buffer_width = width;
        r->gl_display_buffer_height = height;
        r->gl_display_buffer_format = surface->fmt.gl_format;
        r->gl_display_buffer_type = surface->fmt.gl_type;
        glTexImage2D(GL_TEXTURE_2D, 0,
            r->gl_display_buffer_internal_format,
            r->gl_display_buffer_width,
            r->gl_display_buffer_height,
            0,
            r->gl_display_buffer_format,
            r->gl_display_buffer_type,
            NULL);
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D, r->gl_display_buffer, 0);
    GLenum DrawBuffers[1] = {GL_COLOR_ATTACHMENT0};
    glDrawBuffers(1, DrawBuffers);
#if defined(__ANDROID__) || defined(ANDROID)
    {
        GLenum disp_fbo_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (disp_fbo_status != GL_FRAMEBUFFER_COMPLETE) {
            ALOGI_DISP("render_display: FBO incomplete 0x%x "
                       "(disp_buf=%u ifmt=0x%x fmt=0x%x type=0x%x)",
                       (unsigned)disp_fbo_status,
                       r->gl_display_buffer,
                       (unsigned)r->gl_display_buffer_internal_format,
                       (unsigned)r->gl_display_buffer_format,
                       (unsigned)r->gl_display_buffer_type);
        }
    }
#else
    assert(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
#endif

    glBindTexture(GL_TEXTURE_2D, surface->gl_buffer);
    glBindVertexArray(r->disp_rndr.vao);
    glBindBuffer(GL_ARRAY_BUFFER, r->disp_rndr.vbo);
    glUseProgram(r->disp_rndr.prog);
#if defined(__ANDROID__) || defined(ANDROID)
    /* glProgramUniform1i requires GLES 3.1+; use glUniform1i since glUseProgram is already called */
    glUniform1i(r->disp_rndr.tex_loc, 0);
#else
    glProgramUniform1i(r->disp_rndr.prog, r->disp_rndr.tex_loc, 0);
#endif
    glUniform2f(r->disp_rndr.display_size_loc, width, height);
    glUniform1f(r->disp_rndr.line_offset_loc, line_offset);
    render_display_pvideo_overlay(d);

    glViewport(0, 0, width, height);
    glColorMask(true, true, true, true);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_DEPTH_TEST);
#if !defined(__ANDROID__) && !defined(ANDROID)
    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
#endif
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D, 0, 0);
}

static void gl_fence(void)
{
    GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    int result = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT,
                                         (GLuint64)(5000000000));
#if defined(__ANDROID__) || defined(ANDROID)
    if (result != GL_CONDITION_SATISFIED && result != GL_ALREADY_SIGNALED) {
        ALOGI_DISP("gl_fence: glClientWaitSync returned 0x%x (timeout or error), continuing", result);
    }
#else
    assert(result == GL_CONDITION_SATISFIED || result == GL_ALREADY_SIGNALED);
#endif
    glDeleteSync(fence);
}

void pgraph_gl_sync(NV2AState *d)
{
    VGADisplayParams vga_display_params;
    d->vga.get_params(&d->vga, &vga_display_params);

    SurfaceBinding *surface = pgraph_gl_surface_get_within(d, d->pcrtc.start + vga_display_params.line_offset);
#if defined(__ANDROID__) || defined(ANDROID)
    {
        PGRAPHGLState *r_s = d->pgraph.gl_renderer_state;
        ALOGI_DISP("pgraph_gl_sync: pcrtc.start=0x%x surface=%p "
                   "color_binding=%p color_vram=0x%x draw_dirty=%d upload_pending=%d",
                   (unsigned)d->pcrtc.start, (void*)surface,
                   (void*)(r_s ? r_s->color_binding : NULL),
                   (r_s && r_s->color_binding) ? (unsigned)r_s->color_binding->vram_addr : 0,
                   (r_s && r_s->color_binding) ? (int)r_s->color_binding->draw_dirty : -1,
                   surface ? (int)surface->upload_pending : -1);
    }
#endif
    if (surface == NULL || !surface->color || !surface->width || !surface->height) {
        qemu_event_set(&d->pgraph.sync_complete);
        return;
    }

    /* FIXME: Sanity check surface dimensions */

    /* Wait for queued commands to complete */
#if defined(__ANDROID__) || defined(ANDROID)
    {
        int64_t _t0 = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
        pgraph_gl_upload_surface_data(d, surface, !tcg_enabled());
        int64_t _dt = qemu_clock_get_ms(QEMU_CLOCK_REALTIME) - _t0;
        if (_dt > 50) {
            ALOGI_DISP("pgraph_gl_sync: upload_surface_data took %"PRId64"ms", _dt);
        }
    }
    {
        int64_t _t0 = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
        gl_fence();
        int64_t _dt = qemu_clock_get_ms(QEMU_CLOCK_REALTIME) - _t0;
        if (_dt > 50) {
            ALOGI_DISP("pgraph_gl_sync: gl_fence#1 took %"PRId64"ms", _dt);
        }
    }
    {
        GLenum _err = glGetError();
        if (_err != GL_NO_ERROR) {
            ALOGI_DISP("pgraph_gl_sync: GL error 0x%x after upload+fence, continuing",
                  (unsigned)_err);
            while (glGetError() != GL_NO_ERROR) {} /* drain */
        }
    }
#else
    pgraph_gl_upload_surface_data(d, surface, !tcg_enabled());
    gl_fence();
    assert(glGetError() == GL_NO_ERROR);
#endif

    /* Render framebuffer in display context */
    {
#if defined(__ANDROID__) || defined(ANDROID)
        int64_t _t0 = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
#endif
        glo_set_current(g_nv2a_context_display);
#if defined(__ANDROID__) || defined(ANDROID)
        int64_t _dt = qemu_clock_get_ms(QEMU_CLOCK_REALTIME) - _t0;
        if (_dt > 50) {
            ALOGI_DISP("pgraph_gl_sync: glo_set_current(display) took %"PRId64"ms", _dt);
        }
    }
#endif
#if defined(__ANDROID__) || defined(ANDROID)
    /* Before render_display: sample center pixel of surface->gl_buffer
     * to determine whether NV2A has rendered any content into the surface. */
    {
        PGRAPHGLState *r_pre = d->pgraph.gl_renderer_state;
        static int s_pre_count = 0;
        if (++s_pre_count <= 3000 && s_pre_count % 10 == 1) {
            unsigned int vw = 0, vh = 0;
            VGADisplayParams vdp2;
            d->vga.get_resolution(&d->vga, (int*)&vw, (int*)&vh);
            d->vga.get_params(&d->vga, &vdp2);
            int lo = vdp2.line_offset ? (int)(surface->pitch / vdp2.line_offset) : 1;

            GLuint tmp_fbo2;
            glGenFramebuffers(1, &tmp_fbo2);
            glBindFramebuffer(GL_FRAMEBUFFER, tmp_fbo2);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                   GL_TEXTURE_2D, surface->gl_buffer, 0);
            unsigned char px2[4] = {0};
            unsigned char px_upper[4] = {0};  /* upper-center ~25% down */
            unsigned char px_logo[4] = {0};   /* ~33% from left, 40% from top */
            GLenum fbo2_status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (fbo2_status == GL_FRAMEBUFFER_COMPLETE) {
                glReadPixels((GLint)(surface->width / 2), (GLint)(surface->height / 2),
                             1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px2);
                glReadPixels((GLint)(surface->width / 2), (GLint)(surface->height / 4),
                             1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px_upper);
                glReadPixels((GLint)(surface->width / 3), (GLint)(surface->height * 2 / 5),
                             1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px_logo);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &tmp_fbo2);
            ALOGI_DISP("pre-render: surface->gl_buffer=%u %dx%d fmt=0x%x "
                       "fbo_status=0x%x center_px=(%d,%d,%d,%d) "
                       "upper_px=(%d,%d,%d,%d) logo_px=(%d,%d,%d,%d) "
                       "vga=%ux%u lo=%d disp_prog=%u",
                       surface->gl_buffer, surface->width, surface->height,
                       (unsigned)surface->fmt.gl_format,
                       (unsigned)fbo2_status,
                       px2[0], px2[1], px2[2], px2[3],
                       px_upper[0], px_upper[1], px_upper[2], px_upper[3],
                       px_logo[0], px_logo[1], px_logo[2], px_logo[3],
                       vw, vh, lo, r_pre->disp_rndr.prog);
        }
    }
#endif
#if defined(__ANDROID__) || defined(ANDROID)
    {
        int64_t _t0 = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
        render_display(d, surface);
        int64_t _dt = qemu_clock_get_ms(QEMU_CLOCK_REALTIME) - _t0;
        if (_dt > 50) {
            ALOGI_DISP("pgraph_gl_sync: render_display took %"PRId64"ms", _dt);
        }
    }
    {
        int64_t _t0 = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
        gl_fence();
        int64_t _dt = qemu_clock_get_ms(QEMU_CLOCK_REALTIME) - _t0;
        if (_dt > 50) {
            ALOGI_DISP("pgraph_gl_sync: gl_fence#2 took %"PRId64"ms", _dt);
        }
    }
    {
        GLenum _err2 = glGetError();
        if (_err2 != GL_NO_ERROR) {
            ALOGI_DISP("pgraph_gl_sync: GL error 0x%x after render_display+fence",
                       (unsigned)_err2);
            while (glGetError() != GL_NO_ERROR) {}
        }
    }
#else
    render_display(d, surface);
    gl_fence();
    assert(glGetError() == GL_NO_ERROR);
#endif
#if defined(__ANDROID__) || defined(ANDROID)
    /* Sample center pixel of gl_display_buffer to detect black output */
    {
        PGRAPHGLState *r2 = d->pgraph.gl_renderer_state;
        static int s_sync_count = 0;
        if (++s_sync_count <= 3000 && s_sync_count % 10 == 1) {
            /* Read from the display buffer via a temp FBO */
            GLuint tmp_fbo;
            glGenFramebuffers(1, &tmp_fbo);
            glBindFramebuffer(GL_FRAMEBUFFER, tmp_fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                   GL_TEXTURE_2D, r2->gl_display_buffer, 0);
            unsigned char px[4] = {0};
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                glReadPixels(r2->gl_display_buffer_width/2,
                             r2->gl_display_buffer_height/2,
                             1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &tmp_fbo);
            ALOGI_DISP("pgraph_gl_sync: display_buffer=%u %dx%d center_pixel=(%d,%d,%d,%d)",
                       r2->gl_display_buffer,
                       r2->gl_display_buffer_width, r2->gl_display_buffer_height,
                       px[0], px[1], px[2], px[3]);
        }
    }
#endif

    /* Switch back to original context */
    glo_set_current(g_nv2a_context_render);

    qatomic_set(&d->pgraph.sync_pending, false);
    qemu_event_set(&d->pgraph.sync_complete);
}

int pgraph_gl_get_framebuffer_surface(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    qemu_mutex_lock(&d->pfifo.lock);
    // FIXME: Possible race condition with pgraph, consider lock

    VGADisplayParams vga_display_params;
    d->vga.get_params(&d->vga, &vga_display_params);

    SurfaceBinding *surface = pgraph_gl_surface_get_within(
        d, d->pcrtc.start + vga_display_params.line_offset);
#if defined(__ANDROID__) || defined(ANDROID)
    ALOGI_DISP("get_framebuffer_surface: pcrtc.start=0x%x surface=%p color=%d fmt=0x%x",
          (unsigned)d->pcrtc.start, (void*)surface,
          surface ? (int)surface->color : -1,
          surface ? (unsigned)surface->fmt.gl_format : 0);
#endif
    if (surface == NULL || !surface->color) {
        qemu_mutex_unlock(&d->pfifo.lock);
        return 0;
    }

    assert(surface->color);
    assert(surface->fmt.gl_attachment == GL_COLOR_ATTACHMENT0);
    assert(surface->fmt.gl_format == GL_RGBA
        || surface->fmt.gl_format == GL_RGB
        || surface->fmt.gl_format == GL_BGR
        || surface->fmt.gl_format == GL_BGRA
        );

    surface->frame_time = pg->frame_time;
    qemu_event_reset(&d->pgraph.sync_complete);
    qatomic_set(&pg->sync_pending, true);
    pfifo_kick(d);
    qemu_mutex_unlock(&d->pfifo.lock);
#if defined(__ANDROID__) || defined(ANDROID)
    ALOGI_DISP("get_framebuffer_surface: waiting for sync_complete...");
#endif
    qemu_event_wait(&d->pgraph.sync_complete);
#if defined(__ANDROID__) || defined(ANDROID)
    ALOGI_DISP("get_framebuffer_surface: sync_complete received, returning %d", r->gl_display_buffer);
#endif

    return r->gl_display_buffer;
}

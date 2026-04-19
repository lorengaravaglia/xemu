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

#include "hw/xbox/nv2a/nv2a_int.h"
#include "hw/xbox/nv2a/pgraph/pgraph.h"
#include "debug.h"
#include "renderer.h"
#include "qemu/timer.h"

#if defined(__ANDROID__) || defined(ANDROID)
#include <android/log.h>
#define ALOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, "xemu-renderer", __VA_ARGS__))
#endif

GloContext *g_nv2a_context_render;
GloContext *g_nv2a_context_display;

static void early_context_init(void)
{
#if defined(__ANDROID__) || defined(ANDROID)
    fprintf(stderr, "early_context_init (Android): Creating g_nv2a_context_render...\n");
#endif
    g_nv2a_context_render = glo_context_create();
#if defined(__ANDROID__) || defined(ANDROID)
    fprintf(stderr, "early_context_init (Android): Creating g_nv2a_context_display...\n");
#endif
    g_nv2a_context_display = glo_context_create();

    // Note: Due to use of shared contexts, this must happen after some other
    // context is created so the temporary context will not become the thread
    // context. After destroying the context, some a durable context should be
    // selected.
#if defined(__ANDROID__) || defined(ANDROID)
    fprintf(stderr, "early_context_init (Android): Creating temporary context for GPU properties...\n");
#endif
    GloContext *context = glo_context_create();
#if defined(__ANDROID__) || defined(ANDROID)
    fprintf(stderr, "early_context_init (Android): Determining GPU properties...\n");
#endif
    pgraph_gl_determine_gpu_properties();
#if defined(__ANDROID__) || defined(ANDROID)
    fprintf(stderr, "early_context_init (Android): Destroying temporary context...\n");
#endif
    glo_context_destroy(context);
#if defined(__ANDROID__) || defined(ANDROID)
    fprintf(stderr, "early_context_init (Android): Setting current context to display...\n");
#endif
    glo_set_current(g_nv2a_context_display);
#if defined(__ANDROID__) || defined(ANDROID)
    fprintf(stderr, "early_context_init (Android): Done.\n");
#endif
}

static void pgraph_gl_init(NV2AState *d, Error **errp)
{
#if defined(__ANDROID__) || defined(ANDROID)
    if (g_nv2a_context_render == NULL) {
        error_setg(errp, "Android OpenGL context not initialized");
        return;
    }
#endif
    PGRAPHState *pg = &d->pgraph;

    pg->gl_renderer_state = g_malloc0(sizeof(*pg->gl_renderer_state));
    PGRAPHGLState *r = pg->gl_renderer_state;

    /* fire up opengl */
    glo_set_current(g_nv2a_context_render);

#if DEBUG_NV2A_GL
    gl_debug_initialize();
#endif

    /* DXT textures */
#if defined(__ANDROID__) || defined(ANDROID)
    if (!glo_check_extension("GL_EXT_texture_compression_s3tc")) {
        ALOGI("Warning: GL_EXT_texture_compression_s3tc not found. Proceeding anyway (may cause rendering issues)");
    }
    // ES2 compatibility is core in GLES 3.0
#else
    assert(glo_check_extension("GL_EXT_texture_compression_s3tc"));
    /*  Internal RGB565 texture format */
    assert(glo_check_extension("GL_ARB_ES2_compatibility"));
#endif

    glGetFloatv(GL_SMOOTH_LINE_WIDTH_RANGE, r->supported_smooth_line_width_range);
    glGetFloatv(GL_ALIASED_LINE_WIDTH_RANGE, r->supported_aliased_line_width_range);

    pgraph_gl_init_surfaces(pg);
    pgraph_gl_init_reports(d);
    pgraph_gl_init_textures(d);
    pgraph_gl_init_buffers(d);
    pgraph_gl_init_shaders(pg);
    pgraph_gl_init_display(d);

    pgraph_gl_update_entire_memory_buffer(d);

    pg->uniform_attrs = 0;
    pg->swizzle_attrs = 0;

    r->supported_extensions.texture_filter_anisotropic =
        glo_check_extension("GL_EXT_texture_filter_anisotropic");

#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_finalize(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;

    glo_set_current(g_nv2a_context_render);

    pgraph_gl_finalize_surfaces(pg);
    pgraph_gl_finalize_shaders(pg);
    pgraph_gl_finalize_textures(pg);
    pgraph_gl_finalize_reports(pg);
    pgraph_gl_finalize_buffers(pg);
    pgraph_gl_finalize_display(pg);

    glo_set_current(NULL);

    g_free(pg->gl_renderer_state);
    pg->gl_renderer_state = NULL;
}

static void pgraph_gl_flip_stall(NV2AState *d)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    NV2A_GL_DFRAME_TERMINATOR();
#if defined(__ANDROID__) || defined(ANDROID)
    /* glFinish() can stall indefinitely on Android GLES drivers (same issue
     * as in xemu.c render loop). Use glFlush() instead; the EGL swap handles
     * the required synchronization. */
    glFlush();
#else
    glFinish();
#endif
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_flush(NV2AState *d)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_surface_flush(d);
    pgraph_gl_mark_textures_possibly_dirty(d, 0, memory_region_size(d->vram));
    pgraph_gl_update_entire_memory_buffer(d);
    /* FIXME: Flush more? */

    qatomic_set(&d->pgraph.flush_pending, false);
    qemu_event_set(&d->pgraph.flush_complete);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_process_pending(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    if (qatomic_read(&r->downloads_pending) ||
        qatomic_read(&r->download_dirty_surfaces_pending) ||
        qatomic_read(&d->pgraph.sync_pending) ||
        qatomic_read(&d->pgraph.flush_pending) ||
        qatomic_read(&r->shader_cache_writeback_pending)) {
        /*
         * Android: glo_set_current (eglMakeCurrent) is called AFTER releasing
         * pfifo.lock to avoid a deadlock where eglMakeCurrent blocks (e.g.,
         * due to implicit GPU synchronization on context switch) while
         * pfifo.lock is held. The render thread (nv2a_get_framebuffer_surface)
         * acquires pfifo.lock to post sync_pending; if pfifo.lock is stuck
         * here, sync_complete is never signaled → permanent freeze.
         */
        qemu_mutex_unlock(&d->pfifo.lock);
#if defined(__ANDROID__) || defined(ANDROID)
        {
            int64_t t0 = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
            glo_set_current(g_nv2a_context_render);
            int64_t dt = qemu_clock_get_ms(QEMU_CLOCK_REALTIME) - t0;
            if (dt > 50) {
                __android_log_print(ANDROID_LOG_ERROR, "xemu-pgraph",
                    "pgraph_process_pending: glo_set_current(render) took %"PRId64"ms", dt);
            }
        }
#endif
        qemu_mutex_lock(&d->pgraph.lock);
        if (qatomic_read(&r->downloads_pending)) {
            pgraph_gl_process_pending_downloads(d);
        }
        if (qatomic_read(&r->download_dirty_surfaces_pending)) {
            pgraph_gl_download_dirty_surfaces(d);
        }
        if (qatomic_read(&d->pgraph.sync_pending)) {
            pgraph_gl_sync(d);
        }
        if (qatomic_read(&d->pgraph.flush_pending)) {
            pgraph_gl_flush(d);
        }
        if (qatomic_read(&r->shader_cache_writeback_pending)) {
            pgraph_gl_shader_write_cache_reload_list(&d->pgraph);
        }
        qemu_mutex_unlock(&d->pgraph.lock);
#if defined(__ANDROID__) || defined(ANDROID)
        glo_set_current(NULL);
#endif
        qemu_mutex_lock(&d->pfifo.lock);
    }
}

static void pgraph_gl_pre_savevm_trigger(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    qatomic_set(&r->download_dirty_surfaces_pending, true);
    qemu_event_reset(&r->dirty_surfaces_download_complete);
}

static void pgraph_gl_pre_savevm_wait(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    qemu_event_wait(&r->dirty_surfaces_download_complete);
}

static void pgraph_gl_pre_shutdown_trigger(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    qatomic_set(&r->shader_cache_writeback_pending, true);
    qemu_event_reset(&r->shader_cache_writeback_complete);
}

static void pgraph_gl_pre_shutdown_wait(NV2AState *d)
{
    PGRAPHState *pg = &d->pgraph;
    PGRAPHGLState *r = pg->gl_renderer_state;

    qemu_event_wait(&r->shader_cache_writeback_complete);
}

static void pgraph_gl_clear_report_value_op(NV2AState *d)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_clear_report_value(d);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_clear_surface_op(NV2AState *d, uint32_t parameter)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_clear_surface(d, parameter);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_draw_begin_op(NV2AState *d)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_draw_begin(d);
}

static void pgraph_gl_draw_end_op(NV2AState *d)
{
    pgraph_gl_draw_end(d);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
    static unsigned int s_draw_count = 0;
    s_draw_count++;
    if (s_draw_count <= 20 || s_draw_count % 100 == 0) {
        ALOGI("pgraph draw_end #%u", s_draw_count);
    }
#endif
}

static void pgraph_gl_get_report_op(NV2AState *d, uint32_t parameter)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_get_report(d, parameter);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_surface_update_op(NV2AState *d, bool upload, bool color_write, bool zeta_write)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_surface_update(d, upload, color_write, zeta_write);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_image_blit_op(NV2AState *d)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_image_blit(d);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static void pgraph_gl_process_pending_reports_op(NV2AState *d)
{
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(g_nv2a_context_render);
#endif
    pgraph_gl_process_pending_reports(d);
#if defined(__ANDROID__) || defined(ANDROID)
    glo_set_current(NULL);
#endif
}

static PGRAPHRenderer pgraph_gl_renderer = {
    .type = CONFIG_DISPLAY_RENDERER_OPENGL,
    .name = "OpenGL",
    .ops = {
        .init = pgraph_gl_init,
        .early_context_init = early_context_init,
        .finalize = pgraph_gl_finalize,
        .clear_report_value = pgraph_gl_clear_report_value_op,
        .clear_surface = pgraph_gl_clear_surface_op,
        .draw_begin = pgraph_gl_draw_begin_op,
        .draw_end = pgraph_gl_draw_end_op,
        .flip_stall = pgraph_gl_flip_stall,
        .flush_draw = pgraph_gl_flush,
        .get_report = pgraph_gl_get_report_op,
        .image_blit = pgraph_gl_image_blit_op,
        .pre_savevm_trigger = pgraph_gl_pre_savevm_trigger,
        .pre_savevm_wait = pgraph_gl_pre_savevm_wait,
        .pre_shutdown_trigger = pgraph_gl_pre_shutdown_trigger,
        .pre_shutdown_wait = pgraph_gl_pre_shutdown_wait,
        .process_pending = pgraph_gl_process_pending,
        .process_pending_reports = pgraph_gl_process_pending_reports_op,
        .surface_update = pgraph_gl_surface_update_op,
        .set_surface_scale_factor = pgraph_gl_set_surface_scale_factor,
        .get_surface_scale_factor = pgraph_gl_get_surface_scale_factor,
        .get_framebuffer_surface = pgraph_gl_get_framebuffer_surface,
        .get_gpu_properties = pgraph_gl_get_gpu_properties,
    }
};

static void __attribute__((constructor)) register_renderer(void)
{
    pgraph_renderer_register(&pgraph_gl_renderer);
}

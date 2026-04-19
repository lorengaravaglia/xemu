/*
 *  Offscreen OpenGL abstraction layer -- SDL based
 *
 *  Copyright (c) 2018-2024 Matt Borgerson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <assert.h>

#include "gloffscreen.h"

#if defined(__ANDROID__) || defined(ANDROID)

#include <EGL/egl.h>
#include <android/log.h>

/* These globals are defined in xemu_android.c / ui/xemu.c */
extern EGLDisplay egl_display;
extern EGLContext egl_context; /* main display context — used as share group parent */
extern EGLConfig  egl_config;

struct _GloContext {
    EGLContext egl_ctx;
    EGLSurface egl_pbuf;
};

/* Create an offscreen OpenGL context that shares resources with the main
 * display context.  Each NV2A context gets its own real EGL context and a
 * 1×1 pbuffer surface so it can be made current on any thread independently,
 * without blocking the xemu_core render thread. */
GloContext *glo_context_create(void)
{
    GloContext *context = (GloContext *)malloc(sizeof(GloContext));
    assert(context != NULL);

    /* Create a new GLES 3 context sharing objects with the main EGL context */
    EGLint ctx_attrs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    context->egl_ctx = eglCreateContext(egl_display, egl_config,
                                        egl_context, ctx_attrs);
    if (context->egl_ctx == EGL_NO_CONTEXT) {
        fprintf(stderr, "glo_context_create: eglCreateContext failed: 0x%x\n",
                eglGetError());
        free(context);
        return NULL;
    }

    /* Create a 1×1 pbuffer surface for offscreen rendering */
    EGLint pb_attrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    context->egl_pbuf = eglCreatePbufferSurface(egl_display, egl_config,
                                                pb_attrs);
    if (context->egl_pbuf == EGL_NO_SURFACE) {
        fprintf(stderr,
                "glo_context_create: eglCreatePbufferSurface failed: 0x%x\n",
                eglGetError());
        eglDestroyContext(egl_display, context->egl_ctx);
        free(context);
        return NULL;
    }

    /* Make the new context current on the calling thread (matches desktop
     * SDL behaviour where glo_context_create also makes it current). */
    if (!eglMakeCurrent(egl_display, context->egl_pbuf, context->egl_pbuf,
                        context->egl_ctx)) {
        fprintf(stderr, "glo_context_create: eglMakeCurrent failed: 0x%x\n",
                eglGetError());
    }

    return context;
}

/* Set current context — directly calls eglMakeCurrent; no shared mutex
 * needed since each NV2A GloContext is an independent EGL context. */
void glo_set_current(GloContext *context)
{
    if (context == NULL) {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                       EGL_NO_CONTEXT);
    } else {
        if (!eglMakeCurrent(egl_display, context->egl_pbuf, context->egl_pbuf,
                            context->egl_ctx)) {
            fprintf(stderr, "glo_set_current: eglMakeCurrent failed: 0x%x\n",
                    eglGetError());
        }
    }
}

/* Destroy a previously created OpenGL context */
void glo_context_destroy(GloContext *context)
{
    if (!context) return;
    glo_set_current(NULL);
    eglDestroySurface(egl_display, context->egl_pbuf);
    eglDestroyContext(egl_display, context->egl_ctx);
    free(context);
}

#else /* !ANDROID — desktop SDL path */

#include <SDL3/SDL.h>

struct _GloContext {
    SDL_Window    *window;
    SDL_GLContext  gl_context;
};

/* Create an OpenGL context */
GloContext *glo_context_create(void)
{
    GloContext *context = (GloContext *)malloc(sizeof(GloContext));
    assert(context != NULL);

    SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_ALPHA_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);

    // Initialize rendering context
    SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 4);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(
        SDL_GL_CONTEXT_PROFILE_MASK,
        SDL_GL_CONTEXT_PROFILE_CORE);

    // Create main window
    context->window = SDL_CreateWindow(
        "SDL Offscreen Window",
        640, 480,
        SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
    if (context->window == NULL) {
        fprintf(stderr, "%s: Failed to create window\n", __func__);
        SDL_Quit();
        exit(1);
    }

    context->gl_context = SDL_GL_CreateContext(context->window);
    if (context->gl_context == NULL) {
        fprintf(stderr, "%s: Failed to create GL context\n", __func__);
        SDL_DestroyWindow(context->window);
        SDL_Quit();
        exit(1);
    }

    glo_set_current(context);

    return context;
}

/* Set current context */
void glo_set_current(GloContext *context)
{
    if (context == NULL) {
        SDL_GL_MakeCurrent(NULL, NULL);
    } else {
        SDL_GL_MakeCurrent(context->window, context->gl_context);
    }
}

/* Destroy a previously created OpenGL context */
void glo_context_destroy(GloContext *context)
{
    if (!context) return;
    glo_set_current(NULL);
    free(context);
}

#endif /* ANDROID */

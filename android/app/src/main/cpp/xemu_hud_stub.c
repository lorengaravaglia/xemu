#include "xemu_hud_stub.h"
#include "xemu_android.h"
#include <android/log.h>
#include "ui/xemu-notifications.h"
#include "ui/xemu-widescreen.h"
#include <GLES3/gl3.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

/* GL_EXT_sRGB_write_control — may not be defined in NDK headers */
#ifndef GL_FRAMEBUFFER_SRGB_EXT
#define GL_FRAMEBUFFER_SRGB_EXT 0x8DB9
#endif

#define LOG_TAG "xemu-android-hud-stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Globals normally defined in ui/xui/main.cc (extern'd in common.hh)
bool g_screenshot_pending = false;
float g_main_menu_height = 0.0f;

// ---- Aspect ratio ----
// 0 = Native (largest integer multiple of 640x480, centered)
// 1 = Auto   (stretch to fill — same as 16:9 on widescreen phones)
// 2 = 4:3    (pillarbox/letterbox)
// 3 = 16:9   (stretch to fill, default)
static int g_aspect_ratio = 1;   /* Auto; see xemu_hud_render() */

void xemu_hud_set_aspect_ratio(int ratio) {
    g_aspect_ratio = ratio;
}

void xemu_hud_set_aspect_16x9(bool wide) {
    g_aspect_ratio = wide ? 3 : 2;
}

// ---- Texture filter ----
// false = GL_LINEAR (default, smooth)
// true  = GL_NEAREST (pixel-art / sharp)
static bool g_filter_nearest = false;

void xemu_hud_set_filter_nearest(bool nearest) {
    g_filter_nearest = nearest;
}

// ---- Brightness ----
// A gamma curve on the displayed picture, out = in^(100 / percent): blacks
// and whites stay put, mid-tones lift (or drop below 100%).  The user's
// control, applied after the game's own gamma ramp (the DAC palette below).
static float g_gamma = 1.0f;

void xemu_hud_set_brightness(int percent) {
    if (percent < 50) percent = 50;
    if (percent > 300) percent = 300;
    g_gamma = 100.0f / (float)percent;
}

// ---- Fullscreen blit state ----
static GLuint s_blit_tex  = 0;
static GLuint s_blit_prog = 0;
static GLuint s_blit_vao  = 0;
static GLint  s_blit_tex_loc  = -1;
static GLint  s_blit_ndc_loc  = -1;
static GLint  s_blit_gamma_loc = -1;
static GLint  s_blit_lut_loc = -1;

// ---- Xbox gamma ramp ----
// Games set their brightness/gamma through the NV2A's VGA DAC palette (256
// RGB entries, identity at reset; D3D SetGammaRamp).  The real DAC maps every
// scanned-out pixel through it, so the TV sees the game's ramp.  Upstream
// applies it in its display shader (ui/xui/gl-helpers.cc); this blit replaces
// that path, so it does the same with a 256x1 lookup texture, refreshed when
// the vCPU changes the palette.
const uint8_t *nv2a_get_dac_palette(void);
static GLuint s_lut_tex = 0;
static uint8_t s_lut_palette[256 * 3];

static bool palette_is_identity(const uint8_t *p)
{
    for (int i = 0; i < 256 * 3; i++) {
        if (p[i] != i / 3) {
            return false;
        }
    }
    return true;
}

static void update_gamma_lut(void)
{
    uint8_t pal[256 * 3];
    /* The vCPU writes it entry by entry; a frame may catch a ramp half
     * written, which the next frame corrects (as upstream). */
    memcpy(pal, nv2a_get_dac_palette(), sizeof(pal));
    if (s_lut_tex && !memcmp(pal, s_lut_palette, sizeof(pal))) {
        return;
    }

    uint8_t rgba[256 * 4];
    for (int i = 0; i < 256; i++) {
        rgba[i * 4 + 0] = pal[i * 3 + 0];
        rgba[i * 4 + 1] = pal[i * 3 + 1];
        rgba[i * 4 + 2] = pal[i * 3 + 2];
        rgba[i * 4 + 3] = 255;
    }
    glActiveTexture(GL_TEXTURE1);
    if (!s_lut_tex) {
        glGenTextures(1, &s_lut_tex);
        glBindTexture(GL_TEXTURE_2D, s_lut_tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 256, 1, 0, GL_RGBA,
                     GL_UNSIGNED_BYTE, rgba);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    } else {
        glBindTexture(GL_TEXTURE_2D, s_lut_tex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 256, 1, GL_RGBA,
                        GL_UNSIGNED_BYTE, rgba);
    }
    glActiveTexture(GL_TEXTURE0);

    /* Log the ramp when it settles into a new shape: identity, or a few
     * sample points (R channel) and whether the channels differ. */
    bool ident = palette_is_identity(pal);
    bool grey = true;
    for (int i = 0; i < 256 && grey; i++) {
        grey = pal[i * 3] == pal[i * 3 + 1] && pal[i * 3] == pal[i * 3 + 2];
    }
    LOGI("gamma ramp: %s | R at 0/32/64/128/192/255 = %d %d %d %d %d %d%s",
         ident ? "identity" : "set by the game", pal[0], pal[32 * 3],
         pal[64 * 3], pal[128 * 3], pal[192 * 3], pal[255 * 3],
         grey ? "" : " (channels differ)");
    memcpy(s_lut_palette, pal, sizeof(pal));
}

static void check_shader_compile(GLuint shader, const char *name)
{
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetShaderInfoLog(shader, sizeof(buf), NULL, buf);
        LOGE("blit shader '%s' compile error: %s", name, buf);
    }
}

static void init_blit_resources(void)
{
    /* Probe for GL_EXT_sRGB_write_control. If present, disable sRGB encoding
     * on framebuffer writes so Xbox sRGB values pass through the sRGB EGL
     * surface unchanged. If absent, use the pow() fallback in the shader. */
    const char *exts = (const char *)glGetString(GL_EXTENSIONS);
    bool has_srgb_write_ctrl = exts && strstr(exts, "GL_EXT_sRGB_write_control");
    if (has_srgb_write_ctrl) {
        glDisable(GL_FRAMEBUFFER_SRGB_EXT);
        LOGI("GL_EXT_sRGB_write_control available: sRGB framebuffer write encoding disabled");
    } else {
        LOGI("GL_EXT_sRGB_write_control not available: using pow() sRGB decode in blit shader");
    }

    /* dst_ndc: x0,y0,x1,y1 in NDC space for the destination quad.
     * Vertex layout (triangle strip): BL=0, BR=1, TL=2, TR=3.
     * v_uv is always (0,0)→(1,1) regardless of dst_ndc. */
    const char *vs =
        "#version 300 es\n"
        "uniform vec4 dst_ndc;\n"
        "out vec2 v_uv;\n"
        "void main() {\n"
        "    float x = ((gl_VertexID & 1) == 0) ? dst_ndc.x : dst_ndc.z;\n"
        "    float y = ((gl_VertexID & 2) == 0) ? dst_ndc.y : dst_ndc.w;\n"
        "    float u = ((gl_VertexID & 1) == 0) ? 0.0 : 1.0;\n"
        "    float v = ((gl_VertexID & 2) == 0) ? 0.0 : 1.0;\n"
        "    gl_Position = vec4(x, y, 0.0, 1.0);\n"
        "    v_uv = vec2(u, v);\n"
        "}\n";

    /* Sample r->gl_display_buffer: after render_display's Y-flip, the
     * texture's texcoord (0,0) is Xbox bottom and (0,1) is Xbox top.
     * For correct screen output (GL y=0 = screen bottom = Xbox bottom)
     * no additional flip is needed.
     *
     * Two variants: when GL_EXT_sRGB_write_control is available we disabled
     * sRGB encoding above, so we pass through directly.  Otherwise we
     * linearise (sRGB-decode) here so the sRGB EGL surface re-encodes exactly
     * once, preserving the original Xbox sRGB values on screen. */
    const char *fs_passthrough =
        "#version 300 es\n"
        "precision highp float;\n"
        "uniform sampler2D tex;\n"
        "uniform float u_gamma;\n"
        "uniform sampler2D lut;\n"
        "in vec2 v_uv;\n"
        "layout(location = 0) out vec4 fragColor;\n"
        "float dac(float c, int ch) {\n"
        "    float x = (clamp(c, 0.0, 1.0) * 255.0 + 0.5) / 256.0;\n"
        "    return texture(lut, vec2(x, 0.5))[ch];\n"
        "}\n"
        "void main() {\n"
        "    vec3 t = texture(tex, v_uv).rgb;\n"
        "    t = vec3(dac(t.r, 0), dac(t.g, 1), dac(t.b, 2));\n"
        "    vec3 c = pow(max(t, vec3(0.0)), vec3(u_gamma));\n"
        "    fragColor = vec4(c, 1.0);\n"
        "}\n";

    const char *fs_srgb_decode =
        "#version 300 es\n"
        "precision highp float;\n"
        "uniform sampler2D tex;\n"
        "uniform float u_gamma;\n"
        "uniform sampler2D lut;\n"
        "in vec2 v_uv;\n"
        "layout(location = 0) out vec4 fragColor;\n"
        "float dac(float c, int ch) {\n"
        "    float x = (clamp(c, 0.0, 1.0) * 255.0 + 0.5) / 256.0;\n"
        "    return texture(lut, vec2(x, 0.5))[ch];\n"
        "}\n"
        "void main() {\n"
        "    vec3 t = texture(tex, v_uv).rgb;\n"
        "    t = vec3(dac(t.r, 0), dac(t.g, 1), dac(t.b, 2));\n"
        "    vec3 c = pow(max(t, vec3(0.0)), vec3(u_gamma));\n"
        "    vec3 lin = mix(c / 12.92,\n"
        "                   pow((c + 0.055) / 1.055, vec3(2.4)),\n"
        "                   step(vec3(0.04045), c));\n"
        "    fragColor = vec4(lin.rgb, 1.0);\n"
        "}\n";

    const char *fs = has_srgb_write_ctrl ? fs_passthrough : fs_srgb_decode;

    GLuint v = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(v, 1, &vs, NULL);
    glCompileShader(v);
    check_shader_compile(v, "blit-vs");

    GLuint f = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(f, 1, &fs, NULL);
    glCompileShader(f);
    check_shader_compile(f, "blit-fs");

    s_blit_prog = glCreateProgram();
    glAttachShader(s_blit_prog, v);
    glAttachShader(s_blit_prog, f);
    glLinkProgram(s_blit_prog);
    GLint ok = 0;
    glGetProgramiv(s_blit_prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetProgramInfoLog(s_blit_prog, sizeof(buf), NULL, buf);
        LOGE("blit program link error: %s", buf);
    }
    glDeleteShader(v);
    glDeleteShader(f);

    s_blit_tex_loc  = glGetUniformLocation(s_blit_prog, "tex");
    s_blit_ndc_loc  = glGetUniformLocation(s_blit_prog, "dst_ndc");
    s_blit_gamma_loc = glGetUniformLocation(s_blit_prog, "u_gamma");
    s_blit_lut_loc = glGetUniformLocation(s_blit_prog, "lut");

    glGenVertexArrays(1, &s_blit_vao);

    LOGI("blit shader initialised (prog=%u vao=%u tex_loc=%d ndc_loc=%d)",
         s_blit_prog, s_blit_vao, s_blit_tex_loc, s_blit_ndc_loc);
}

// ---- Stub implementations ----

void xemu_hud_init(SDL_Window* window, void* sdl_gl_context) {
    LOGI("xemu_hud_init (stub)");
}

void xemu_hud_cleanup(void) {
    LOGI("xemu_hud_cleanup (stub)");
}

void xemu_hud_process_sdl_events(SDL_Event *event) {}

void xemu_hud_should_capture_kbd_mouse(int *kbd, int *mouse) {
    if (kbd)   *kbd   = 0;
    if (mouse) *mouse = 0;
}

void xemu_hud_set_framebuffer_texture(uint32_t tex, bool flip) {
    s_blit_tex = tex;
    (void)flip; /* orientation already handled by render_display */
}

void xemu_hud_update(void) {}

void xemu_hud_render(void)
{
    if (s_blit_tex == 0) {
        return;
    }

    if (s_blit_prog == 0) {
        init_blit_resources();
        if (s_blit_prog == 0) return; /* compile failed */
    }

    /* Get current viewport set by gl_render_frame */
    GLint vp[4];
    glGetIntegerv(GL_VIEWPORT, vp);
    float w = (float)vp[2];
    float h = (float)vp[3];

    /* Compute destination rect in NDC space.
     * g_aspect_ratio:
     *   0 = Native  — largest integer multiple of Xbox 640x480, centered
     *   1 = Auto    — (default) follows Boxxy's Widescreen setting for
     *                 this game, the way a real TV is set to 16:9 by hand:
     *                 on -> 16:9 fill, off -> 4:3 pillarbox.  On component
     *                 and composite nothing tells the TV what a game
     *                 renders, and games need not flag it -- an anamorphic
     *                 picture can come with a "4:3" mode set -- so the
     *                 kernel's aspect GPIO (xemu_get_widescreen()) is only
     *                 logged, not trusted (FINDINGS BC).
     *                 A widescreen game renders 16:9 squeezed into 640x480
     *                 and expects the TV to stretch it, so stretching is
     *                 right exactly then; a 4:3 picture must not be.
     *   2 = 4:3     — pillarbox/letterbox to preserve 4:3 AR
     *   3 = 16:9    — stretch to fill viewport */
    float x0 = -1.0f, y0 = -1.0f, x1 = 1.0f, y1 = 1.0f;
    if (w > 0.0f && h > 0.0f) {
        bool auto_wide = xemu_android_widescreen_enabled();
        if (g_aspect_ratio == 1) {
            /* Auto: log the decision, and the game's own aspect signal
             * beside it for diagnosis, whenever either changes. */
            static int last = -1;
            int now = auto_wide | (xemu_get_widescreen() << 1);
            if (now != last) {
                LOGI("aspect Auto: widescreen setting %s -> showing %s "
                     "(game's aspect signal: %s)",
                     auto_wide ? "on" : "off",
                     auto_wide ? "16:9 (fill)" : "4:3 (pillarbox)",
                     xemu_get_widescreen() ? "widescreen" : "4:3");
                last = now;
            }
        }
        bool four_three = g_aspect_ratio == 2 ||
                          (g_aspect_ratio == 1 && !auto_wide);

        if (g_aspect_ratio == 0) {
            /* Native: largest integer scale of 640×480 that fits */
            int scale = (int)(w / 640.0f);
            int scaleh = (int)(h / 480.0f);
            if (scaleh < scale) scale = scaleh;
            if (scale < 1) scale = 1;
            float rw = (640.0f * scale) / w;
            float rh = (480.0f * scale) / h;
            x0 = -rw; x1 = rw; y0 = -rh; y1 = rh;
        } else if (four_three) {
            /* 4:3: pillarbox or letterbox */
            const float target_ar = 4.0f / 3.0f;
            float screen_ar = w / h;
            if (screen_ar > target_ar) {
                float rect_w_ndc = target_ar / screen_ar;
                x0 = -rect_w_ndc;
                x1 =  rect_w_ndc;
            } else if (screen_ar < target_ar) {
                float rect_h_ndc = screen_ar / target_ar;
                y0 = -rect_h_ndc;
                y1 =  rect_h_ndc;
            }
        }
        /* 16:9, or Auto on a widescreen console: fill the viewport (the
         * Thor's panel is 16:9) — NDC -1..1, no change */
    }

    /* Render to the EGL window surface (FBO 0) */
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(vp[0], vp[1], vp[2], vp[3]);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

    /* Clear pillarbox/letterbox bars to black */
    if (x0 > -1.0f || y0 > -1.0f) {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    update_gamma_lut();

    glUseProgram(s_blit_prog);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, s_lut_tex);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_blit_tex);
    {
        GLenum filter = g_filter_nearest ? GL_NEAREST : GL_LINEAR;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    }
    glUniform1i(s_blit_tex_loc, 0);
    glUniform4f(s_blit_ndc_loc, x0, y0, x1, y1);
    glUniform1f(s_blit_gamma_loc, g_gamma);
    glUniform1i(s_blit_lut_loc, 1);

    glBindVertexArray(s_blit_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

// ---- Notification stubs ----
void xemu_queue_notification(const char *msg) {
    LOGI("Notification: %s", msg);
}

void xemu_queue_error_message(const char *msg) {
    LOGI("Error notification: %s", msg);
}

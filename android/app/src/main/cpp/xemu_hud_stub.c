#include "xemu_hud_stub.h"
#include <android/log.h>
#include "ui/xemu-notifications.h"
#include <GLES3/gl3.h>
#include <stdlib.h>

#define LOG_TAG "xemu-android-hud-stub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Globals normally defined in ui/xui/main.cc (extern'd in common.hh)
bool g_screenshot_pending = false;
float g_main_menu_height = 0.0f;

// ---- Aspect ratio ----
// true  = 16:9 (stretch to fill screen, default)
// false = 4:3  (pillarbox/letterbox to preserve Xbox AR)
static bool g_aspect_16x9 = true;

void xemu_hud_set_aspect_16x9(bool wide) {
    g_aspect_16x9 = wide;
}

// ---- Fullscreen blit state ----
static GLuint s_blit_tex  = 0;
static GLuint s_blit_prog = 0;
static GLuint s_blit_vao  = 0;
static GLint  s_blit_tex_loc  = -1;
static GLint  s_blit_ndc_loc  = -1;

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
     * no additional flip is needed. */
    const char *fs =
        "#version 300 es\n"
        "precision highp float;\n"
        "uniform sampler2D tex;\n"
        "in vec2 v_uv;\n"
        "layout(location = 0) out vec4 fragColor;\n"
        "void main() {\n"
        "    fragColor = vec4(texture(tex, v_uv).rgb, 1.0);\n"
        "}\n";

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

static int s_blit_frame = 0;

void xemu_hud_render(void)
{
    s_blit_frame++;

    if (s_blit_tex == 0) {
        if (s_blit_frame % 60 == 1) LOGI("xemu_hud_render: s_blit_tex=0, skipping");
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
     * 16:9 mode: fill the whole viewport (NDC -1..1).
     * 4:3 mode:  pillarbox or letterbox to preserve 4:3 Xbox output. */
    float x0 = -1.0f, y0 = -1.0f, x1 = 1.0f, y1 = 1.0f;
    if (!g_aspect_16x9 && w > 0.0f && h > 0.0f) {
        const float target_ar = 4.0f / 3.0f;
        float screen_ar = w / h;
        if (screen_ar > target_ar) {
            /* Screen is wider than 4:3 → pillarbox: shrink x */
            float rect_w_ndc = target_ar / screen_ar; /* fraction of half-width */
            x0 = -rect_w_ndc;
            x1 =  rect_w_ndc;
        } else if (screen_ar < target_ar) {
            /* Screen is taller than 4:3 → letterbox: shrink y */
            float rect_h_ndc = screen_ar / target_ar;
            y0 = -rect_h_ndc;
            y1 =  rect_h_ndc;
        }
    }

    if (s_blit_frame % 60 == 1) {
        LOGI("xemu_hud_render: tex=%u vp=%d,%d,%d,%d prog=%u ar=%s ndc=(%.3f,%.3f,%.3f,%.3f)",
             s_blit_tex, vp[0], vp[1], vp[2], vp[3], s_blit_prog,
             g_aspect_16x9 ? "16:9" : "4:3", x0, y0, x1, y1);
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

    glUseProgram(s_blit_prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_blit_tex);
    glUniform1i(s_blit_tex_loc, 0);
    glUniform4f(s_blit_ndc_loc, x0, y0, x1, y1);

    glBindVertexArray(s_blit_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    /* Diagnostic: sample the center pixel to detect black output */
    if (s_blit_frame % 60 == 1 && vp[2] > 0 && vp[3] > 0) {
        unsigned char pixel[4] = {0};
        glReadPixels(vp[2]/2, vp[3]/2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        GLenum err = glGetError();
        LOGI("xemu_hud_render: center pixel rgba=(%d,%d,%d,%d) readpixels_err=0x%x",
             pixel[0], pixel[1], pixel[2], pixel[3], (unsigned)err);
    }
}

// ---- Notification stubs ----
void xemu_queue_notification(const char *msg) {
    LOGI("Notification: %s", msg);
}

void xemu_queue_error_message(const char *msg) {
    LOGI("Error notification: %s", msg);
}

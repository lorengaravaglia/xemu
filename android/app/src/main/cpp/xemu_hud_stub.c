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

// ---- Fullscreen blit state ----
static GLuint s_blit_tex  = 0;
static GLuint s_blit_prog = 0;
static GLuint s_blit_vao  = 0;
static GLint  s_blit_tex_loc  = -1;
static GLint  s_blit_size_loc = -1;

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
    const char *vs =
        "#version 300 es\n"
        "void main() {\n"
        "    float x = -1.0 + float((gl_VertexID & 1) << 2);\n"
        "    float y = -1.0 + float((gl_VertexID & 2) << 1);\n"
        "    gl_Position = vec4(x, y, 0.0, 1.0);\n"
        "}\n";

    /* Sample r->gl_display_buffer: after render_display's Y-flip, the
     * texture's texcoord (0,0) is Xbox bottom and (0,1) is Xbox top.
     * For correct screen output (GL y=0 = screen bottom = Xbox bottom)
     * no additional flip is needed. */
    const char *fs =
        "#version 300 es\n"
        "precision highp float;\n"
        "uniform sampler2D tex;\n"
        "uniform vec2 display_size;\n"
        "layout(location = 0) out vec4 fragColor;\n"
        "void main() {\n"
        "    vec2 uv = gl_FragCoord.xy / display_size;\n"
        "    fragColor = vec4(texture(tex, uv).rgb, 1.0);\n"
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
    s_blit_size_loc = glGetUniformLocation(s_blit_prog, "display_size");

    glGenVertexArrays(1, &s_blit_vao);

    LOGI("blit shader initialised (prog=%u vao=%u tex_loc=%d size_loc=%d)",
         s_blit_prog, s_blit_vao, s_blit_tex_loc, s_blit_size_loc);
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

    if (s_blit_frame % 60 == 1) {
        LOGI("xemu_hud_render: tex=%u vp=%d,%d,%d,%d prog=%u",
             s_blit_tex, vp[0], vp[1], vp[2], vp[3], s_blit_prog);
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

    glUseProgram(s_blit_prog);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, s_blit_tex);
    glUniform1i(s_blit_tex_loc, 0);
    glUniform2f(s_blit_size_loc, w, h);

    glBindVertexArray(s_blit_vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);

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

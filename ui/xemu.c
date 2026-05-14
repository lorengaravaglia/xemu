/*
 * xemu SDL display driver
 *
 * Copyright (c) 2020-2025 Matt Borgerson
 *
 * Based on sdl2.c, sdl2-gl.c
 *
 * Copyright (c) 2003 Fabrice Bellard
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
/* Ported SDL 1.2 code to 2.0 by Dave Airlie. */

#include "qemu/osdep.h"
#include "qemu/module.h"
#include "qemu/thread.h"
#include "qemu/main-loop.h"
#include "qemu/rcu.h"
#include "qemu-version.h"
#include "qapi/error.h"
#include "qapi/qapi-commands-block.h"
#include "qobject/qdict.h"
#include "ui/console.h"
#include "ui/input.h"
#include "ui/kbd-state.h"
#include "system/runstate.h"
#include "system/runstate-action.h"
#include "system/system.h"
#include "block/block-global-state.h"
#if defined(__ANDROID__) || defined(ANDROID)
#include "../android/app/src/main/cpp/xemu_hud_stub.h"
#else
#include "xui/xemu-hud.h"
#endif
#include "xemu-input.h"
#include "xemu-settings.h"
#include "xemu-snapshots.h"
#include "xemu-version.h"
#if defined(__ANDROID__) || defined(ANDROID)
#include "../android/app/src/main/cpp/xemu_os_utils_android.h"
#else
#include "xemu-os-utils.h"
#endif

#if defined(__ANDROID__) || defined(ANDROID)
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <android/native_window.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/syscall.h>
#include <math.h>
#include "../android/app/src/main/cpp/xemu_android.h"
#define ALOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, "xemu-android", __VA_ARGS__))
#define ALOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "xemu-android", __VA_ARGS__))

extern EGLDisplay __attribute__((weak)) egl_display;
extern EGLContext __attribute__((weak)) egl_context;
extern EGLSurface __attribute__((weak)) egl_surface;
extern EGLConfig  __attribute__((weak)) egl_config;
extern void __attribute__((weak)) set_egl_current(bool current);
extern pid_t __attribute__((weak)) xemu_main_thread_id;
extern ThreadArgs __attribute__((weak)) *g_android_args;
extern ANativeWindow __attribute__((weak)) *xemu_android_get_window(void);

bool xemu_is_main_thread(void)
{
#if defined(__ANDROID__) || defined(ANDROID)
    return syscall(SYS_gettid) == xemu_main_thread_id && xemu_main_thread_id != 0;
#else
    return true;
#endif
}

#if defined(__ANDROID__) || defined(ANDROID)
/* Called from JNI (Java main thread) via NativeInterface.pauseEmulation() /
 * resumeEmulation() when the app goes to background / foreground.
 * vm_stop/vm_start pause TCG, PFIFO, PGRAPH and all QEMU device threads,
 * which also drains the AAudio ring buffer to silence. */
void xemu_android_vm_pause(void)
{
    if (!xemu_android_qemu_initialized()) return;
    bql_lock();
    if (runstate_is_running()) {
        vm_stop(RUN_STATE_PAUSED);
    }
    bql_unlock();
}

void xemu_android_vm_resume(void)
{
    if (!xemu_android_qemu_initialized()) return;
    bql_lock();
    if (runstate_check(RUN_STATE_PAUSED)) {
        vm_start();
    }
    bql_unlock();
}

/* Frame counter incremented after each eglSwapBuffers — read by JNI to
 * compute FPS in the Kotlin UI layer. */
static volatile int g_rendered_frame_count = 0;

int xemu_android_get_rendered_frame_count(void)
{
    return g_rendered_frame_count;
}

/* save_snapshot(), load_snapshot(), and bdrv_drain_all_begin() all assert
 * qemu_in_main_thread() — they must run on the QEMU main loop thread.
 * We dispatch all such work via a single bottom-half handler and block the
 * JNI thread on a QemuSemaphore until the BH completes.
 *
 * The QEMU main loop holds BQL when it dispatches BHs (bql_lock_impl asserts
 * !bql_locked(), so we must never call bql_lock() inside the BH).
 * vm_stop()/vm_start() work because BQL is already held.
 * load/save_snapshot() internally yield/re-acquire BQL via QEMU coroutines
 * for their block I/O — BQL is restored before they return.
 * bdrv_drain_all_begin/end must be called with BQL held; they manage BQL
 * internally via AIO_WAIT_WHILE_UNLOCKED. */
#include "migration/snapshot.h"

typedef enum { MAIN_OP_SAVE, MAIN_OP_LOAD, MAIN_OP_FLUSH } MainThreadOp;

static struct {
    MainThreadOp op;
    char         name[64];
    bool         ok;
    Error       *err;
} g_main_req;

static QemuSemaphore g_main_done;
static QemuMutex     g_main_lock;
static QEMUBH       *g_main_bh;

static void main_thread_bh(void *opaque)
{
    switch (g_main_req.op) {
    case MAIN_OP_SAVE:
        save_snapshot(g_main_req.name, true, NULL, false, NULL,
                      &g_main_req.err);
        xemu_snapshots_mark_dirty();
        break;

    case MAIN_OP_LOAD: {
        bool was_running = runstate_is_running();
        vm_stop(RUN_STATE_RESTORE_VM);
        g_main_req.ok = load_snapshot(g_main_req.name, NULL, false, NULL,
                                      &g_main_req.err);
        if (g_main_req.ok && was_running) {
            vm_start();
        }
        break;
    }

    case MAIN_OP_FLUSH:
        /* vm_stop under BQL (already held) */
        if (runstate_is_running()) {
            vm_stop(RUN_STATE_SHUTDOWN);
        }
        /* bdrv_drain_all_begin() asserts bql_locked() (qemu_in_main_thread ==
         * bql_locked() in this QEMU build) and manages BQL internally via
         * AIO_WAIT_WHILE_UNLOCKED — do NOT unlock BQL before calling it. */
        bdrv_drain_all_begin();
        bdrv_flush_all();
        bdrv_drain_all_end();
        break;
    }
    qemu_sem_post(&g_main_done);
}

static void main_bh_ensure_init(void)
{
    static bool initialised = false;
    if (initialised) return;
    initialised = true;
    qemu_mutex_init(&g_main_lock);
    qemu_sem_init(&g_main_done, 0);
    g_main_bh = qemu_bh_new(main_thread_bh, NULL);
}

static void dispatch_main_op(MainThreadOp op, const char *name)
{
    if (!xemu_android_qemu_initialized()) return;
    main_bh_ensure_init();

    qemu_mutex_lock(&g_main_lock);
    g_main_req.op   = op;
    g_main_req.ok   = false;
    g_main_req.err  = NULL;
    if (name) {
        snprintf(g_main_req.name, sizeof(g_main_req.name), "%s", name);
    }

    qemu_bh_schedule(g_main_bh);
    qemu_sem_wait(&g_main_done);

    if (g_main_req.err) {
        const char *opname = (op == MAIN_OP_SAVE) ? "save"
                           : (op == MAIN_OP_LOAD) ? "load" : "flush";
        ALOGE("main_op '%s' (%s): %s", name ? name : "", opname,
              error_get_pretty(g_main_req.err));
        error_free(g_main_req.err);
        g_main_req.err = NULL;
    }
    qemu_mutex_unlock(&g_main_lock);
}

/* Stop the VM and flush all block devices (qcow2 HDD) to disk so the dirty
 * bit is cleared before _exit().  bdrv_drain_all_begin() asserts
 * qemu_in_main_thread(), so dispatch via BH like save/load state. */
void xemu_android_flush_block_devices(void)
{
    dispatch_main_op(MAIN_OP_FLUSH, NULL);
}

void xemu_android_save_state(const char *name)
{
    dispatch_main_op(MAIN_OP_SAVE, name);
}

void xemu_android_load_state(const char *name)
{
    dispatch_main_op(MAIN_OP_LOAD, name);
}

/* Return names of all existing snapshots in *out_names (caller frees each entry
 * and the array itself with g_free).  Returns count; 0 on error or no snapshots. */
int xemu_android_list_states(char ***out_names)
{
    *out_names = NULL;
    if (!xemu_android_qemu_initialized()) return 0;

    Error *err = NULL;
    BlockDriverState *bs;
    QEMUSnapshotInfo *sn_list = NULL;
    int n;

    bql_lock();
    bs = bdrv_all_find_vmstate_bs(NULL, false, NULL, &err);
    if (!bs) {
        bql_unlock();
        if (err) { error_free(err); }
        return 0;
    }
    n = bdrv_snapshot_list(bs, &sn_list);
    bql_unlock();

    if (n <= 0) {
        g_free(sn_list);
        return 0;
    }

    char **names = (char **)g_malloc(n * sizeof(char *));
    for (int i = 0; i < n; i++) {
        names[i] = g_strdup(sn_list[i].name);
    }
    g_free(sn_list);
    *out_names = names;
    return n;
}

void xemu_android_free_state_names(char **names, int count)
{
    if (!names) return;
    for (int i = 0; i < count; i++) {
        g_free(names[i]);
    }
    g_free(names);
}
#endif
#else
#define ALOGI(...) fprintf(stderr, __VA_ARGS__)
#define ALOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

#include "data/xemu_64x64.png.h"

#include "hw/xbox/smbus.h" // For eject, drive tray
#include "hw/xbox/nv2a/nv2a.h"
#include "hw/core/cpu.h"
#include "ui/xemu-notifications.h"

#include <stb_image.h>
#include <locale.h>
#include <math.h>
#include <SDL3/SDL.h>

#ifndef DEBUG_XEMU_C
#define DEBUG_XEMU_C 0
#endif

#if DEBUG_XEMU_C
#define DPRINTF(...) fprintf(stderr, __VA_ARGS__)
#else
#define DPRINTF(...)
#endif

uint64_t vblank_interval_ns = 16666666LL;
bool use_vblank_timer_thread = true;

uint32_t xemu_get_ticks(void)
{
#if defined(__ANDROID__) || defined(ANDROID)
    return (uint32_t)(qemu_clock_get_ns(QEMU_CLOCK_REALTIME) / 1000000);
#else
    return (uint32_t)SDL_GetTicks();
#endif
}

struct xemu_console {
    DisplayChangeListener dcl;
    DisplaySurface *surface;
    DisplayOptions *opts;
    SDL_Window *real_window;
    int idx;
    int hidden;
    int ignore_hotkeys;
    SDL_GLContext winctx;
    QKbdState *kbd;
};

#ifdef _WIN32
#include "nvapi.h"
// Provide hint to prefer high-performance graphics for hybrid systems
// https://gpuopen.com/learn/amdpowerxpressrequesthighperformance/
__declspec(dllexport) DWORD AmdPowerXpressRequestHighPerformance = 1;
// https://docs.nvidia.com/gameworks/content/technologies/desktop/optimus.htm
__declspec(dllexport) DWORD NvOptimusEnablement = 1;
#endif

static int num_outputs;
static struct xemu_console *scon_list;
static SDL_Surface *guest_sprite_surface;
static int gui_grab; /* if true, all keyboard/mouse events are grabbed */
static bool alt_grab;
static bool ctrl_grab;
static int gui_saved_grab;
static int gui_fullscreen;
static int gui_grab_code = SDL_KMOD_LALT | SDL_KMOD_LCTRL;
static SDL_Cursor *sdl_cursor_normal;
static SDL_Cursor *sdl_cursor_hidden;
static int absolute_enabled;
static int guest_cursor;
static int guest_x, guest_y;
static SDL_Cursor *guest_sprite;
static Notifier mouse_mode_notifier;
static SDL_Window *m_window;
static SDL_GLContext m_context;
static QemuSemaphore display_init_sem;
static QemuSemaphore display_shutdown_sem;
static QEMUTimer *vblank_timer;
static QemuThread vblank_thread;
static bool qemu_exiting;
static int exit_status;

void tcg_register_init_ctx(void); // tcg.c

#if DEBUG_XEMU_C
static uint64_t lock_held_acc;
static uint64_t lock_start;
#endif

void xemu_main_loop_lock(void)
{
    qemu_mutex_lock_main_loop();
    bql_lock();
#if DEBUG_XEMU_C
    lock_start = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
#endif
}

void xemu_main_loop_unlock(void)
{
#if DEBUG_XEMU_C
    lock_held_acc += qemu_clock_get_ns(QEMU_CLOCK_REALTIME) - lock_start;
#endif
    bql_unlock();
    qemu_mutex_unlock_main_loop();
}

SDL_Window *xemu_get_window(void)
{
    return m_window;
}

static struct xemu_console *get_scon_from_window(uint32_t window_id)
{
    int i;
    for (i = 0; i < num_outputs; i++) {
        if (scon_list[i].real_window == SDL_GetWindowFromID(window_id)) {
            return &scon_list[i];
        }
    }
    return NULL;
}

static void window_resize(struct xemu_console *scon)
{
    if (!scon->real_window) {
        return;
    }

    SDL_SetWindowSize(scon->real_window,
                      surface_width(scon->surface),
                      surface_height(scon->surface));
}

static void hide_cursor(struct xemu_console *scon)
{
    if (scon->opts->has_show_cursor && scon->opts->show_cursor) {
        return;
    }

    SDL_HideCursor();
    SDL_SetCursor(sdl_cursor_hidden);

    if (scon->real_window && !qemu_input_is_absolute(scon->dcl.con)) {
        SDL_SetWindowRelativeMouseMode(scon->real_window, true);
    }
}

static void show_cursor(struct xemu_console *scon)
{
    if (scon->opts->has_show_cursor && scon->opts->show_cursor) {
        return;
    }

    if (scon->real_window && !qemu_input_is_absolute(scon->dcl.con)) {
        SDL_SetWindowRelativeMouseMode(scon->real_window, false);
    }

    if (guest_cursor &&
        (gui_grab || qemu_input_is_absolute(scon->dcl.con) || absolute_enabled)) {
        SDL_SetCursor(guest_sprite);
    } else {
        SDL_SetCursor(sdl_cursor_normal);
    }

    SDL_ShowCursor();
}

static void grab_start(struct xemu_console *scon)
{
}

static void grab_end(struct xemu_console *scon)
{
    SDL_SetWindowKeyboardGrab(scon->real_window, false);
    SDL_SetWindowMouseGrab(scon->real_window, false);
    gui_grab = 0;
    show_cursor(scon);
}

static void absolute_mouse_grab(struct xemu_console *scon)
{
    float mouse_x, mouse_y;
    int scr_w, scr_h;
    SDL_GetMouseState(&mouse_x, &mouse_y);
    SDL_GetWindowSize(scon->real_window, &scr_w, &scr_h);
    if (mouse_x > 0 && mouse_x < scr_w - 1 &&
        mouse_y > 0 && mouse_y < scr_h - 1) {
        grab_start(scon);
    }
}

static void mouse_mode_change(Notifier *notify, void *data)
{
    if (qemu_input_is_absolute(scon_list[0].dcl.con)) {
        if (!absolute_enabled) {
            absolute_enabled = 1;
            if (scon_list[0].real_window) {
                SDL_SetWindowRelativeMouseMode(scon_list[0].real_window, false);
            }
            absolute_mouse_grab(&scon_list[0]);
        }
    } else if (absolute_enabled) {
        if (!gui_fullscreen) {
            grab_end(&scon_list[0]);
        }
        absolute_enabled = 0;
    }
}

static void send_mouse_event(struct xemu_console *scon, int dx, int dy,
                                 int x, int y, int state)
{
    static uint32_t bmap[INPUT_BUTTON__MAX] = {
        [INPUT_BUTTON_LEFT]       = SDL_BUTTON_MASK(SDL_BUTTON_LEFT),
        [INPUT_BUTTON_MIDDLE]     = SDL_BUTTON_MASK(SDL_BUTTON_MIDDLE),
        [INPUT_BUTTON_RIGHT]      = SDL_BUTTON_MASK(SDL_BUTTON_RIGHT),
    };
    static uint32_t prev_state;

    if (prev_state != state) {
        qemu_input_update_buttons(scon->dcl.con, bmap, prev_state, state);
        prev_state = state;
    }

    if (qemu_input_is_absolute(scon->dcl.con)) {
        qemu_input_queue_abs(scon->dcl.con, INPUT_AXIS_X,
                             x, 0, surface_width(scon->surface));
        qemu_input_queue_abs(scon->dcl.con, INPUT_AXIS_Y,
                             y, 0, surface_height(scon->surface));
    } else {
        if (guest_cursor) {
            x -= guest_x;
            y -= guest_y;
            guest_x += x;
            guest_y += y;
            dx = x;
            dy = y;
        }
        qemu_input_queue_rel(scon->dcl.con, INPUT_AXIS_X, dx);
        qemu_input_queue_rel(scon->dcl.con, INPUT_AXIS_Y, dy);
    }
    qemu_input_event_sync();
}

static void set_full_screen(struct xemu_console *scon, bool set)
{
    gui_fullscreen = set;

    if (gui_fullscreen) {
        const SDL_DisplayMode *mode = NULL;
        SDL_DisplayMode **modes = NULL;
        if (g_config.display.window.fullscreen_exclusive) {
            SDL_DisplayID display = SDL_GetDisplayForWindow(scon->real_window);
            if (display) {
                int num_modes = 0;
                modes = SDL_GetFullscreenDisplayModes(display, &num_modes);
                if (modes && num_modes > 0) {
                    // First mode is the highest resolution, typically the native resolution
                    mode = modes[0];
                }
            }
            if (mode) {
                fprintf(stderr, "Selected exclusive fullscreen mode: %dx%d pixel_density=%f refresh_rate=%f\n", mode->w, mode->h, mode->pixel_density, mode->refresh_rate);
            } else {
                fprintf(stderr, "Failed to get fullscreen display mode: %s\n", SDL_GetError());
            }
        }
        SDL_SetWindowFullscreenMode(scon->real_window, mode);
        SDL_free(modes);
        SDL_SetWindowFullscreen(scon->real_window, true);
        gui_saved_grab = gui_grab;
        grab_start(scon);
    } else {
        if (!gui_saved_grab) {
            grab_end(scon);
        }
        SDL_SetWindowFullscreen(scon->real_window, false);
    }
}

static void toggle_full_screen(struct xemu_console *scon)
{
    set_full_screen(scon, !gui_fullscreen);
}

void xemu_toggle_fullscreen(void)
{
    toggle_full_screen(&scon_list[0]);
}

int xemu_is_fullscreen(void)
{
    return gui_fullscreen;
}

static int get_mod_state(void)
{
    SDL_Keymod mod = SDL_GetModState();

    if (alt_grab) {
        return (mod & (gui_grab_code | SDL_KMOD_LSHIFT)) ==
            (gui_grab_code | SDL_KMOD_LSHIFT);
    } else if (ctrl_grab) {
        return (mod & SDL_KMOD_RCTRL) == SDL_KMOD_RCTRL;
    } else {
        return (mod & gui_grab_code) == gui_grab_code;
    }
}

static void process_key(struct xemu_console *scon, SDL_KeyboardEvent *ev)
{
    int qcode;

    if (ev->scancode >= qemu_input_map_usb_to_qcode_len) {
        return;
    }
    qcode = qemu_input_map_usb_to_qcode[ev->scancode];
    qkbd_state_key_event(scon->kbd, qcode, ev->type == SDL_EVENT_KEY_DOWN);
}

static void handle_keydown(SDL_Event *ev)
{
    int win;
    struct xemu_console *scon = get_scon_from_window(ev->key.windowID);
    if (scon == NULL) return;
    int gui_key_modifier_pressed = get_mod_state();
    int gui_keysym = 0;

    if (!scon->ignore_hotkeys && gui_key_modifier_pressed && !ev->key.repeat) {
        switch (ev->key.scancode) {
        case SDL_SCANCODE_2:
        case SDL_SCANCODE_3:
        case SDL_SCANCODE_4:
        case SDL_SCANCODE_5:
        case SDL_SCANCODE_6:
        case SDL_SCANCODE_7:
        case SDL_SCANCODE_8:
        case SDL_SCANCODE_9:
            if (gui_grab) {
                grab_end(scon);
            }

            win = ev->key.scancode - SDL_SCANCODE_1;
            if (win < num_outputs) {
                scon_list[win].hidden = !scon_list[win].hidden;
                if (scon_list[win].real_window) {
                    if (scon_list[win].hidden) {
                        SDL_HideWindow(scon_list[win].real_window);
                    } else {
                        SDL_ShowWindow(scon_list[win].real_window);
                    }
                }
                gui_keysym = 1;
            }
            break;
        case SDL_SCANCODE_F:
            toggle_full_screen(scon);
            gui_keysym = 1;
            break;
        case SDL_SCANCODE_G:
            gui_keysym = 1;
            if (!gui_grab) {
                grab_start(scon);
            } else if (!gui_fullscreen) {
                grab_end(scon);
            }
            break;
        case SDL_SCANCODE_U:
            window_resize(scon);
            gui_keysym = 1;
            break;
        default:
            break;
        }
    }
    if (!gui_keysym) {
        process_key(scon, &ev->key);
    }
}

static void handle_keyup(SDL_Event *ev)
{
    struct xemu_console *scon = get_scon_from_window(ev->key.windowID);
    if (!scon) return;

    scon->ignore_hotkeys = false;
    process_key(scon, &ev->key);
}

static void handle_mousemotion(SDL_Event *ev)
{
    int max_x, max_y;
    struct xemu_console *scon = get_scon_from_window(ev->motion.windowID);

    if (!scon || !qemu_console_is_graphic(scon->dcl.con)) {
        return;
    }

    if (qemu_input_is_absolute(scon->dcl.con) || absolute_enabled) {
        int scr_w, scr_h;
        SDL_GetWindowSize(scon->real_window, &scr_w, &scr_h);
        max_x = scr_w - 1;
        max_y = scr_h - 1;
        if (gui_grab && !gui_fullscreen
            && (ev->motion.x == 0 || ev->motion.y == 0 ||
                ev->motion.x == max_x || ev->motion.y == max_y)) {
            grab_end(scon);
        }
        if (!gui_grab &&
            (ev->motion.x > 0 && ev->motion.x < max_x &&
             ev->motion.y > 0 && ev->motion.y < max_y)) {
            grab_start(scon);
        }
    }
    if (gui_grab || qemu_input_is_absolute(scon->dcl.con) || absolute_enabled) {
        send_mouse_event(scon, ev->motion.xrel, ev->motion.yrel,
                             ev->motion.x, ev->motion.y, ev->motion.state);
    }
}

static void handle_mousebutton(SDL_Event *ev)
{
    int buttonstate = SDL_GetMouseState(NULL, NULL);
    SDL_MouseButtonEvent *bev;
    struct xemu_console *scon = get_scon_from_window(ev->button.windowID);

    if (!scon || !qemu_console_is_graphic(scon->dcl.con)) {
        return;
    }

    bev = &ev->button;
    if (!gui_grab && !qemu_input_is_absolute(scon->dcl.con)) {
        if (ev->type == SDL_EVENT_MOUSE_BUTTON_UP && bev->button == SDL_BUTTON_LEFT) {
            /* start grabbing all events */
            grab_start(scon);
        }
    } else {
        if (ev->type == SDL_EVENT_MOUSE_BUTTON_DOWN) {
            buttonstate |= SDL_BUTTON_MASK(bev->button);
        } else {
            buttonstate &= ~SDL_BUTTON_MASK(bev->button);
        }
        send_mouse_event(scon, 0, 0, bev->x, bev->y, buttonstate);
    }
}

static void handle_mousewheel(SDL_Event *ev)
{
    struct xemu_console *scon = get_scon_from_window(ev->wheel.windowID);
    SDL_MouseWheelEvent *wev = &ev->wheel;
    InputButton btn;

    if (!scon || !qemu_console_is_graphic(scon->dcl.con)) {
        return;
    }

    if (wev->y > 0) {
        btn = INPUT_BUTTON_WHEEL_UP;
    } else if (wev->y < 0) {
        btn = INPUT_BUTTON_WHEEL_DOWN;
    } else {
        return;
    }

    qemu_input_queue_btn(scon->dcl.con, btn, true);
    qemu_input_event_sync();
    qemu_input_queue_btn(scon->dcl.con, btn, false);
    qemu_input_event_sync();
}

static void handle_windowevent(SDL_Event *ev)
{
    struct xemu_console *scon = get_scon_from_window(ev->window.windowID);
    bool allow_close = true;

    if (!scon) {
        return;
    }

    switch (ev->type) {
    case SDL_EVENT_WINDOW_RESIZED:
        {
            QemuUIInfo info;
            memset(&info, 0, sizeof(info));
            info.width = ev->window.data1;
            info.height = ev->window.data2;
            dpy_set_ui_info(scon->dcl.con, &info, true);

            if (!gui_fullscreen) {
                g_config.display.window.last_width = ev->window.data1;
                g_config.display.window.last_height = ev->window.data2;
            }
        }
        break;
    case SDL_EVENT_WINDOW_FOCUS_GAINED:
    case SDL_EVENT_WINDOW_MOUSE_ENTER:
        if (!gui_grab && (qemu_input_is_absolute(scon->dcl.con) || absolute_enabled)) {
            absolute_mouse_grab(scon);
        }
        /* If a new console window opened using a hotkey receives the
         * focus, SDL sends another KEYDOWN event to the new window,
         * closing the console window immediately after.
         *
         * Work around this by ignoring further hotkey events until a
         * key is released.
         */
        scon->ignore_hotkeys = get_mod_state();
        break;
    case SDL_EVENT_WINDOW_FOCUS_LOST:
        if (gui_grab && !gui_fullscreen) {
            grab_end(scon);
        }
        break;
    case SDL_EVENT_WINDOW_CLOSE_REQUESTED:
        if (qemu_console_is_graphic(scon->dcl.con)) {
            if (scon->opts->has_window_close && !scon->opts->window_close) {
                allow_close = false;
            }
            if (allow_close) {
                shutdown_action = SHUTDOWN_ACTION_POWEROFF;
                qemu_system_shutdown_request(SHUTDOWN_CAUSE_HOST_UI);
            }
        } else {
            SDL_HideWindow(scon->real_window);
            scon->hidden = true;
        }
        break;
    case SDL_EVENT_WINDOW_SHOWN:
        scon->hidden = false;
        break;
    case SDL_EVENT_WINDOW_HIDDEN:
        scon->hidden = true;
        break;
    }
}

static void mouse_warp(DisplayChangeListener *dcl,
                       int x, int y, bool on)
{
    struct xemu_console *scon = container_of(dcl, struct xemu_console, dcl);

    if (!qemu_console_is_graphic(scon->dcl.con)) {
        return;
    }

    if (on) {
        if (!guest_cursor) {
            show_cursor(scon);
        }
        if (gui_grab || qemu_input_is_absolute(scon->dcl.con) || absolute_enabled) {
            SDL_SetCursor(guest_sprite);
            if (!qemu_input_is_absolute(scon->dcl.con) && !absolute_enabled) {
                SDL_WarpMouseInWindow(scon->real_window, x, y);
            }
        }
    } else if (gui_grab) {
        hide_cursor(scon);
    }
    guest_cursor = on;
    guest_x = x, guest_y = y;
}

static void mouse_define(DisplayChangeListener *dcl,
                             QEMUCursor *c)
{

    if (guest_sprite) {
        SDL_DestroyCursor(guest_sprite);
    }

    if (guest_sprite_surface) {
        SDL_DestroySurface(guest_sprite_surface);
    }

    guest_sprite_surface =
        SDL_CreateSurfaceFrom(c->width, c->height, SDL_PIXELFORMAT_ARGB8888, c->data, c->width * 4);

    if (!guest_sprite_surface) {
        fprintf(stderr, "Failed to make rgb surface from %p\n", c);
        return;
    }
    guest_sprite = SDL_CreateColorCursor(guest_sprite_surface,
                                         c->hot_x, c->hot_y);
    if (!guest_sprite) {
        fprintf(stderr, "Failed to make color cursor from %p\n", c);
        return;
    }
    if (guest_cursor &&
        (gui_grab || qemu_input_is_absolute(dcl->con) || absolute_enabled)) {
        SDL_SetCursor(guest_sprite);
    }
}

static void xb_surface_gl_create_texture(DisplaySurface *surface)
{
    assert(QEMU_IS_ALIGNED(surface_stride(surface), surface_bytes_per_pixel(surface)));

    switch (surface_format(surface)) {
    case PIXMAN_BE_b8g8r8x8:
    case PIXMAN_BE_b8g8r8a8:
#if defined(__ANDROID__) || defined(ANDROID)
        // GLES 3.0 prefers RGBA
        surface->glformat = GL_RGBA;
#else
        surface->glformat = GL_BGRA_EXT;
#endif
        surface->gltype = GL_UNSIGNED_BYTE;
        break;
    case PIXMAN_BE_x8r8g8b8:
    case PIXMAN_BE_a8r8g8b8:
        surface->glformat = GL_RGBA;
        surface->gltype = GL_UNSIGNED_BYTE;
        break;
    case PIXMAN_r5g6b5:
        surface->glformat = GL_RGB;
        surface->gltype = GL_UNSIGNED_SHORT_5_6_5;
        break;
    default:
        /* Log the unknown pixman format instead of crashing; use a safe fallback. */
        fprintf(stderr, "xb_surface_gl_create_texture: unhandled pixman format 0x%x, using GL_RGBA fallback\n",
                surface_format(surface));
        surface->glformat = GL_RGBA;
        surface->gltype = GL_UNSIGNED_BYTE;
        break;
    }

    if (!surface->texture) {
        glGenTextures(1, &surface->texture);
    }
    glBindTexture(GL_TEXTURE_2D, surface->texture);
#if defined(__ANDROID__) || defined(ANDROID)
    // GLES doesn't have GL_UNPACK_ROW_LENGTH_EXT in standard (only some extensions)
    // For now skip it if not supported, but xemu expects it.
    // GLES 3.0 HAS GL_UNPACK_ROW_LENGTH.
    glPixelStorei(GL_UNPACK_ROW_LENGTH,
                  surface_stride(surface) / surface_bytes_per_pixel(surface));
#else
    glPixelStorei(GL_UNPACK_ROW_LENGTH_EXT,
                  surface_stride(surface) / surface_bytes_per_pixel(surface));
#endif

    GLint internal_format = GL_RGB;
#if defined(__ANDROID__) || defined(ANDROID)
    if (surface->glformat == GL_RGBA) internal_format = GL_RGBA;
#endif

    glTexImage2D(GL_TEXTURE_2D, 0, internal_format,
                 surface_width(surface),
                 surface_height(surface),
                 0, surface->glformat, surface->gltype,
                 surface_data(surface));
    
#if defined(__ANDROID__) || defined(ANDROID)
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
#else
    glPixelStorei(GL_UNPACK_ROW_LENGTH_EXT, 0);
#endif

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

#if defined(__ANDROID__) || defined(ANDROID)
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        ALOGE("xb_surface_gl_create_texture: GL error 0x%x", err);
    }
#endif
}

static void xb_surface_gl_destroy_texture(DisplaySurface *surface)
{
    if (!surface || !surface->texture) {
        return;
    }
    glDeleteTextures(1, &surface->texture);
    surface->texture = 0;
}

static bool xb_console_gl_check_format(DisplayChangeListener *dcl,
                                       pixman_format_code_t format)
{
    switch (format) {
    case PIXMAN_BE_b8g8r8x8:
    case PIXMAN_BE_b8g8r8a8:
    case PIXMAN_r5g6b5:
        return true;
    default:
        return false;
    }
}

static void gl_switch(DisplayChangeListener *dcl,
                      DisplaySurface *new_surface)
{
    struct xemu_console *scon = container_of(dcl, struct xemu_console, dcl);
    scon->surface = new_surface;
}

static float update_avg(float avg, float ms, float r) {
    if (fabs(avg-ms) > 0.25*avg) avg = ms;
    else avg = avg*(1.0-r)+ms*r;
    return avg;
}

static float fps = 1.0;

static void update_fps(void)
{
    static float avg = 1.0;
    static int64_t last_update = 0;
    int64_t now = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
    if (!last_update) {
        last_update = now;
        return;
    }
    float ms = ((float)(now-last_update)/1000000.0);
    last_update = now;
    avg = update_avg(avg, ms, 0.5);
    fps = 1000.0/avg;
}

static void process_vblank(struct xemu_console *scon)
{
    assert(bql_locked());

    update_fps();

#if 0
    static uint64_t last_ns = 0;
    uint64_t now_ns = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
    uint64_t delta_ns = last_ns ? now_ns - last_ns : 0;
    fprintf(stderr, "%s delta_ns=%"PRId64"\n", __func__, delta_ns);
    last_ns = now_ns;
#endif

    graphic_hw_update(scon->dcl.con);
}

static void vblank_timer_callback(void *opaque)
{
    struct xemu_console *scon = (struct xemu_console *)opaque;

    int64_t now = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
    process_vblank(scon);
    timer_mod_ns(vblank_timer, now + vblank_interval_ns);
}

static void *vblank_timer_thread(void *opaque)
{
    struct xemu_console *scon = (struct xemu_console *)opaque;
    int64_t next_vblank = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
#if defined(__ANDROID__) || defined(ANDROID)
    int frames = 0;
    ALOGI("vblank_timer_thread starting (thread %ld)", (long)syscall(SYS_gettid));
#endif

    while (!qatomic_read(&qemu_exiting)) {
        // Schedule next vblank at fixed interval (absolute deadline)
        next_vblank += vblank_interval_ns;

        // Wait until deadline
        int64_t now = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
        if (now < next_vblank) {
#if !defined(__ANDROID__) && !defined(ANDROID)
            SDL_DelayPrecise(next_vblank - now);
#else
            g_usleep((next_vblank - now) / 1000);
#endif
        } else if (now > next_vblank + vblank_interval_ns) {
            // We've fallen behind by more than one frame, reset to avoid
            // rapid-fire catch-up
            next_vblank = now;
        }

        if (!qatomic_read(&qemu_exiting)) {
#if defined(__ANDROID__) || defined(ANDROID)
            /* When paused and no surface, block here instead of acquiring
             * the BQL 60 times/second for a no-op process_vblank call.
             * Reset next_vblank after waking so we resume at the correct
             * cadence without a catch-up burst. */
            if (!runstate_is_running() && !xemu_android_surface_valid()) {
                xemu_android_wait_for_surface();
                next_vblank = qemu_clock_get_ns(QEMU_CLOCK_REALTIME);
                continue;
            }
            frames++;
#endif
            xemu_main_loop_lock();
            process_vblank(scon);
#if defined(__ANDROID__) || defined(ANDROID)
            /* Every 5 seconds (300 vblanks at 60 Hz): log vCPU liveness.
             * Reports halted/running/stopped so we can tell whether the vCPU
             * is executing TCG code, waiting in HLT, or externally stopped. */
            if (frames % 300 == 0) {
                CPUState *vcpu = first_cpu;
                if (vcpu) {
                    ALOGI("vCPU health: halted=%u running=%d stopped=%d irq_req=0x%x",
                          vcpu->halted, (int)vcpu->running,
                          (int)vcpu->stopped, (unsigned)vcpu->interrupt_request);
                }
            }
#endif
            xemu_main_loop_unlock();
        }
    }

    return NULL;
}

#if DEBUG_XEMU_C
static void report_stats(void)
{
    uint64_t now = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
    static uint64_t last_reported = 0;
    static int num_frames = 0;
    uint64_t delta_ms = now - last_reported;
    num_frames += 1;
    if (delta_ms >= 1000) {
        DPRINTF("[[ ");
        DPRINTF("vblank @%fHz avg", fps);
        DPRINTF(" - bql %"PRId64"ns/iter, %g%% time avg", lock_held_acc/num_frames, (double)lock_held_acc/(double)(delta_ms * 10000.0));
        DPRINTF(" ]]\n");
        lock_held_acc = 0;
        last_reported = now;
        num_frames = 0;
    }
}
#endif

/**
 * Renders the main interface. Usually called from the main thread,
 * but may sometimes be called from another thread.
 */
static void gl_render_frame(struct xemu_console *scon)
{
    static int frames = 0;
#if defined(__ANDROID__) || defined(ANDROID)
    frames++;
    /* Throttle state: when nv2a_get_framebuffer_surface returns 0 (no surface
     * at pcrtc.start yet), we limit polling to ~10Hz instead of 60Hz.  The
     * call acquires pfifo.lock, which at 60Hz starves the PFIFO thread that
     * processes Xbox GPU commands.  When a real frame IS available (last
     * returned non-zero) we skip the throttle and run at full rate. */
    static GLuint s_last_tex = 0;
    static int64_t s_last_miss_ms = 0;
    static int s_last_synced_frame_time = -1;
#endif
    static bool rendering;
    if (qatomic_xchg(&rendering, true) || qatomic_read(&qemu_exiting)) {
        return;
    }

#if defined(__ANDROID__) || defined(ANDROID)
    if (!xemu_android_surface_valid()) {
        qatomic_set(&rendering, false);
        return;
    }
#endif

    bool flip_required = false;
    bool release_surface_texture = false;
#if defined(__ANDROID__) || defined(ANDROID)
    bool acquired_surface = false;
#endif

#if defined(__ANDROID__) || defined(ANDROID)
    if (s_last_tex == 0) {
        int64_t now = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
        if (now - s_last_miss_ms < 100) {
            /* Too soon since last miss — skip this call to avoid pfifo.lock
             * contention.  framebuffer_in_use was never set, so no release
             * needed; just clear the reentrance guard and return. */
            qatomic_set(&rendering, false);
            return;
        }
    }
#endif

    GLuint tex;
#if defined(__ANDROID__) || defined(ANDROID)
    /* Only sync with the NV2A PGRAPH thread when the Xbox has produced a new
     * frame (frame_time advanced past what we last composited).  Re-use the
     * cached texture otherwise — this avoids 60 redundant pfifo.lock +
     * sync_complete round-trips per second when TCG is running at <1 fps. */
    int cur_frame_time = nv2a_get_frame_time();
    if (s_last_tex != 0 && cur_frame_time == s_last_synced_frame_time) {
        tex = s_last_tex;
    } else {
        tex = nv2a_get_framebuffer_surface();
        acquired_surface = true;
        s_last_tex = tex;
        if (tex != 0) {
            s_last_synced_frame_time = cur_frame_time;
            g_rendered_frame_count++;
        } else {
            s_last_miss_ms = qemu_clock_get_ms(QEMU_CLOCK_REALTIME);
        }
    }
#else
    tex = nv2a_get_framebuffer_surface();
#endif

#if !defined(__ANDROID__) && !defined(ANDROID)
    SDL_GL_MakeCurrent(scon->real_window, scon->winctx);
#else
    set_egl_current(true);
    
    EGLint surface_width = 0, surface_height = 0;
    eglQuerySurface(egl_display, egl_surface, EGL_WIDTH, &surface_width);
    eglQuerySurface(egl_display, egl_surface, EGL_HEIGHT, &surface_height);
    glViewport(0, 0, surface_width, surface_height);

    GLenum initial_err = glGetError();
    if (initial_err != GL_NO_ERROR) {
        ALOGE("gl_render_frame: Residual GL error after context acquisition: 0x%x", initial_err);
    }

    if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
        ALOGE("gl_render_frame: NO CURRENT CONTEXT after set_egl_current(true)!");
    }
#endif

#if !defined(__ANDROID__) && !defined(ANDROID)
    assert(glGetError() == GL_NO_ERROR);
#else
    {
        GLenum _err = glGetError();
        if (_err != GL_NO_ERROR) {
            ALOGE("gl_render_frame: residual GL error 0x%x (NV2A shared context)", _err);
        }
    }
#endif

    if (tex == 0) {
        if (!scon->surface) {
                goto skip_render;
        }
#if defined(__ANDROID__) || defined(ANDROID)
        /*
         * On Android the Xbox renders exclusively via NV2A (nv2a_get_framebuffer_surface).
         * Skip the QEMU software/placeholder surface path entirely — it uses qemu_memfd_alloc
         * which may produce an invalid pixman image on Android. Show black until NV2A renders.
         */
        goto skip_render;
#endif
        xemu_main_loop_lock();
        // FIXME: Don't upload if notdirty
        xb_surface_gl_create_texture(scon->surface);
        tex = scon->surface->texture;
        flip_required = true;
        release_surface_texture = true;
        xemu_main_loop_unlock();
    }

#if defined(__ANDROID__) || defined(ANDROID)
    /* Bind the EGL window surface before clearing/rendering. Must be done
     * AFTER the tex==0 early-exit check to avoid leaving FBO 0 bound for
     * the PGRAPH thread's glValidateProgram (which needs the NV2A FBO). */
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
#endif
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    GLenum post_clear_err = glGetError();
    if (post_clear_err != GL_NO_ERROR) {
        ALOGE("gl_render_frame: GL error after clear: 0x%x", post_clear_err);
    }
    xemu_snapshots_set_framebuffer_texture(tex, flip_required);
    xemu_hud_set_framebuffer_texture(tex, flip_required);

    /* FIXME: Finer locking. Event handlers in segments of the code expect
     * to be running on the main thread with the BQL. For now, acquire the
     * lock and perform rendering, but release before swap to avoid
     * possible lengthy blocking (for vsync).
     */
    if (xemu_is_main_thread()) {
        xemu_main_loop_lock();
        xemu_hud_update();
        xemu_main_loop_unlock();
        xemu_hud_render();
    }
#if defined(__ANDROID__) || defined(ANDROID)
    /* On Android, glFinish() can stall indefinitely when sampling from a
     * texture that was just rendered to on a different thread in the same
     * shared EGL context (driver synchronization issue on Mali/Adreno).
     * eglSwapBuffers handles the required flush, so glFinish() is not needed. */
    glFlush();
#else
    glFinish();
#endif

    if (release_surface_texture) {
        xemu_main_loop_lock();
        xb_surface_gl_destroy_texture(scon->surface);
        xemu_main_loop_unlock();
    }

#if !defined(__ANDROID__) && !defined(ANDROID)
    SDL_GL_SwapWindow(scon->real_window);
    assert(glGetError() == GL_NO_ERROR);
#else
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        ALOGE("gl_render_frame: GL error before swap: 0x%x (continuing)", err);
    }
    eglSwapBuffers(egl_display, egl_surface);
    set_egl_current(false);
#endif
skip_render:
#if defined(__ANDROID__) || defined(ANDROID)
    /* Release EGL context on all paths, including early-exit (goto skip_render). */
    set_egl_current(false);
#endif
#if defined(__ANDROID__) || defined(ANDROID)
    if (acquired_surface) {
        nv2a_release_framebuffer_surface();
    }
#else
    nv2a_release_framebuffer_surface();
#endif
    qatomic_set(&rendering, false);

#if DEBUG_XEMU_C
    report_stats();
#endif
}

static bool event_watch_callback(void *userdata, SDL_Event *event)
{
    struct xemu_console *scon = (struct xemu_console *)userdata;

    if (event->type == SDL_EVENT_WINDOW_EXPOSED ||
        event->type == SDL_EVENT_WINDOW_RESIZED) {
        gl_render_frame(scon);
    }

    return true; // Ignored
}

static void poll_events(struct xemu_console *scon)
{
    SDL_Event ev1, *ev = &ev1;
    bool allow_close = true;

    int kbd = 0, mouse = 0;
    xemu_hud_should_capture_kbd_mouse(&kbd, &mouse);

    while (SDL_PollEvent(ev)) {
        xemu_main_loop_lock();

        // HUD must process events first so that if a controller is detached,
        // a latent rebind request can cancel before the state is freed
        xemu_hud_process_sdl_events(ev);
        xemu_input_process_sdl_events(ev);

        switch (ev->type) {
        case SDL_EVENT_KEY_DOWN:
            if (kbd) break;
            handle_keydown(ev);
            break;
        case SDL_EVENT_KEY_UP:
            if (kbd) break;
            handle_keyup(ev);
            break;
        case SDL_EVENT_QUIT:
            if (scon->opts->has_window_close && !scon->opts->window_close) {
                allow_close = false;
            }
            if (allow_close) {
                shutdown_action = SHUTDOWN_ACTION_POWEROFF;
                qemu_system_shutdown_request(SHUTDOWN_CAUSE_HOST_UI);
            }
            break;
        case SDL_EVENT_MOUSE_MOTION:
            if (mouse) break;
            handle_mousemotion(ev);
            break;
        case SDL_EVENT_MOUSE_BUTTON_DOWN:
        case SDL_EVENT_MOUSE_BUTTON_UP:
            if (mouse) break;
            handle_mousebutton(ev);
            break;
        case SDL_EVENT_MOUSE_WHEEL:
            if (mouse) break;
            handle_mousewheel(ev);
            break;
        case SDL_EVENT_WINDOW_FIRST ... SDL_EVENT_WINDOW_LAST:
            handle_windowevent(ev);
            break;
        default:
            break;
        }

        xemu_main_loop_unlock();
    }

    xemu_main_loop_lock();
#if !defined(__ANDROID__) && !defined(ANDROID)
    xemu_input_update_controllers();
#endif
    xemu_main_loop_unlock();
}

static void display_very_early_init(DisplayOptions *o)
{
#if defined(__ANDROID__) || defined(ANDROID)
    ALOGI("display_very_early_init starting on Android...");
    ANativeWindow *window = xemu_android_get_window();
    if (!window) {
        ALOGE("Error: Android Native Window is NULL");
        return;
    }
    egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(egl_display, NULL, NULL);
    ALOGI("EGL Initialized. Choosing config...");

    EGLint attr[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config;
    EGLint num_configs;
    if (!eglChooseConfig(egl_display, attr, &config, 1, &num_configs) || num_configs == 0) {
        ALOGE("Error: eglChooseConfig failed or found no configs");
        return;
    }
    egl_config = config;
    ALOGI("eglChooseConfig success, found %d configs. Creating context...", num_configs);

    /* Try GLES 3.2 first (GL_CLAMP_TO_BORDER, glProgramUniform, etc.),
     * then fall back to 3.1 (glProgramUniform), then 3.0. */
    static const struct { EGLint major; EGLint minor; } gles_versions[] = {
        {3, 2}, {3, 1}, {3, 0}
    };
    for (int vi = 0; vi < 3 && egl_context == EGL_NO_CONTEXT; vi++) {
        EGLint ctx_attr[] = {
            EGL_CONTEXT_CLIENT_VERSION, gles_versions[vi].major,
            EGL_CONTEXT_MINOR_VERSION_KHR, gles_versions[vi].minor,
            EGL_NONE
        };
        egl_context = eglCreateContext(egl_display, config,
                                       EGL_NO_CONTEXT, ctx_attr);
        if (egl_context != EGL_NO_CONTEXT) {
            ALOGI("eglCreateContext: using GLES %d.%d",
                  gles_versions[vi].major, gles_versions[vi].minor);
        }
    }
    if (egl_context == EGL_NO_CONTEXT) {
        ALOGE("Error: eglCreateContext failed for all GLES versions");
        return;
    }

    static const EGLint srgb_attribs[] = {
        EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_SRGB_KHR,
        EGL_NONE
    };
    egl_surface = eglCreateWindowSurface(egl_display, config, window, srgb_attribs);
    if (egl_surface == EGL_NO_SURFACE) {
        ALOGI("eglCreateWindowSurface with sRGB colorspace failed (0x%x), retrying without",
              eglGetError());
        egl_surface = eglCreateWindowSurface(egl_display, config, window, NULL);
    }
    if (egl_surface == EGL_NO_SURFACE) {
        ALOGE("Error: eglCreateWindowSurface failed");
        return;
    }
    xemu_android_surface_mark_valid();
    ALOGI("eglCreateWindowSurface success. Making current...");

    set_egl_current(true);

    ALOGI("eglMakeCurrent success. Initializing SDL events and haptic...");
    ALOGI("SDL_Init skipped on Android for stability.");
    ALOGI("Android EGL and SDL initialized successfully.");

    // Initialize offscreen rendering context now
    nv2a_context_init();
    set_egl_current(false);
#else
#ifdef __linux__
    /* on Linux, SDL may use fbcon|directfb|svgalib when run without
     * accessible $DISPLAY to open X11 window.  This is often the case
     * when qemu is run using sudo.  But in this case, and when actually
     * run in X11 environment, SDL fights with X11 for the video card,
     * making current display unavailable, often until reboot.
     * So make x11 the default SDL video driver if this variable is unset.
     * This is a bit hackish but saves us from bigger problem.
     * Maybe it's a good idea to fix this in SDL instead.
     */
    setenv("SDL_VIDEODRIVER", "x11", 0);
#endif

    if (!SDL_Init(SDL_INIT_VIDEO)) {
        fprintf(stderr, "Failed to initialize SDL video subsystem: %s\n",
                SDL_GetError());
        exit(1);
    }

#ifdef SDL_HINT_VIDEO_X11_NET_WM_BYPASS_COMPOSITOR /* only available since SDL 2.0.8 */
    SDL_SetHint(SDL_HINT_VIDEO_X11_NET_WM_BYPASS_COMPOSITOR, "0");
#endif
    SDL_SetHint(SDL_HINT_VIDEO_MINIMIZE_ON_FOCUS_LOSS, "0");

    // Initialize rendering context
    SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_ALPHA_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 4);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_GL_SetAttribute(
        SDL_GL_CONTEXT_PROFILE_MASK,
        SDL_GL_CONTEXT_PROFILE_CORE);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);

    char *title = g_strdup_printf("xemu | v%s"
#ifdef XEMU_DEBUG_BUILD
                                  " Debug"
#endif
                                  , xemu_version);

    // Decide window size
    int min_window_width = 640;
    int min_window_height = 480;
    int window_width = min_window_width;
    int window_height = min_window_height;

    const int res_table[][2] = {
        {640,  480},
        {720,  480},
        {1280, 720},
        {1280, 800},
        {1280, 960},
        {1920, 1080},
        {2560, 1440},
        {2560, 1600},
        {2560, 1920},
        {3840, 2160}
    };

    if (g_config.display.window.startup_size == CONFIG_DISPLAY_WINDOW_STARTUP_SIZE_LAST_USED) {
        window_width  = g_config.display.window.last_width;
        window_height = g_config.display.window.last_height;
    } else {
        window_width  = res_table[g_config.display.window.startup_size-1][0];
        window_height = res_table[g_config.display.window.startup_size-1][1];
    }

    if (window_width < min_window_width) {
        window_width = min_window_width;
    }
    if (window_height < min_window_height) {
        window_height = min_window_height;
    }

    SDL_WindowFlags window_flags = (SDL_WindowFlags)(SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY);

    // Create main window
    m_window = SDL_CreateWindow(
        title, window_width, window_height,
        window_flags);
    if (m_window == NULL) {
        fprintf(stderr, "Failed to create main window: %s\n", SDL_GetError());
        SDL_Quit();
        exit(1);
    }
    g_free(title);
    SDL_SetWindowMinimumSize(m_window, min_window_width, min_window_height);

    const SDL_DisplayMode *disp_mode = SDL_GetCurrentDisplayMode(SDL_GetDisplayForWindow(m_window));
    if (disp_mode && (disp_mode->w < window_width || disp_mode->h < window_height)) {
        SDL_SetWindowSize(m_window, min_window_width, min_window_height);
        SDL_SetWindowPosition(m_window, SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED);
    }

    m_context = SDL_GL_CreateContext(m_window);

    if (m_context != NULL && epoxy_gl_version() < 40) {
        SDL_GL_MakeCurrent(NULL, NULL);
        SDL_GL_DestroyContext(m_context);
        m_context = NULL;
    }

    if (m_context == NULL) {
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR,
            "Unable to create OpenGL context",
            "Unable to create OpenGL context. This usually means the\r\n"
            "graphics device on this system does not support OpenGL 4.0.\r\n"
            "\r\n"
            "xemu cannot continue and will now exit.",
            m_window);
        SDL_DestroyWindow(m_window);
        SDL_Quit();
        exit(1);
    }

    int width, height, channels = 0;
    stbi_set_flip_vertically_on_load(0);
    unsigned char *icon_data = stbi_load_from_memory(xemu_64x64_data, xemu_64x64_size, &width, &height, &channels, 4);
    if (icon_data) {
        SDL_Surface *icon = SDL_CreateSurfaceFrom(width, height, SDL_PIXELFORMAT_RGBA32, icon_data, width*4);
        if (icon) {
            SDL_SetWindowIcon(m_window, icon);
        }
        // Note: Retaining the memory allocated by stbi_load. It's used in place
        // by the SDL surface.
    }

    fprintf(stderr, "CPU: %s\n", xemu_get_cpu_info());
    fprintf(stderr, "OS_Version: %s\n", xemu_get_os_info());
    fprintf(stderr, "GL_VENDOR: %s\n", glGetString(GL_VENDOR));
    fprintf(stderr, "GL_RENDERER: %s\n", glGetString(GL_RENDERER));
    fprintf(stderr, "GL_VERSION: %s\n", glGetString(GL_VERSION));
    fprintf(stderr, "GL_SHADING_LANGUAGE_VERSION: %s\n", glGetString(GL_SHADING_LANGUAGE_VERSION));

    // Initialize offscreen rendering context now
    nv2a_context_init();
    SDL_GL_MakeCurrent(NULL, NULL);
#endif
}

static void display_early_init(DisplayOptions *o)
{
    ALOGI("display_early_init starting...");
    assert(o->type == DISPLAY_TYPE_XEMU);
    display_opengl = 1;

#if !defined(__ANDROID__) && !defined(ANDROID)
    SDL_GL_MakeCurrent(m_window, m_context);
    SDL_GL_SetSwapInterval(g_config.display.window.vsync ? 1 : 0);
    xemu_hud_init(m_window, m_context);
#else
    ALOGI("display_early_init: Making EGL context current...");
    set_egl_current(true);
    ALOGI("display_early_init: Initializing HUD...");
    if (xemu_is_main_thread()) {
        xemu_hud_init(NULL, NULL);
    }
    set_egl_current(false);
#endif
    ALOGI("display_early_init finished.");
}

static const DisplayChangeListenerOps dcl_gl_ops = {
    .dpy_name                = "xemu-gl",
    .dpy_gfx_switch          = gl_switch,
    .dpy_gfx_check_format    = xb_console_gl_check_format,
    .dpy_mouse_set           = mouse_warp,
    .dpy_cursor_define       = mouse_define,
};

static void display_init(DisplayState *ds, DisplayOptions *o)
{
    ALOGI("display_init starting...");
    uint8_t data = 0;
    int i;

    assert(o->type == DISPLAY_TYPE_XEMU);
#if !defined(__ANDROID__) && !defined(ANDROID)
    SDL_GL_MakeCurrent(m_window, m_context);
#else
    ALOGI("display_init: Making EGL context current...");
    set_egl_current(true);
#endif

    gui_fullscreen = o->has_full_screen && o->full_screen;
    gui_fullscreen |= g_config.display.window.fullscreen_on_startup;

    ALOGI("display_init: Initializing consoles...");
    num_outputs = 1;
    scon_list = g_new0(struct xemu_console, num_outputs);
    for (i = 0; i < num_outputs; i++) {
        ALOGI("display_init: Initializing console %d...", i);
        QemuConsole *con = qemu_console_lookup_by_index(i);
        assert(con != NULL);
        if (!qemu_console_is_graphic(con) &&
            qemu_console_get_index(con) != 0) {
            scon_list[i].hidden = true;
        }
        scon_list[i].idx = i;
        scon_list[i].opts = o;
        scon_list[i].dcl.ops = &dcl_gl_ops;
        scon_list[i].dcl.con = con;
        scon_list[i].kbd = qkbd_state_init(con);
        register_displaychangelistener(&scon_list[i].dcl);

#if !defined(__ANDROID__) && !defined(ANDROID)
#if defined(SDL_VIDEO_DRIVER_WINDOWS)
        HWND hwnd = (HWND)SDL_GetPointerProperty(SDL_GetWindowProperties(scon_list[i].real_window), SDL_PROP_WINDOW_WIN32_HWND_POINTER, NULL);
        if (hwnd) {
            qemu_console_set_window_id(con, (uintptr_t)hwnd);
        }
#elif defined(SDL_VIDEO_DRIVER_X11)
        Window xwindow = (Window)SDL_GetNumberProperty(SDL_GetWindowProperties(scon_list[i].real_window), SDL_PROP_WINDOW_X11_WINDOW_NUMBER, 0);
        if (xwindow) {
            qemu_console_set_window_id(con, xwindow);
        }
#endif
#endif
    }

#if !defined(__ANDROID__) && !defined(ANDROID)
    scon_list[0].real_window = m_window;
    scon_list[0].winctx = m_context;
#endif

    mouse_mode_notifier.notify = mouse_mode_change;
    qemu_add_mouse_mode_change_notifier(&mouse_mode_notifier);

#if !defined(__ANDROID__) && !defined(ANDROID)
    sdl_cursor_hidden = SDL_CreateCursor(&data, &data, 8, 1, 0, 0);
    sdl_cursor_normal = SDL_GetCursor();

    // SDL_PollEvent may block during main window resize or drag operations.
    // Register event watch to handle rendering during these operations.
    SDL_AddEventWatch(event_watch_callback, &scon_list[0]);
#endif

    if (use_vblank_timer_thread) {
        qemu_thread_create(&vblank_thread, "vblank-timer", vblank_timer_thread,
                           &scon_list[0], QEMU_THREAD_JOINABLE);
    } else {
        vblank_timer = timer_new_ns(QEMU_CLOCK_REALTIME, vblank_timer_callback, &scon_list[0]);
        timer_mod_ns(vblank_timer, qemu_clock_get_ns(QEMU_CLOCK_REALTIME) + vblank_interval_ns);
    }

    /* Tell main thread to go ahead and create the app and enter the run loop */
    ALOGI("display_init: Done. Releasing context and posting sem...");
#if !defined(__ANDROID__) && !defined(ANDROID)
    SDL_GL_MakeCurrent(NULL, NULL);
#else
    set_egl_current(false);
#endif
    qemu_sem_post(&display_init_sem);
}

static void display_finalize(void)
{
    if (use_vblank_timer_thread) {
        qemu_thread_join(&vblank_thread);
    }

#if !defined(__ANDROID__) && !defined(ANDROID)
    SDL_RemoveEventWatch(event_watch_callback, &scon_list[0]);
    SDL_GL_MakeCurrent(NULL, NULL);
    SDL_GL_DestroyContext(m_context);
    SDL_DestroyWindow(m_window);
    SDL_Quit();
#else
    set_egl_current(false);
    eglDestroySurface(egl_display, egl_surface);
    eglDestroyContext(egl_display, egl_context);
    eglTerminate(egl_display);
#endif
}

static QemuDisplay qemu_display_xemu = {
    .type       = DISPLAY_TYPE_XEMU,
    .early_init = display_early_init,
    .init       = display_init,
};

static void register_xemu_display(void)
{
#if defined(__ANDROID__) || defined(ANDROID)
    ALOGI("register_xemu_display: DISPLAY_TYPE_XEMU=%d, qemu_display_xemu.type=%d",
          (int)DISPLAY_TYPE_XEMU, (int)qemu_display_xemu.type);
#endif
    qemu_display_register(&qemu_display_xemu);
}

type_init(register_xemu_display);

int gArgc;
char **gArgv;

static void *qemu_main(void *opaque)
{
#if defined(__ANDROID__) || defined(ANDROID)
    pthread_setname_np(pthread_self(), "qemu_main");
    GMainContext *ctx = g_main_context_new();
    g_main_context_push_thread_default(ctx);
#endif
    ALOGI("qemu_main thread starting (thread %ld)...", (long)gettid());
    qemu_init(gArgc, gArgv);
    ALOGI("qemu_main: qemu_init returned. Starting main loop...");
    exit_status = qemu_main_loop();
    ALOGI("qemu_main: main loop returned status %d", exit_status);
    qatomic_set(&qemu_exiting, true);
    bql_unlock();
    qemu_mutex_unlock_main_loop();

    ALOGI("qemu_main: waiting for display_shutdown_sem...");
    qemu_sem_wait(&display_shutdown_sem);
    ALOGI("qemu_main: performing cleanup...");
    bql_lock();
    qemu_cleanup(exit_status);
    bql_unlock();
    ALOGI("qemu_main thread finished.");

    return NULL;
}

#ifdef _WIN32
static const wchar_t *get_executable_name(void)
{
    static wchar_t exe_name[MAX_PATH] = { 0 };
    static bool initialized = false;

    if (!initialized) {
        wchar_t full_path[MAX_PATH];
        DWORD length = GetModuleFileNameW(NULL, full_path, MAX_PATH);
        if (length == 0 || length == MAX_PATH) {
            return NULL;
        }

        wchar_t *last_slash = wcsrchr(full_path, L'\\');
        if (last_slash) {
            wcsncpy_s(exe_name, MAX_PATH, last_slash + 1, _TRUNCATE);
        } else {
            wcsncpy_s(exe_name, MAX_PATH, full_path, _TRUNCATE);
        }

        initialized = true;
    }

    return exe_name;
}

static void setup_nvidia_profile(void)
{
    const wchar_t *exe_name = get_executable_name();
    if (exe_name == NULL) {
        fprintf(stderr, "Failed to get current executable name\n");
        return;
    }

    if (nvapi_init()) {
        nvapi_setup_profile((NvApiProfileOpts){
            .profile_name = L"xemu",
            .executable_name = exe_name,
            .threaded_optimization = false,
        });
        nvapi_finalize();
    }
}
#endif

static void init_sdl_app_metadata(void)
{
    SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_NAME_STRING, "xemu");
    SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_VERSION_STRING,
                               xemu_version);
    SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_IDENTIFIER_STRING,
                               "app.xemu.xemu");
    SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_URL_STRING,
                               "https://xemu.app");
}

int xemu_core_main(int argc, char **argv)
{
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    ALOGI("xemu_core_main started (thread %ld), argc=%d", (long)gettid(), argc);
    for (int i = 0; i < argc; i++) {
        ALOGI("  argv[%d] = %s", i, argv[i]);
    }
    QemuThread thread;

#if !defined(__ANDROID__) && !defined(ANDROID)
    setlocale(LC_NUMERIC, "C");
#endif

#ifdef _WIN32
    if (AttachConsole(ATTACH_PARENT_PROCESS)) {
        // Launched with a console. If stdout and stderr are not associated with
        // an output stream, redirect to parent console.
        if (_fileno(stdout) == -2) {
            freopen("CONOUT$", "w+", stdout);
        }
        if (_fileno(stderr) == -2) {
            freopen("CONOUT$", "w+", stderr);
        }
    } else {
        // Launched without a console. Redirect stdout and stderr to a log file.
        HANDLE logfile = CreateFileA("xemu.log",
            GENERIC_WRITE, FILE_SHARE_WRITE|FILE_SHARE_READ,
            NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (logfile != INVALID_HANDLE_VALUE) {
            freopen("xemu.log", "a", stdout);
            freopen("xemu.log", "a", stderr);
        }
    }

    _set_error_mode(_OUT_TO_STDERR);
#endif

    fprintf(stderr, "xemu_version: %s\n", xemu_version);
    fprintf(stderr, "xemu_commit: %s\n", xemu_commit);
    fprintf(stderr, "xemu_date: %s\n", xemu_date);

#if !defined(__ANDROID__) && !defined(ANDROID)
    init_sdl_app_metadata();
#endif

    for (int i = 1; i < argc; i++) {
        if (argv[i] && strcmp(argv[i], "-config_path") == 0) {
            argv[i] = NULL;
            if (i < argc - 1 && argv[i+1]) {
                xemu_settings_set_path(argv[i+1]);
                argv[i+1] = NULL;
            }
            break;
        }
    }

    // Create a copy of argv for QEMU without nulled arguments
    int q_argc = 0;
    char **q_argv = malloc(sizeof(char *) * (argc + 16)); // Extra space for Android args
    for (int j = 0; j < argc; j++) {
        if (argv[j] != NULL) {
            q_argv[q_argc++] = argv[j];
        }
    }

    gArgc = q_argc;
    gArgv = q_argv;

    if (!xemu_settings_load()) {
        const char *err_msg = xemu_settings_get_error_message();
        ALOGE("Failed to load xemu config file: %s", err_msg);
#if !defined(__ANDROID__) && !defined(ANDROID)
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR,
            "Failed to load xemu config file", err_msg,
            m_window);
#endif
        exit(1);
    }

#if defined(__ANDROID__) || defined(ANDROID)
    ALOGI("Checking Android args (pointer: %p)", (void*)g_android_args);
    if (g_android_args) {
        ALOGI("Applying Android file overrides...");
        if (g_android_args->mcpxPath) {
            ALOGI("  MCPX: %s", g_android_args->mcpxPath);
            g_config.sys.files.bootrom_path = strdup(g_android_args->mcpxPath);
        }
        if (g_android_args->biosPath) {
            ALOGI("  Flash: %s", g_android_args->biosPath);
            g_config.sys.files.flashrom_path = strdup(g_android_args->biosPath);
        }
        if (g_android_args->hddPath) {
            ALOGI("  HDD: %s", g_android_args->hddPath);
            g_config.sys.files.hdd_path = strdup(g_android_args->hddPath);
        }
        if (g_android_args->isoPath) {
            ALOGI("  DVD: %s", g_android_args->isoPath);
            xemu_settings_set_string(&g_config.sys.files.dvd_path,
                                     g_android_args->isoPath);
        }
        g_config.general.show_welcome = false;

        if (g_android_args->renderer) {
            if (strcmp(g_android_args->renderer, "VULKAN") == 0) {
                g_config.display.renderer = CONFIG_DISPLAY_RENDERER_VULKAN;
                ALOGI("Renderer override: VULKAN");
            } else if (strcmp(g_android_args->renderer, "OPENGL") == 0) {
                g_config.display.renderer = CONFIG_DISPLAY_RENDERER_OPENGL;
                ALOGI("Renderer override: OPENGL");
            }
        }

        ALOGI("Android file overrides applied successfully. show_welcome set to false.");
    }
#endif

#if !defined(__ANDROID__) && !defined(ANDROID)
    /* On Android we never register the atexit save: the dvd_path is an
     * ephemeral /proc/self/fd/N that becomes invalid in any future session,
     * and writing it via atexit() while QEMU threads are still live corrupts
     * the config file.  _exit() is used for clean emulation exit anyway. */
    atexit(xemu_settings_save);
#endif

#ifdef _WIN32
    if (g_config.display.setup_nvidia_profile) {
        setup_nvidia_profile();
    }
#endif

    display_very_early_init(NULL);
    ALOGI("STEP: display_very_early_init returned");
    ALOGI("display_very_early_init finished. Initializing semaphores and threads...");

#if defined(__ANDROID__) || defined(ANDROID)
    // Explicitly register the xemu display before qemu_init() runs.
    // This ensures dpys[DISPLAY_TYPE_XEMU] is populated even if the
    // type_init constructor mechanism doesn't fire in time on Android.
    ALOGI("Explicitly registering xemu display (Android path)...");
    qemu_display_register(&qemu_display_xemu);
    ALOGI("xemu display registered.");
#endif

    qemu_sem_init(&display_init_sem, 0);
    qemu_sem_init(&display_shutdown_sem, 0);
    ALOGI("Creating qemu_main thread...");
    qemu_thread_create(&thread, "qemu_main", qemu_main,
                       NULL, QEMU_THREAD_JOINABLE);
    ALOGI("qemu_main thread created. Waiting for display_init_sem...");
    qemu_sem_wait(&display_init_sem);
    ALOGI("display_init_sem posted. Continuing xemu_core_main...");

    gui_grab = 0;
    if (gui_fullscreen) {
        grab_start(0);
        set_full_screen(&scon_list[0], gui_fullscreen);
    }

    tcg_register_init_ctx();
    qemu_set_current_aio_context(qemu_get_aio_context());

    xemu_main_loop_lock();
    if (xemu_is_main_thread()) {
        xemu_input_init();
    }
    xemu_main_loop_unlock();

    ALOGI("Initialization complete. Entering main render loop...");
    struct xemu_console *scon = &scon_list[0];
    int frames = 0;
    while (!qatomic_read(&qemu_exiting)) {
#if defined(__ANDROID__) || defined(ANDROID)
        /* When the surface is gone (app backgrounded), block instead of
         * spinning at 60fps doing nothing.  Wakes immediately when the
         * surface is recreated (app returns to foreground). */
        if (!xemu_android_surface_valid()) {
            xemu_android_wait_for_surface();
            continue;
        }
#endif
#if !defined(__ANDROID__) && !defined(ANDROID)
        if (xemu_is_main_thread()) {
            poll_events(scon);
        }
#endif
        gl_render_frame(scon);
        frames++;
#if defined(__ANDROID__) || defined(ANDROID)
        g_usleep(16000); // Throttle to ~60fps
#endif
    }
    ALOGI("Main loop exited. Shutting down...");
    qemu_sem_post(&display_shutdown_sem);
    qemu_thread_join(&thread);
    display_finalize();
    return exit_status;
}

#if !defined(__ANDROID__) && !defined(ANDROID)
int main(int argc, char **argv)
{
    return xemu_core_main(argc, argv);
}
#endif

void xemu_eject_disc(Error **errp)
{
    Error *error = NULL;

    xbox_smc_eject_button();
    xemu_settings_set_string(&g_config.sys.files.dvd_path, "");

    // Xbox software may request that the drive open, but do it now anyway
    qmp_eject("ide0-cd1", NULL, true, false, &error);
    if (error) {
        error_propagate(errp, error);
    }

    xbox_smc_update_tray_state();
}

void xemu_load_disc(const char *path, Error **errp)
{
    Error *error = NULL;

    // Ensure an eject sequence is always triggered so Xbox software reloads
    xbox_smc_eject_button();
    xemu_settings_set_string(&g_config.sys.files.dvd_path, "");

    qmp_blockdev_change_medium("ide0-cd1", NULL, path, "raw", false, false,
                               false, 0, &error);
    if (error) {
        error_propagate(errp, error);
    } else {
        xemu_settings_set_string(&g_config.sys.files.dvd_path, path);
    }

    xbox_smc_update_tray_state();
}

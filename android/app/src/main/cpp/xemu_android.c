#include "qemu/osdep.h"
#include "xemu_android.h"
#include <sys/system_properties.h>
#ifdef HAVE_ADRENOTOOLS
#include <adrenotools/driver.h>
#include <dlfcn.h>
#endif
#include "ui/xemu-input.h"
#include <pthread.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <vulkan/vulkan.h>

#define LOG_TAG "xemu-android"
#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); \
} while(0)
#define LOGE(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); \
} while(0)

extern int xemu_core_main(int argc, char **argv);

EGLDisplay egl_display = EGL_NO_DISPLAY;
EGLContext egl_context = EGL_NO_CONTEXT;
EGLSurface egl_surface = EGL_NO_SURFACE;
EGLConfig  egl_config  = NULL;
pid_t xemu_main_thread_id = 0;

static _Thread_local bool t_egl_current = false;
static ANativeWindow *native_window = NULL;

/* Surface lifecycle synchronisation.
 *
 * g_surface_valid  — true when egl_surface is created and safe to use.
 * g_render_has_ctx — true while the render thread has egl_surface current
 *                    (between set_egl_current(true) and set_egl_current(false)).
 *
 * xemu_android_surface_destroyed() sets g_surface_valid=false and waits on
 * g_surface_cond until the render thread releases the context, then it is safe
 * to call eglDestroySurface.  xemu_android_surface_created() creates a new
 * egl_surface and sets g_surface_valid=true.
 */
static pthread_mutex_t g_surface_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  g_surface_cond  = PTHREAD_COND_INITIALIZER;
static bool g_surface_valid    = false;
static bool g_render_has_ctx   = false;

bool xemu_android_surface_valid(void) {
    pthread_mutex_lock(&g_surface_mutex);
    bool v = g_surface_valid;
    pthread_mutex_unlock(&g_surface_mutex);
    return v;
}

void xemu_android_wait_for_surface(void) {
    struct timespec ts;
    pthread_mutex_lock(&g_surface_mutex);
    while (!g_surface_valid) {
        /* Use a 1-second timeout so the caller can re-check qemu_exiting
         * and other exit conditions without blocking indefinitely. */
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 1;
        pthread_cond_timedwait(&g_surface_cond, &g_surface_mutex, &ts);
    }
    pthread_mutex_unlock(&g_surface_mutex);
}

/* Set to true once QEMU has initialized the BQL and the VM is running.
 * Guards xemu_android_vm_pause/resume against being called too early
 * (onResume() fires before startEmulation() on first launch). */
static volatile bool g_qemu_initialized = false;

/* Called from display_very_early_init() after the initial egl_surface is
 * created, to mark the surface as usable by the render thread.
 * By this point qemu_init() has run and the BQL is initialized. */
void xemu_android_surface_mark_valid(void) {
    g_qemu_initialized = true;
    pthread_mutex_lock(&g_surface_mutex);
    g_surface_valid = true;
    pthread_cond_broadcast(&g_surface_cond);
    pthread_mutex_unlock(&g_surface_mutex);
}

bool xemu_android_qemu_initialized(void) {
    return g_qemu_initialized;
}

/* Called only from the xemu_core render thread to acquire/release the main
 * EGL display context (egl_context + egl_surface).  NV2A PGRAPH/PFIFO threads
 * have their own independent EGL contexts via glo_context_create(). */
void set_egl_current(bool current) {
    if (current == t_egl_current) return;
    if (current) {
        pthread_mutex_lock(&g_surface_mutex);
        bool ok = g_surface_valid && egl_surface != EGL_NO_SURFACE;
        pthread_mutex_unlock(&g_surface_mutex);
        if (!ok) return;
        if (eglMakeCurrent(egl_display, egl_surface, egl_surface, egl_context)) {
            t_egl_current = true;
            pthread_mutex_lock(&g_surface_mutex);
            g_render_has_ctx = true;
            pthread_mutex_unlock(&g_surface_mutex);
        } else {
            LOGE("set_egl_current: eglMakeCurrent failed: 0x%x", eglGetError());
        }
    } else {
        eglMakeCurrent(egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        t_egl_current = false;
        pthread_mutex_lock(&g_surface_mutex);
        g_render_has_ctx = false;
        pthread_cond_broadcast(&g_surface_cond);
        pthread_mutex_unlock(&g_surface_mutex);
    }
}

/* Called from the Java main thread when Android destroys the SurfaceView
 * (e.g. app goes to background, screen rotation).  Waits for the render
 * thread to release egl_surface, then destroys it.  egl_context and
 * egl_display are preserved across surface recreations. */
void xemu_android_surface_destroyed(void) {
    LOGI("surface_destroyed: waiting for render thread to release context...");
    pthread_mutex_lock(&g_surface_mutex);
    g_surface_valid = false;
    while (g_render_has_ctx) {
        pthread_cond_wait(&g_surface_cond, &g_surface_mutex);
    }
    pthread_mutex_unlock(&g_surface_mutex);

    LOGI("surface_destroyed: context released, destroying EGL surface");
    if (egl_surface != EGL_NO_SURFACE) {
        eglDestroySurface(egl_display, egl_surface);
        egl_surface = EGL_NO_SURFACE;
    }
    if (native_window) {
        ANativeWindow_release(native_window);
        native_window = NULL;
    }
}

/* Called from the Java main thread when Android provides a new SurfaceView
 * (after the app returns to foreground or after rotation).  Creates a new
 * egl_surface from the new ANativeWindow and marks it valid. */
void xemu_android_surface_created(ANativeWindow *window) {
    LOGI("surface_created: creating new EGL surface");
    native_window = window;
    static const EGLint srgb_attribs[] = {
        EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_SRGB_KHR,
        EGL_NONE
    };
    egl_surface = eglCreateWindowSurface(egl_display, egl_config, window, srgb_attribs);
    if (egl_surface == EGL_NO_SURFACE) {
        LOGI("surface_created: sRGB surface failed (0x%x), retrying without", eglGetError());
        egl_surface = eglCreateWindowSurface(egl_display, egl_config, window, NULL);
    }
    if (egl_surface == EGL_NO_SURFACE) {
        LOGE("surface_created: eglCreateWindowSurface failed: 0x%x", eglGetError());
        return;
    }
    pthread_mutex_lock(&g_surface_mutex);
    g_surface_valid = true;
    pthread_cond_broadcast(&g_surface_cond);
    pthread_mutex_unlock(&g_surface_mutex);
    LOGI("surface_created: EGL surface ready");
}

static void signal_handler(int sig) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "CRITICAL: Captured signal %d on thread %ld", sig, (long)gettid());
    exit(sig);
}

/* SIGABRT handler: logs to logcat then re-raises so debuggerd gets a backtrace */
static void sigabrt_handler(int sig) {
    (void)sig;
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
        "SIGABRT on tid %ld — check xemu-stdout tag above for abort message",
        (long)gettid());
    /* Reset to default and re-raise so Android's crash reporter fires */
    signal(SIGABRT, SIG_DFL);
    raise(SIGABRT);
}

static pthread_t xemu_thread;
static pthread_t log_thread;
static int log_pipe[2];
ThreadArgs *g_android_args = NULL;

/* Pointer to the Android virtual ControllerState — set once by
 * xemu_android_input_init(), then written by JNI input callbacks. */
static ControllerState *g_android_controller = NULL;

static void *logging_thread(void *p) {
    char buffer[1024];
    ssize_t n;
    while ((n = read(log_pipe[0], buffer, sizeof(buffer) - 1)) > 0) {
        buffer[n] = '\0';
        __android_log_write(ANDROID_LOG_INFO, "xemu-stdout", buffer);
    }
    return NULL;
}

static void setup_logging(void) {
    signal(SIGPIPE, SIG_IGN);
    /* Install SIGABRT handler to log the crash to logcat before re-raising.
     * Other signals are left default so Android's crash reporter provides backtraces. */
    signal(SIGABRT, sigabrt_handler);

    pipe(log_pipe);
    dup2(log_pipe[1], STDOUT_FILENO);
    dup2(log_pipe[1], STDERR_FILENO);
    /* Make stdout/stderr unbuffered so assert()/abort() messages reach the
     * logging thread before the process crashes. */
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    pthread_create(&log_thread, NULL, logging_thread, NULL);
}

void xemu_android_input_init(void) {
    LOGI("Initializing Android virtual controller...");
    ControllerState *state = g_new0(ControllerState, 1);
    state->type = INPUT_DEVICE_ANDROID;
    state->name = "Android Virtual Controller";
    g_android_controller = state;
    xemu_input_bind(0, state, false);
}

void xemu_android_set_button(uint32_t mask, int pressed) {
    if (!g_android_controller) return;
    if (pressed) {
        g_android_controller->buttons |= (uint16_t)mask;
    } else {
        g_android_controller->buttons &= (uint16_t)~mask;
    }
}

void xemu_android_set_axis(int axis_index, int16_t value) {
    if (!g_android_controller) return;
    if (axis_index >= 0 && axis_index < CONTROLLER_AXIS__COUNT) {
        g_android_controller->axis[axis_index] = value;
    }
}

void xemu_android_request_exit(void) {
    LOGI("Exit requested — terminating emulation process.");
    /* Stop the VM and flush all block devices so the qcow2 HDD image is in
     * a clean state on the next open.  Without this, _exit() leaves the
     * qcow2 dirty bit set and any pending FATX metadata writes are lost,
     * which causes "Unable to create new player profile" on the next session.
     *
     * We use _exit() (not exit()) after the flush: exit() would run atexit()
     * handlers (including xemu_settings_save) while QEMU threads are still
     * live, corrupting the config file (e.g. dvd_path=/proc/self/fd/N). */
    xemu_android_flush_block_devices();
    LOGI("Block devices flushed — exiting.");
    _exit(0);
}

static long get_file_size(const char *filename) {
    FILE *fp = fopen(filename, "rb");
    if (!fp) return -1;
    fseek(fp, 0L, SEEK_END);
    long size = ftell(fp);
    fclose(fp);
    return size;
}

/* Pin this thread to the highest-frequency CPU cores.
 * Reads /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq for each CPU,
 * builds a bitmask of cores at >= 80% of peak frequency, and calls
 * sched_setaffinity via raw syscall (Bionic does not expose cpu_set_t).
 * Silently falls back to all-cores if anything fails. */

/*
 * Jump-cache invalidation strategy, switchable for A/B measurement.
 *
 * true  (default, and what this port ships): clear only the entries that
 *       reference the invalidated TB.
 * false: upstream QEMU's behaviour, flush the whole jump cache.
 *
 * Set with `adb shell setprop debug.xemu.jc_targeted 0|1` before launch.
 * The change was originally measured only through frame rate, which we now
 * know could not resolve it; this exists so it can be measured properly.
 */
bool g_jc_targeted = true;

static void jc_read_property(void);

/* Re-read before each benchmark so configurations can be alternated inside a
 * single process.  Running them as separate blocks lets the device heat up
 * under the second one: a full-flush block drifted 38.75 -> 39.70 ms/frame
 * across four runs purely from thermals, which is the same size as the effect
 * being measured.  Interleaving cancels that. */
void jc_refresh_property(void);
void jc_refresh_property(void)
{
    jc_read_property();
}

static void jc_read_property(void)
{
    char v[PROP_VALUE_MAX] = { 0 };

    if (__system_property_get("debug.xemu.jc_targeted", v) > 0 &&
        (v[0] == '0' || v[0] == 'n' || v[0] == 'f')) {
        g_jc_targeted = false;
    }
    LOGI("jump-cache invalidation: %s",
         g_jc_targeted ? "targeted (clear matching entries)"
                       : "full flush (upstream behaviour)");
}

void pin_to_big_cores(void) {
    int ncpus = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (ncpus <= 0 || ncpus > 64) return;

    long freqs[64] = {0};
    int valid = 0;
    long max_freq = 0;
    for (int i = 0; i < ncpus; i++) {
        char path[128];
        snprintf(path, sizeof(path),
            "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        if (fscanf(f, "%ld", &freqs[i]) == 1) {
            valid++;
            if (freqs[i] > max_freq) max_freq = freqs[i];
        }
        fclose(f);
    }

    if (valid == 0 || max_freq == 0) return;

    /* Use cores with at least 80% of the maximum frequency */
    long threshold = max_freq * 80 / 100;
    unsigned long mask = 0;
    int big_count = 0;
    for (int i = 0; i < ncpus; i++) {
        if (freqs[i] >= threshold) {
            mask |= (1UL << i);
            big_count++;
        }
    }

    if (big_count == 0) return;

    /* Bionic doesn't expose cpu_set_t/CPU_SET — use the raw syscall with a
     * plain unsigned long bitmask (works for up to 64 CPUs on ARM64). */
    if (syscall(__NR_sched_setaffinity, 0, sizeof(mask), &mask) == 0) {
        LOGI("Pinned xemu_core to %d big core(s) (max_freq=%ldkHz threshold=%ldkHz)",
             big_count, max_freq, threshold);
    } else {
        LOGI("sched_setaffinity failed (errno=%d) — running on all cores", errno);
    }
}

static void *xemu_android_thread(void *opaque) {
    xemu_main_thread_id = gettid();
    pthread_setname_np(pthread_self(), "xemu_core");

    /* Boost priority — valid range is -20 (highest) to 19 (lowest).
     * -10 gives the emulator thread priority over most system threads
     * without requiring CAP_SYS_NICE (Android allows lowering nice value
     * slightly below 0 for foreground processes). */
    if (setpriority(PRIO_PROCESS, 0, -10) != 0) {
        LOGI("setpriority failed (errno=%d) — running at default priority", errno);
    }

    pin_to_big_cores();
    jc_read_property();

    if (g_android_args->mcpxPath) {
        LOGI("Bootrom size: %ld", get_file_size(g_android_args->mcpxPath));
    }

    /* MTTCG (thread=multi): allows I/O device threads to run in parallel
     * with the vCPU thread, significantly improving emulation throughput.
     * tb-size=256: 256 MB translation block cache (up from default 32 MB)
     * reduces recompilation overhead for games with large working sets. */
    char **argv = malloc(sizeof(char *) * 8);
    argv[0] = strdup("xemu");
    argv[1] = strdup("-config_path");
    argv[2] = strdup(g_android_args->configPath);
    argv[3] = strdup("-audio");
    argv[4] = strdup("driver=aaudio");
    argv[5] = strdup("-accel");
    argv[6] = strdup("tcg,thread=multi,tb-size=256");
    argv[7] = NULL;
    int argc = 7;

    /* Register the AAudio driver before QEMU initialises the audio subsystem */
    extern void aaudio_register_driver(void);
    aaudio_register_driver();

    LOGI("Starting xemu core thread %ld...", (long)gettid());

    int status = xemu_core_main(argc, argv);
    LOGI("xemu core exited with status: %d", status);

    return NULL;
}

void xemu_android_start(
    const char *configPath,
    const char *mcpxPath,
    const char *biosPath,
    const char *hddPath,
    const char *isoPath,
    const char *renderer,
    const char *hookLibDir,
    const char *driverDir,
    const char *driverName,
    ANativeWindow *window) {

    if (native_window != NULL) return;
    native_window = window;

    setup_logging();

    g_android_args = malloc(sizeof(ThreadArgs));
    g_android_args->configPath = strdup(configPath);
    g_android_args->mcpxPath = strdup(mcpxPath);
    g_android_args->biosPath = strdup(biosPath);
    g_android_args->hddPath = strdup(hddPath);
    g_android_args->isoPath = (isoPath && isoPath[0]) ? strdup(isoPath) : NULL;
    g_android_args->renderer = (renderer && renderer[0]) ? strdup(renderer) : NULL;
    g_android_args->hookLibDir = (hookLibDir && hookLibDir[0]) ? strdup(hookLibDir) : NULL;
    g_android_args->driverDir  = (driverDir && driverDir[0]) ? strdup(driverDir) : NULL;
    g_android_args->driverName = (driverName && driverName[0]) ? strdup(driverName) : NULL;
    LOGI("g_android_args initialized at %p", (void*)g_android_args);

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8 * 1024 * 1024); // 8MB
    pthread_create(&xemu_thread, &attr, xemu_android_thread, NULL);
    pthread_attr_destroy(&attr);
}

void xemu_android_stop(void) {
    LOGI("Stop signal received.");
}

ANativeWindow *xemu_android_get_window(void) {
    return native_window;
}

/*
 * Returns a custom vkGetInstanceProcAddr loaded via libadrenotools (Mesa
 * Turnip or other custom Adreno driver), or NULL to use the system Vulkan
 * loader. Called by the Vulkan renderer in instance.c before volkInitialize().
 *
 * When g_android_args->driverDir and driverName are set, attempts to load the
 * custom driver via adrenotools_open_libvulkan(). Falls back to the system
 * loader (returns NULL) on any failure.
 */
extern int g_shaders_compiled_count;
int xemu_android_get_compiled_shader_count(void) {
    return g_shaders_compiled_count;
}

/* Internal resolution scale — stored here, applied in gl_render_frame (ui/xemu.c)
 * on the first frame after QEMU/NV2A is fully initialized. */
static volatile unsigned int g_surface_scale = 1;

/* Rumble state written by xemu_input_update_rumble() in ui/xemu-input.c. */
extern volatile uint16_t g_android_rumble_l;
extern volatile uint16_t g_android_rumble_r;

void xemu_android_get_rumble(uint16_t *left, uint16_t *right)
{
    *left  = g_android_rumble_l;
    *right = g_android_rumble_r;
}

void xemu_android_set_surface_scale(unsigned int scale) {
    g_surface_scale = (scale >= 1 && scale <= 4) ? scale : 1;
}

unsigned int xemu_android_get_surface_scale(void) {
    return g_surface_scale;
}

PFN_vkGetInstanceProcAddr xemu_android_get_vk_proc_addr(void) {
#ifdef HAVE_ADRENOTOOLS
    if (!g_android_args ||
        !g_android_args->hookLibDir ||
        !g_android_args->driverDir  ||
        !g_android_args->driverName) {
        return NULL;  /* no custom driver configured — use system loader */
    }

    void *handle = adrenotools_open_libvulkan(
        RTLD_NOW | RTLD_LOCAL,
        ADRENOTOOLS_DRIVER_CUSTOM,
        NULL,                          /* tmpLibDir — not needed on API 29+ */
        g_android_args->hookLibDir,
        g_android_args->driverDir,
        g_android_args->driverName,
        NULL,                          /* fileRedirectDir — unused */
        NULL                           /* userMappingHandle — unused */
    );
    if (!handle) {
        LOGE("adrenotools_open_libvulkan failed — falling back to system Vulkan loader");
        return NULL;
    }

    PFN_vkGetInstanceProcAddr proc =
        (PFN_vkGetInstanceProcAddr)dlsym(handle, "vkGetInstanceProcAddr");
    if (!proc) {
        LOGE("dlsym(vkGetInstanceProcAddr) failed in custom driver — falling back");
        return NULL;
    }

    LOGI("Custom Vulkan driver loaded: %s/%s",
         g_android_args->driverDir, g_android_args->driverName);
    return proc;
#else
    return NULL;
#endif
}

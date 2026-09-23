/*
 * ADPF (Android Dynamic Performance Framework) hint session.
 *
 * Tells the platform's power governor how long each guest frame actually took
 * against the frame budget, for the two threads that set the frame rate: the
 * vCPU and nv2a.pfifo.  A frame over budget asks for more clock (and on some
 * vendors a better core) *before* the governor's own utilisation averaging
 * would notice; that is the whole point, since the tail this port fights is
 * a few heavy frames, not a slow mean.
 *
 * The budget follows the game's own pace: 16.7 ms if most recent frames fit
 * in 20 ms, otherwise 33.3 ms.  A fixed 33.3 ms target would tell a 60 fps
 * game it has half a frame of headroom and invite the governor to drop clocks.
 *
 * API 33+.  minSdk is 28, so everything is resolved with dlsym and the
 * feature is simply absent below 33 or where the HAL declines a session.
 *
 * OFF BY DEFAULT, and only reachable through `setprop debug.xemu.adpf 1`
 * (re-read about once a second, so an A/B can toggle it inside one process).
 * Measured on the AYN Thor (FINDINGS AO): no benefit, because there is no
 * clock left to ask for -- the pinned vCPU's X3 core already sits at its
 * 3187 MHz maximum and the NV2A thread's cluster at 2803 MHz, with or without
 * the session.  Kept because a phone whose governor drops clocks more
 * eagerly is exactly where it could matter, and this makes that a one-line
 * test.
 */

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <time.h>

#include "xemu_android.h"

#define TAG "xemu-adpf"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;

static APerformanceHintManager *(*p_get_manager)(void);
static APerformanceHintSession *(*p_create_session)(
    APerformanceHintManager *, const int32_t *, size_t, int64_t);
static int (*p_update_target)(APerformanceHintSession *, int64_t);
static int (*p_report_actual)(APerformanceHintSession *, int64_t);
static void (*p_close_session)(APerformanceHintSession *);

extern int xemu_vcpu_tid;       /* accel/tcg/cpu-exec.c */

#define TARGET_30_NS  33333333LL
#define TARGET_60_NS  16666667LL
#define PACE_WINDOW   60        /* frames between pace decisions */
#define MAX_REPORT_NS 1000000000LL  /* longer = paused or backgrounded */

static bool s_api_ok, s_api_tried, s_session_failed;
static APerformanceHintSession *s_session;
static int64_t s_target_ns = TARGET_30_NS;
static int64_t s_last_frame_ns;
static int64_t s_last_prop_check_ns;
static int s_prop_override = -1;    /* -1 none, 0 off, 1 on */
static int s_pace_frames, s_pace_fast;

/* Exposed for the benchmark log, so a run can prove the session engaged. */
unsigned long long xemu_adpf_reports;
unsigned long long xemu_adpf_over_target;

static int64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static bool load_api(void)
{
    if (s_api_tried) {
        return s_api_ok;
    }
    s_api_tried = true;

    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        return false;
    }
    *(void **)&p_get_manager = dlsym(lib, "APerformanceHint_getManager");
    *(void **)&p_create_session = dlsym(lib, "APerformanceHint_createSession");
    *(void **)&p_update_target = dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
    *(void **)&p_report_actual = dlsym(lib, "APerformanceHint_reportActualWorkDuration");
    *(void **)&p_close_session = dlsym(lib, "APerformanceHint_closeSession");
    s_api_ok = p_get_manager && p_create_session && p_update_target &&
               p_report_actual && p_close_session;
    if (!s_api_ok) {
        LOGI("ADPF unavailable (API < 33)");
    }
    return s_api_ok;
}

/* nv2a.pfifo has no exported tid; find it by the name QEMU gives it. */
static int find_thread_by_prefix(const char *prefix)
{
    DIR *d = opendir("/proc/self/task");
    if (!d) {
        return 0;
    }
    int tid = 0;
    struct dirent *e;
    while (!tid && (e = readdir(d))) {
        char path[64], comm[32] = { 0 };
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", e->d_name);
        FILE *f = fopen(path, "r");
        if (!f) {
            continue;
        }
        if (fgets(comm, sizeof(comm), f) &&
            !strncmp(comm, prefix, strlen(prefix))) {
            tid = atoi(e->d_name);
        }
        fclose(f);
    }
    closedir(d);
    return tid;
}

static void open_session(void)
{
    if (s_session_failed || !load_api() || !xemu_vcpu_tid) {
        return;
    }
    int32_t tids[2];
    size_t n = 0;
    tids[n++] = xemu_vcpu_tid;
    int pfifo = find_thread_by_prefix("nv2a.pfifo");
    if (pfifo) {
        tids[n++] = pfifo;
    }

    APerformanceHintManager *m = p_get_manager();
    s_session = m ? p_create_session(m, tids, n, s_target_ns) : NULL;
    if (!s_session) {
        /* The HAL may decline; do not retry every frame. */
        s_session_failed = true;
        LOGI("ADPF session refused by the platform");
        return;
    }
    LOGI("ADPF session open: vcpu %d, pfifo %d, target %.1f ms",
         tids[0], pfifo, s_target_ns / 1e6);
}

static void close_session(void)
{
    if (s_session) {
        p_close_session(s_session);
        s_session = NULL;
        LOGI("ADPF session closed");
    }
}

static bool effective_enabled(int64_t now)
{
    if (now - s_last_prop_check_ns > 1000000000LL) {
        char v[PROP_VALUE_MAX] = { 0 };
        s_last_prop_check_ns = now;
        s_prop_override = __system_property_get("debug.xemu.adpf", v) > 0
                              ? (v[0] == '1') : -1;
    }
    return s_prop_override == 1;
}

/* Render thread, once per NEW guest frame. */
void xemu_adpf_on_guest_frame(void)
{
    int64_t now = now_ns();
    int64_t interval = s_last_frame_ns ? now - s_last_frame_ns : 0;
    s_last_frame_ns = now;

    if (!effective_enabled(now)) {
        close_session();
        return;
    }
    if (!s_session) {
        open_session();
        if (!s_session) {
            return;
        }
    }
    if (interval <= 0 || interval > MAX_REPORT_NS) {
        return;
    }

    /* Follow the game's pace: 60 fps if most recent frames fit in 20 ms. */
    s_pace_frames++;
    s_pace_fast += interval < 20000000LL;
    if (s_pace_frames == PACE_WINDOW) {
        int64_t target = s_pace_fast * 2 > PACE_WINDOW ? TARGET_60_NS
                                                        : TARGET_30_NS;
        if (target != s_target_ns) {
            s_target_ns = target;
            p_update_target(s_session, target);
            LOGI("ADPF target %.1f ms", target / 1e6);
        }
        s_pace_frames = s_pace_fast = 0;
    }

    p_report_actual(s_session, interval);
    xemu_adpf_reports++;
    /* 5% tolerance: a 33.4 ms frame on a 33.3 ms target is on pace. */
    xemu_adpf_over_target += interval > s_target_ns + s_target_ns / 20;
}

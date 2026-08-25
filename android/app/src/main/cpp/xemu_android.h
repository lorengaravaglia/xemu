#ifndef XEMU_ANDROID_H
#define XEMU_ANDROID_H

#include <jni.h>
#include <android/native_window.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    char *configPath;
    char *mcpxPath;
    char *biosPath;
    char *hddPath;
    char *isoPath;      /* optional disc image; NULL or empty = no disc */
    char *renderer;     /* "VULKAN", "OPENGL", or NULL to use saved config */
    char *hookLibDir;   /* app's nativeLibraryDir (holds adrenotools hook .so files) */
    char *driverDir;    /* dir containing custom Vulkan driver .so; NULL = system driver */
    char *driverName;   /* soname of custom Vulkan driver; NULL = system driver */
} ThreadArgs;

extern ThreadArgs *g_android_args;
extern pid_t xemu_main_thread_id;

/**
 * Starts the xemu emulation thread with all required system files.
 * isoPath may be NULL or empty string for disc-less boot (dashboard).
 */
void xemu_android_start(
    const char *configPath,
    const char *mcpxPath,
    const char *biosPath,
    const char *hddPath,
    const char *isoPath,
    const char *renderer,    /* "VULKAN", "OPENGL", or NULL for default */
    const char *hookLibDir,  /* app's nativeLibraryDir for adrenotools hook .so files */
    const char *driverDir,   /* dir containing custom Vulkan driver .so; NULL = system */
    const char *driverName,  /* soname of custom Vulkan driver; NULL = system */
    ANativeWindow *window
);

void xemu_android_stop(void);
ANativeWindow *xemu_android_get_window(void);

/* Surface lifecycle — called from JNI on the Java main thread. */
void xemu_android_surface_destroyed(void);
void xemu_android_surface_created(ANativeWindow *window);

/* Called from display_very_early_init() after initial EGL surface creation. */
void xemu_android_surface_mark_valid(void);

/* Returns true if egl_surface is valid and the render thread may use it. */
bool xemu_android_surface_valid(void);

/* Block the calling thread until the EGL surface is valid (app foregrounded)
 * or a short timeout expires.  Safe to call from any thread. */
void xemu_android_wait_for_surface(void);
void xemu_android_input_init(void);

/* Called from JNI input thread to update the virtual controller state.
 * mask values are CONTROLLER_BUTTON_* from xemu-input.h.
 * axis_index values are CONTROLLER_AXIS_* from xemu-input.h. */
void xemu_android_set_button(uint32_t mask, int pressed);
void xemu_android_set_axis(int axis_index, int16_t value);

/* Terminate the emulation process and return to MainActivity. */
void xemu_android_request_exit(void);

/* Pause/resume the QEMU VM (stops TCG, PFIFO, PGRAPH, audio).
 * Defined in ui/xemu.c — linked into the final .so via Meson objects. */
void xemu_android_vm_pause(void);
void xemu_android_vm_resume(void);

/* Stop the VM and flush all block devices (qcow2 HDD) to disk, clearing
 * the dirty bit so the next session can open the image cleanly.
 * Defined in ui/xemu.c. */
void xemu_android_flush_block_devices(void);

/* Returns true once qemu_init() has completed and the BQL is initialized. */
bool xemu_android_qemu_initialized(void);

/* Returns the total number of frames rendered (eglSwapBuffers calls).
 * Sample twice with a known interval to compute FPS. */
int xemu_android_get_rendered_frame_count(void);

/* Returns the worst frame time (ms) seen since the last call and resets the
 * accumulator.  Measures time between successive eglSwapBuffers calls on the
 * render thread — call once per second from the Kotlin overlay runnable. */
int xemu_android_get_worst_frame_time_ms(void);

/* Copies up to [capacity] frame time samples (ms) into [buf] in chronological
 * order (oldest first) and sets *out_count to the number written.
 * Capacity should be at least 60. */
void xemu_android_get_frame_time_history(int *buf, int capacity, int *out_count);

/* Returns the time (ms) the render thread last spent blocked waiting for
 * the PGRAPH thread to signal sync_complete (nv2a_get_framebuffer_surface).
 * High values indicate the GPU is the bottleneck. */
int xemu_android_get_pgraph_sync_wait_ms(void);

/* Bracket a measurement window (called by InputRecorder around replay).
 * starting != 0 resets the accumulators; starting == 0 emits one SUMMARY line
 * to logcat tag "xemu-frameprof" covering exactly that window. */
void xemu_android_frameprof_mark(int starting);

/* HRTF 3D positional audio; takes effect on the next audio frame. */
void xemu_android_set_hrtf(bool enabled);

/* Skip the Xbox startup animation; must be set before qemu_init(). */
void xemu_android_set_skip_boot_anim(bool enabled);

/* Voice-processing worker threads; read once at startup, negative = default. */
void xemu_android_set_voice_workers(int n);

/* Returns the running total of GL shader programs compiled this session.
 * Increments each time pgraph_gl_compile_shader() succeeds. */
int xemu_android_get_compiled_shader_count(void);

/* Internal resolution scale factor passed to nv2a_set_surface_scale_factor().
 * Set before or just after startEmulation; applied on first frame after QEMU init.
 * Valid values: 1 (native), 2 (2×), 3 (3×). */
void xemu_android_set_surface_scale(unsigned int scale);
unsigned int xemu_android_get_surface_scale(void);

/* Returns the current Xbox rumble motor intensities (0–65535 each).
 * Written by xemu_input_update_rumble(); polled from Kotlin every ~100 ms. */
void xemu_android_get_rumble(uint16_t *left, uint16_t *right);

/* Save/load a named VM snapshot (stored inside the qcow2 HDD image).
 * Slot convention used by the UI: "slot_1" … "slot_8".
 * Safe to call from any thread; acquires the BQL internally. */
void xemu_android_save_state(const char *name);
void xemu_android_load_state(const char *name);

/* Return names of all existing snapshots in *out_names.
 * Free the result with xemu_android_free_state_names(names, count).
 * Returns the count; 0 on error or when no snapshots exist. */
int xemu_android_list_states(char ***out_names);

/* Free the name array returned by xemu_android_list_states(). */
void xemu_android_free_state_names(char **names, int count);

#ifdef __cplusplus
}
#endif

#endif

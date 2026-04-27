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
    char *isoPath;   /* optional disc image; NULL or empty = no disc */
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

#ifdef __cplusplus
}
#endif

#endif

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
void xemu_android_input_init(void);

/* Called from JNI input thread to update the virtual controller state.
 * mask values are CONTROLLER_BUTTON_* from xemu-input.h.
 * axis_index values are CONTROLLER_AXIS_* from xemu-input.h. */
void xemu_android_set_button(uint32_t mask, int pressed);
void xemu_android_set_axis(int axis_index, int16_t value);

/* Terminate the emulation process and return to MainActivity. */
void xemu_android_request_exit(void);

#ifdef __cplusplus
}
#endif

#endif

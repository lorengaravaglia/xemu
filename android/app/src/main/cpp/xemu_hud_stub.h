#ifndef XEMU_HUD_STUB_H
#define XEMU_HUD_STUB_H

#include <stdbool.h>
#include <stdint.h>
#include <SDL3/SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

// Forward declarations of stub functions for the HUD
void xemu_hud_init(SDL_Window* window, void* sdl_gl_context);
void xemu_hud_cleanup(void);
void xemu_hud_process_sdl_events(SDL_Event *event);
void xemu_hud_should_capture_kbd_mouse(int *kbd, int *mouse);
void xemu_hud_set_framebuffer_texture(uint32_t tex, bool flip);
void xemu_hud_update(void);
void xemu_hud_render(void);

/* Aspect ratio control.
 * wide=true  → 16:9 stretch (default).
 * wide=false → 4:3 with pillarboxing/letterboxing. */
void xemu_hud_set_aspect_16x9(bool wide);

#ifdef __cplusplus
}
#endif

#endif // XEMU_HUD_STUB_H

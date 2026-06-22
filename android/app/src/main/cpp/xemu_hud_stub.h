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
 * ratio: 0=Native (integer scale), 1=Auto (stretch), 2=4:3 (pillarbox), 3=16:9 (stretch).
 * xemu_hud_set_aspect_16x9 kept for legacy callers. */
void xemu_hud_set_aspect_ratio(int ratio);
void xemu_hud_set_aspect_16x9(bool wide);

/* Texture filter for the final blit.
 * nearest=true → GL_NEAREST (pixel-art / sharp scaling).
 * nearest=false → GL_LINEAR (default, smooth). */
void xemu_hud_set_filter_nearest(bool nearest);

#ifdef __cplusplus
}
#endif

#endif // XEMU_HUD_STUB_H

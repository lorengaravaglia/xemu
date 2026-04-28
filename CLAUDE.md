# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **xemu** — an original Xbox emulator — being ported to Android. The codebase is a fork of QEMU targeting the i386-softmmu machine type with Xbox hardware emulation. The Android port is an active work-in-progress.

## Dual Build System Architecture

The Android port uses a **hybrid two-stage build**:

1. **Stage 1 — Meson/Ninja (core engine):** Cross-compiles all QEMU/xemu core libraries for `arm64-android` into static `.a` files with PIC objects. Output lives in `build/`.

2. **Stage 2 — CMake/Android Studio (JNI wrapper):** Compiles the Android-patched `ui/xemu*.c` files directly (with `#if ANDROID` guards), then links against the Meson `.o` object files via `file(GLOB_RECURSE ...)`. This avoids re-running Meson for every Android tweak.

**Critical invariant:** Both build systems must use identical flags: `-mbranch-protection=none -fno-sanitize=shadow-call-stack`. This prevents PAC/SCS security hardening from crashing coroutine stack switching on AArch64. Do not add these flags to only one side.

## Build Commands

### Meson (core engine — run first, then only when core C files change)

```bash
# Initial setup
./rebuild_meson.sh

# Incremental rebuild (after meson setup)
ninja -C build
```

`rebuild_meson.sh` generates `build/config-host.mak` manually (required by meson.build), then runs `meson setup` with the Android cross-file.

### Android Studio (JNI + APK)

Open `android/` as the project root in Android Studio. Use **Build → Clean Build**, then **Run**. The CMake build in `android/app/src/main/cpp/CMakeLists.txt` is driven automatically.

After changing `CMakeLists.txt`: use **Build → Refresh Linked C++ Projects** before running.

### Updating Meson-compiled core files (e.g. `hw/xbox/nv2a/*.c`)

CMake links `libqemu-i386-softmmu.a` (a regular static archive). After editing any core file compiled by Meson, a **two-step** rebuild is required:

```bash
# Step 1: recompile changed .o files and rebuild the Meson fat thin archive
ninja -C build libqemu-i386-softmmu.a

# Step 2: replace the thin archive with a slim regular archive (own objects only)
NDK_PATH='/Users/lorengaravaglia/Library/Android/sdk/ndk/29.0.14206865'
LLVM_AR="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
rm -f build/libqemu-i386-softmmu.a
"$LLVM_AR" rcs build/libqemu-i386-softmmu.a build/libqemu-i386-softmmu.a.p/*.o
```

**Why step 2 is mandatory:** Meson produces a "fat" thin archive that embeds references to objects from `libsystem.a.p/` and `libcommon.a.p/` alongside the 152 `libqemu-i386-softmmu.a.p/` objects. CMakeLists.txt links both `libqemu-i386-softmmu.a` AND `libsystem.a`/`libcommon.a` with `--whole-archive`. Without step 2, every type registered in `libsystem.a.p/` runs its `type_init()` constructor twice → `"Registering 'cpu' which already exists"` SIGABRT at startup.

If Meson thinks the `.o` is up-to-date despite source changes (same-minute timestamp), force it before step 1:

```bash
touch hw/xbox/nv2a/nv2a.c   # or whichever file was changed
```

Then in Android Studio: **Build → Clean Project → Rebuild Project**.

Things to avoid:
- `ninja -C build "libqemu-i386-softmmu.a.p/hw_xbox_nv2a_nv2a.c.o"` — only updates the `.o`, not the `.a`
- Skipping step 2 — causes the `cpu` type double-registration crash on startup

### ⚠️ CRITICAL: Never apply the slim-archive procedure to `libsystem.a`

The slim-archive `llvm-ar rcs` procedure above applies **ONLY to `libqemu-i386-softmmu.a`**. Never apply it to `libsystem.a` or any other Meson archive.

**Why:** `libsystem.a` is a Meson thin archive whose members include objects from `libqom.a.p/`, `libio.a.p/`, `libmigration.a.p/`, and other subdirectories — the **same physical files** that are also in `libqom.a`, `libio.a`, etc. When lld links `--whole-archive libsystem.a` alongside `--whole-archive libqom.a`, it deduplicates by file path and only includes each object once. If you convert `libsystem.a` to a regular archive (embedding copies of those objects), lld sees them as separate objects and runs every `type_init()` constructor twice — causing dozens of cascading `"Registering 'X' which already exists"` and `"Type 'X' is missing its parent 'Y'"` SIGABRTs at startup that require hours of iterative archive surgery to fix.

**If `libsystem.a` is accidentally overwritten:** run `./fixup_archives.sh` from the repo root. It runs `ninja -C build`, applies the slim-archive procedure to `libqemu-i386-softmmu.a`, and removes all known-bad objects from `libsystem.a` automatically.

**If ninja cannot run** (e.g. 34 objects cannot be recompiled due to missing NDK headers for `libvfio-user.h` / `9p-marshal.h`): see the recovery object list below.

### libsystem.a recovery object list (if manual reconstruction is unavoidable)

After running `ninja -C build` and rebuilding as a regular archive from the ninja rule objects, the following objects **must be removed** with `llvm-ar d` to prevent linker errors and runtime QOM crashes. These objects are either non-Xbox platform code with missing Android dependencies, CMake-compiled duplicates, or QOM types whose parent/interface types were removed:

```bash
NDK=/Users/lorengaravaglia/Library/Android/sdk/ndk/29.0.14206865
LLVM_AR=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar

# ── CMake-compiled files (double-register their QOM types) ───────────────────
$LLVM_AR d build/libsystem.a \
  ui_xemu-monitor.c.o ui_xemu.c.o ui_xemu-input.c.o ui_xemu-snapshots.c.o \
  ui_xemu-widescreen.c.o ui_xemu-net.c.o ui_xemu-data.c.o \
  ui_xemu-os-utils-linux.c.o ui_xui_main.cc.o ui_xui_notifications.cc.o

# ── Objects duplicated in other --whole-archive libraries ────────────────────
# (libqom.a, libblock.a, libauthz.a, etc. — 153 objects total)
# Build the list with:
#   for lib in libqom.a libcommon.a libio.a libmigration.a libqmp.a libcrypto.a \
#              libevent-loop-base.a libhwcore.a libchardev.a libblock.a \
#              libblockdev.a libauthz.a libqemuutil.a; do
#     $LLVM_AR t build/$lib | sed 's|.*/||'
#   done | sort -u > /tmp/other.txt
#   $LLVM_AR t build/libsystem.a | sort -u > /tmp/sys.txt
#   comm -12 /tmp/other.txt /tmp/sys.txt | xargs $LLVM_AR d build/libsystem.a

# ── Non-Xbox platform hardware (ARM GIC, Xilinx, missing NDK symbols) ────────
$LLVM_AR d build/libsystem.a \
  hw_intc_arm_gic_common.c.o hw_intc_arm_gic.c.o \
  hw_intc_allwinner-a10-pic.c.o hw_intc_arm_gicv2m.c.o \
  hw_intc_arm_gicv3_its_common.c.o hw_intc_aspeed_intc.c.o \
  hw_intc_aspeed_vic.c.o hw_intc_bcm2835_ic.c.o hw_intc_bcm2836_control.c.o \
  hw_intc_exynos4210_combiner.c.o hw_intc_exynos4210_gic.c.o \
  hw_intc_goldfish_pic.c.o hw_intc_heathrow_pic.c.o \
  hw_intc_imx_avic.c.o hw_intc_imx_gpcv2.c.o hw_intc_omap_intc.c.o \
  hw_intc_pl190.c.o hw_intc_realview_gic.c.o hw_intc_slavio_intctl.c.o \
  hw_intc_xilinx_intc.c.o hw_intc_xlnx-pmu-iomod-intc.c.o \
  hw_intc_xlnx-zynqmp-ipi.c.o \
  hw_misc_arm_integrator_debug.c.o hw_misc_arm_l2x0.c.o \
  hw_misc_arm_sysctl.c.o hw_misc_arm11scu.c.o \
  hw_misc_armsse-cpu-pwrctrl.c.o hw_misc_armsse-cpuid.c.o \
  hw_misc_armsse-mhu.c.o hw_misc_armv7m_ras.c.o \
  hw_misc_xlnx-cfi-if.c.o hw_misc_xlnx-versal-cframe-reg.c.o \
  hw_misc_xlnx-versal-cfu.c.o hw_misc_xlnx-versal-pmc-iou-slcr.c.o \
  hw_misc_xlnx-versal-trng.c.o hw_misc_xlnx-versal-xramc.c.o \
  hw_misc_xlnx-zynqmp-apu-ctrl.c.o

# ── virtio objects with uncompiled dependencies or removed parents ────────────
$LLVM_AR d build/libsystem.a \
  hw_virtio_virtio-pci.c.o hw_virtio_virtio-bus.c.o \
  hw_virtio_virtio-mmio.c.o hw_virtio_virtio-md-pci.c.o \
  hw_virtio_vhost-user-vsock.c.o hw_scsi_vhost-user-scsi.c.o \
  hw_char_virtio-console.c.o hw_input_virtio-input.c.o \
  hw_input_virtio-input-host.c.o hw_audio_virtio-snd.c.o \
  hw_audio_virtio-snd-pci.c.o hw_virtio_virtio-mem.c.o \
  hw_virtio_virtio-pmem.c.o hw_s390x_virtio-ccw-gpu.c.o \
  hw_vmapple_virtio-blk.c.o hw_scsi_virtio-scsi-dataplane.c.o

# ── Objects with other missing dependencies ──────────────────────────────────
$LLVM_AR d build/libsystem.a \
  hw_remote_message.c.o hw_remote_proxy.c.o hw_remote_remote-obj.c.o \
  semihosting_arm-compat-semi.c.o fsdev_qemu-fsdev.c.o \
  hw_gpio_omap_gpio.c.o hw_i2c_omap_i2c.c.o hw_sd_omap_mmc.c.o \
  hw_dma_omap_dma.c.o \
  hw_vfio-user_pci.c.o hw_vfio-user_container.c.o \
  hw_vfio_cpr-load.c.o hw_vfio_cpr-save.c.o hw_vfio_device.c.o \
  hw_vfio_region.c.o hw_vfio_migration-multifd.c.o \
  hw_vfio_migration-multifd-load.c.o hw_vfio_migration.c.o \
  hw_vfio_display.c.o \
  hw_acpi_ich9.c.o hw_acpi_ich9_tco.c.o hw_acpi_ich9_timer.c.o \
  hw_intc_openpic.c.o \
  hw_hyperv_hv-balloon.c.o \
  hw_char_sclpconsole.c.o hw_char_sclpconsole-lm.c.o \
  hw_block_xen-block.c.o hw_core_guest-loader.c.o \
  migration_vfio.c.o
```

## Key Files

| File | Purpose |
|------|---------|
| `android_arm64.txt` | Meson cross-file for NDK toolchain (API 28, aarch64) |
| `rebuild_meson.sh` | Full Meson reconfigure + build script |
| `android/app/src/main/cpp/CMakeLists.txt` | JNI CMake build — links Meson objects + compiles Android-patched sources |
| `android/app/src/main/cpp/xemu_android.c` | Entry point: `xemu_android_start()`, EGL context management, emulation thread |
| `android/app/src/main/cpp/xemu_hud_stub.c` | Replaces `ui/xui/main.cc` and `ui/xui/notifications.cc` for Android |
| `android/app/src/main/cpp/xemu_os_utils_android.c` | Replaces `ui/xemu-os-utils-linux.c` |
| `android/app/src/main/cpp/NativeInterface.cpp` | JNI entry point: `Java_com_xemu_NativeInterface_*` |
| `ui/xemu.c` | Main display/render loop — heavily patched with `#if ANDROID` guards |
| `ui/xemu-input.c` | Input handling — patched for Android |
| `util/oslib-posix.c` | `shm_open` replaced with `qemu_memfd_create` for Bionic |

## Android-Specific Source Patching Pattern

Files in `ui/xemu*.c` are patched in-place with `#if defined(__ANDROID__) || defined(ANDROID)` guards. The modified files are compiled **directly by CMake** (not from Meson objects). The corresponding Meson `.o` files are excluded from the object glob by exact-match regex filters in `CMakeLists.txt`.

Files compiled by CMake directly (and therefore filtered from Meson objects):
- `ui/xemu.c`, `ui/xemu-input.c`, `ui/xemu-monitor.c`, `ui/xemu-snapshots.c`
- `ui/xemu-widescreen.c`, `ui/xemu-net.c`, `ui/xemu-data.c`
- `ui/xemu-os-utils-linux.c` (replaced by `xemu_os_utils_android.c`)

Files from Meson objects that are **stub-replaced** (filtered from Meson objects):
- `ui_xui_main.cc.o` → replaced by `xemu_hud_stub.c` (defines `xemu_hud_*`, `g_screenshot_pending`, `g_main_menu_height`)
- `ui_xui_notifications.cc.o` → replaced by stubs in `xemu_hud_stub.c` (defines `xemu_queue_notification`, `xemu_queue_error_message`)

All other `ui_xui_*.cc.o` objects (gl-helpers, font-manager, actions, etc.) are kept from Meson because they define symbols needed at link time.

## CMakeLists.txt Object Filtering Pattern

**Lazy archive linking with `-Wl,-u`:** Meson QEMU libraries are linked with standard lazy loading — only objects whose symbols are referenced get pulled in. Most QEMU objects are reached transitively (Xbox machine code calls device init functions, which pull in their objects and run their `type_init()` constructors). The one exception is `hw/xbox/nv2a/pgraph/gl/renderer.c`: it registers the OpenGL PGRAPH renderer solely via `static __attribute__((constructor)) register_renderer()` with no exported function ever called directly. Without forcing it in, `renderers[OPENGL]` stays NULL and `nv2a_context_init()` crashes. The link line uses `-Wl,-u,g_nv2a_context_render` (a global BSS symbol defined in that file) to force the object to be included. If a new constructor-only registration is added in future, add another `-Wl,-u,<symbol>` for an exported symbol from that file.

**`--allow-multiple-definition`** (singular, not plural): QEMU intentionally compiles both stub and real implementations of some symbols. This flag suppresses duplicate-symbol errors when both are present.

**Android-patched file conflicts:** When a file is compiled directly by CMake (with Android patches), filter out its Meson counterpart with a precise regex:
```cmake
list(FILTER MESON_OBJECTS EXCLUDE REGEX "exact_filename\\.c\\.o$")
```
Never use broad wildcards (e.g., `ui_xemu.*`) — they accidentally exclude files needed from Meson.

When a new undefined-symbol linker error appears, check:
1. Is the symbol in a Meson object that's being accidentally filtered? → narrow the filter
2. Is the symbol from a `ui/xui/` file we've excluded? → add a stub to `xemu_hud_stub.c`
3. Is the symbol behind a `#ifdef CONFIG_*` guard? → add the define to `add_definitions()` in CMakeLists.txt

## EGL / Rendering Architecture (Android)

Desktop xemu uses SDL + OpenGL. Android replaces this with EGL + GLES 3.0.

### Three-context model

Three EGL contexts are used, all sharing GL objects (textures, programs, buffers):

| Context | Owner | Surface | Purpose |
|---------|-------|---------|---------|
| `egl_context` | `xemu_android.c` | `egl_surface` (ANativeWindow) | xemu_core render thread: final blit + `eglSwapBuffers` |
| `g_nv2a_context_render` | `sdl.c` via `glo_context_create()` | 1×1 pbuffer | PFIFO/pgraph thread: NV2A geometry rendering |
| `g_nv2a_context_display` | `sdl.c` via `glo_context_create()` | 1×1 pbuffer | PFIFO/pgraph thread: display compositing (`render_display`) |

The two NV2A contexts are created in `glo_context_create()` (`hw/xbox/nv2a/pgraph/thirdparty/gloffscreen/sdl.c`) as real EGL contexts sharing resources with `egl_context`. Each gets its own 1×1 pbuffer surface.

`egl_config` is a global in `xemu_android.c`, set by `display_very_early_init()` after `eglChooseConfig`, and must be set before `nv2a_context_init()` calls `glo_context_create()`. The EGL config must include `EGL_PBUFFER_BIT` in `EGL_SURFACE_TYPE`.

`glo_set_current()` on Android calls `eglMakeCurrent` directly with the context's own `egl_ctx` and `egl_pbuf` — no mutex needed since each thread has its own context. `set_egl_current(bool)` in `xemu_android.c` is called only by the xemu_core render thread to manage `egl_context`.

### Frame delivery path

1. PFIFO thread renders NV2A geometry into surface FBOs → `surface->gl_buffer` texture (render context)
2. PFIFO calls `pgraph_gl_sync()` → `render_display()` composites `surface->gl_buffer` into `gl_display_buffer` texture (display context)
3. xemu_core calls `nv2a_get_framebuffer_surface()` → waits for `sync_complete` → receives `gl_display_buffer` texture ID
4. `xemu_hud_render()` in `xemu_hud_stub.c` blits `gl_display_buffer` to FBO 0 (window surface) via a fullscreen quad shader
5. `eglSwapBuffers()` presents the frame

### GLES compatibility
- `GL_BGRA_EXT` / `GL_UNPACK_ROW_LENGTH_EXT` replaced with `GL_RGBA` / `GL_UNPACK_ROW_LENGTH`
- `glFinish()` replaced with `glFlush()` in render paths — `glFinish()` can stall indefinitely on some Android GLES drivers

### Texture color channel fixes (`hw/xbox/nv2a/pgraph/gl/texture.c`)

All formats using `GL_BGRA + GL_UNSIGNED_INT_8_8_8_8_REV` (A8R8G8B8, X8R8G8B8 — both SZ and LU_IMAGE variants) require two Android-specific fixes:

**1. Internal format upgrade** (`upload_gl_texture()`, Android block): GLES 3.0 requires the internal format's base format to match the external format. `GL_RGB8`/`GL_RGB5` have base format `GL_RGB`, which is incompatible with `GL_RGBA` (the substituted external format) — `glTexImage2D` returns `GL_INVALID_OPERATION` silently, leaving a black texture. Fix: upgrade `GL_RGB8`/`GL_RGB5` → `GL_RGBA8` when `GL_BGRA → GL_RGBA` substitution occurs.

**2. R/B channel swizzle** (`generate_texture()`, Android block): Substituting `GL_BGRA → GL_RGBA` causes Xbox_B to land in `texture.r` and Xbox_R in `texture.b`. Fix: set swizzle mask `{GL_BLUE, GL_GREEN, GL_RED, GL_ALPHA}` for all formats where `f.gl_format == GL_BGRA && f.gl_type == GL_UNSIGNED_INT_8_8_8_8_REV`. This condition correctly excludes B8G8R8A8 (no REV) and A4R4G4B4 (manually expanded via a separate path that resets the swizzle to identity).

**3. Surface-to-texture swizzle reset** (`pgraph_gl_render_surface_to_texture()` fast path in `surface.c`, Android block): When a GPU-rendered surface is used as a texture, the fast path reuses the existing GL texture object and renders the FBO content into it. The BGRA corrective swizzle set by `generate_texture()` for a prior CPU-uploaded use of that texture object must be reset to identity `{GL_RED, GL_GREEN, GL_BLUE, GL_ALPHA}` — FBO-rendered content is already in correct channel order and the corrective swizzle would incorrectly swap R↔B. Symptom if missing: render-to-texture surfaces appear with R↔B swap (e.g. Halo CE loading screen appeared purple instead of blue).

**xemu Y-axis convention**: Positive Y = stick pushed up (matches keyboard mapping in `xemu-input.c`). Android reports `AXIS_Y`/`AXIS_RZ` as −1.0 when pushed up, so both `GamepadView` (touch overlay) and `EmulationActivity` (physical controller path) negate Y axes before calling `NativeInterface.sendAxis()`.

## NV2A PGRAPH / GLSL Shader Compatibility (Android)

The NV2A GPU emulation generates GLSL shaders via `hw/xbox/nv2a/pgraph/gl/` (GL renderer) and `hw/xbox/nv2a/pgraph/glsl/` (GLSL generator). On Android these shaders are patched at runtime by `patch_shader_source()` in `hw/xbox/nv2a/pgraph/gl/shaders.c`.

### Runtime GLES capability detection (`shaders.c`)

Two cached probes run on first shader compilation:

| Flag | Purpose |
|------|---------|
| `g_has_geometry_shaders` | Probes `glCreateShader(GL_GEOMETRY_SHADER)` — returns 0 on GLES < 3.2, which also sets `GL_INVALID_ENUM`. The error is cleared and geometry shaders are skipped. |
| `g_gles_minor` | Reads `GL_MINOR_VERSION` via `glGetIntegerv` to distinguish GLES 3.0 vs 3.1+. |

### `patch_shader_source()` — what it does on Android

1. **Version line**: Replaces the desktop `#version 400` with `#version 310 es` (GLES 3.1+) or `#version 300 es` (GLES 3.0). Adds `precision highp float; precision highp int;` plus precision qualifiers for all sampler types (`sampler2D`, `sampler3D`, `samplerCube`, `sampler2DArray`, `sampler2DShadow`, `sampler2DArrayShadow`) — GLES requires explicit precision on all sampler uniforms or a shader compile error results.
2. **`bitfieldExtract` polyfill** (GLES 3.0 only): This built-in is not available until GLSL ES 3.10. A sign-extending integer polyfill is injected into the shader preamble.
3. **Fragment shader fixups**: Removes the `out vec4 out_Color` declaration (replaced by `fragColor` via `#define`), fixes `textureSize()` return type cast.
4. **uint/int type fixes** (all shaders): Strict GLSL ES rejects `uint == int` and passing ambiguous hex literals to `uintBitsToFloat`. Replacements applied:
   - `floatBitsToUint(t) == 0` → `floatBitsToUint(t) == 0u`
   - `uintBitsToFloat(0xDF800000)` → `uintBitsToFloat(0xDF800000u)`
   - `uintBitsToFloat(0x9F800000)` → `uintBitsToFloat(0x9F800000u)`

**Source-level int literal fixes** — some int-vs-float issues are fixed at the shader generator rather than via `patch_shader_source()`:
- `psh.c:1282` `PS_TEXTUREMODES_DOT_RFLCT_SPEC`: `2*n_N*dot(...)` → `2.0*n_N*dot(...)`. GLSL ES rejects `int * vec3`. Fixed directly in source.

### Meson object for shaders.c

`hw/xbox/nv2a/pgraph/gl/shaders.c` is compiled by Meson (like all other `pgraph/gl/*.c` files). If this object is missing from `build/libqemu-i386-softmmu.a.p/`, run:
```bash
ninja -C build "libqemu-i386-softmmu.a.p/hw_xbox_nv2a_pgraph_gl_shaders.c.o"
```
A preprocessor error in `shaders.c` (e.g. mismatched `#if`/`#endif`) silently drops the `.o` file while all other objects in the same `meson.build` block still build. Symptoms: linker errors for `pgraph_gl_init_shaders`, `pgraph_gl_finalize_shaders`, `pgraph_gl_bind_shaders`, `pgraph_gl_compile_shader`, `pgraph_gl_shader_write_cache_reload_list`.

## Activity / Thread Architecture

```
MainActivity (Kotlin)
  └─ selects ROM files, launches EmulationActivity

EmulationActivity (Kotlin)
  └─ SurfaceView → surfaceCreated → NativeInterface.startEmulation()

NativeInterface.startEmulation() [JNI, NativeInterface.cpp]
  └─ xemu_android_start() [xemu_android.c]
       ├─ setup_logging() — pipes stdout/stderr to Android logcat
       └─ pthread_create → xemu_android_thread
                           └─ xemu_core_main() [ui/xemu.c]
                                ├─ xemu_settings_load()
                                ├─ display_very_early_init() — EGL setup
                                ├─ pthread_create → qemu_main thread
                                │    └─ qemu_init() + qemu_main_loop()
                                └─ render loop: gl_render_frame()
```

`EmulationActivity` runs in a separate process (`:EmulationProcess`) per the manifest — this isolates the native crash domain from MainActivity.

## vcpkg Dependencies (arm64-android triplet)

Located at `/Users/lorengaravaglia/projects/vcpkg/installed/arm64-android/`. Key packages: `glib`, `pixman`, `libepoxy`, `SDL3`, `openssl`, `zlib`, `libpng`, `libsamplerate`.

The `pkg-config` path must be set to the vcpkg pkgconfig dir when running Meson (handled by `android_arm64.txt` `[properties]` section).

## Style Guide

From `.github/copilot-instructions.md`: developer style guide is at `docs/devel/style.rst`.

## Common Pitfalls

- **Never put C++ code in `.c` files.** `ui/xemu.c` and `ui/xemu-input.c` are compiled as C. ImGui types (`ImGuiContext*`, `ImGui::*`) and `std::` types are forbidden in these files.
- **`ui/xemu.c` is compiled by both Meson and CMake.** After modifying it, you may need to run `ninja -C build` to regenerate the Meson object if other Meson-linked code depends on it, but the CMake-compiled version is what the Android APK uses.
- **Meson object files are cached.** After a Meson rebuild, Android Studio may use stale cached CMake data. Always **Refresh Linked C++ Projects** when the Meson build output changes.
- **`xemu_is_main_thread()`** is only defined when `ANDROID` is defined (in `ui/xemu.c`). Do not call it from code paths that compile on non-Android without a `#if ANDROID` guard.
- **A preprocessor error in a Meson-compiled file silently drops only that `.o` file.** All other files in the same `meson.build` block still build successfully. If you see undefined-symbol linker errors for functions in a file that is listed in a `meson.build` but has no CMake FILTER EXCLUDE entry, the file likely failed to compile — check for mismatched `#if`/`#endif` and rebuild with `ninja -C build "path/to/the_file.c.o"` to see the error.
- **GLSL ES strict type rules.** The NV2A shader generators (`pgraph/glsl/vsh.c`, `vsh-prog.c`, `psh.c`) produce desktop GLSL 4.00. On Android, `patch_shader_source()` rewrites these to GLSL ES. Common failures: (1) built-ins missing before GLSL ES 3.10 (`bitfieldExtract`), (2) `uint == int` comparisons, (3) large hex literals without `u` suffix passed to `uintBitsToFloat`. Add new fixes as string replacements in `patch_shader_source()` in `shaders.c`, then rebuild that single `.o` with ninja.
- **Desktop-only GL calls crash via libepoxy.** On GLES, calling a function with no provider (e.g. `glProvokingVertex`, `glPolygonMode`) causes libepoxy to print "No provider of X found" then call `abort()`. Guard with `#if !defined(__ANDROID__) && !defined(ANDROID)`. Known cases fixed: `glProvokingVertex` (draw.c, gpuprops.c), `glEnable(GL_DEPTH_CLAMP)` (draw.c), `glEnable/Disable(GL_LINE_SMOOTH)` (draw.c), `GL_CLAMP_TO_BORDER`/`GL_TEXTURE_BORDER_COLOR` (surface.c — replaced with `GL_CLAMP_TO_EDGE`, texture.c already guarded).
- **`xemu-pgraph` logcat tag.** Diagnostic `ALOGI` calls in `shaders.c` use tag `xemu-pgraph` (not `xemu-android`). Use `-s xemu-pgraph:I` in your logcat filter to see uniform errors and GLES capability detection messages.
- **`generated/config-host.h` vs `build/config-host.h` layout mismatch.** CMake-compiled files find `android/app/src/main/cpp/generated/config-host.h` before `build/config-host.h` (due to include path order). The generated file is intentionally minimal, so any `CONFIG_*` flag that guards struct members in shared headers (e.g. `CONFIG_GIO` guards `set_dbus_server` in `struct audio_driver` in `audio_int.h`) **must** be explicitly added as `add_definitions(-DCONFIG_XXX=1)` in CMakeLists.txt. Forgetting this causes the CMake-compiled struct to have different field offsets than the Meson-compiled version, producing SIGSEGV with a small non-NULL fault address (e.g. `0x69` = `1 + 0x68` when `max_voices_out=1` is read as a pointer). When adding a new CMake-compiled file that uses QEMU shared headers, check `build/config-host.h` for any `CONFIG_*` flags that affect structs those headers define.
- **Surface VRAM upload has R/B swap on GLES (fixed in `surface.c`).** The BGRA→RGBA format substitution at surface creation time changes `GL_BGRA+GL_UNSIGNED_INT_8_8_8_8_REV` to `GL_RGBA+GL_UNSIGNED_BYTE`. When CPU-written VRAM data (BGRA byte order) is uploaded via `pgraph_gl_upload_surface_data`, the bytes are interpreted as RGBA, swapping R and B. Fixed in `surface.c`'s upload path by software-swapping bytes 0 and 2 for surfaces with `gl_format==GL_RGBA && gl_type==GL_UNSIGNED_BYTE && bytes_per_pixel==4`. GPU-rendered FBO content is unaffected (it never goes through the upload path after the initial clear).
- **Surface download to VRAM also has R/B swap on GLES (fixed in `surface_download_to_buffer`, `surface.c`).** `glReadPixels` on GLES always returns RGBA byte order. When a GPU-rendered surface is downloaded to VRAM (e.g. so it can be reused as a texture via `generate_texture()`), the RGBA bytes land in VRAM but `generate_texture()` assumes BGRA byte order and applies its corrective BGRA swizzle, double-swapping R↔B. Symptom: GPU-rendered intermediate surfaces used as textures (e.g. the Xbox boot logo glow) displayed with R↔B swap. Fixed by swapping bytes 0↔2 immediately after `glo_readpixels` in `surface_download_to_buffer` for `gl_format==GL_RGBA && gl_type==GL_UNSIGNED_BYTE && bytes_per_pixel==4` surfaces, restoring BGRA byte order in VRAM before `generate_texture()` reads it.
- **MCPx APU audio uses SDL directly, not the QEMU audio API.** `hw/xbox/mcpx/apu/monitor.c` calls `SDL_PutAudioStreamData`. SDL audio is not initialized on Android (SDL_Init skipped for stability). Fixed by adding an AAudio (NDK) path in `monitor.c` that opens a 48000 Hz S16LE stereo callback-mode stream with a ring buffer. The `libaaudio` symbols are resolved at final CMake link time (CMakeLists.txt already links libaaudio). Note: Meson-compiled files CAN reference NDK-specific functions (e.g. AAudio) as long as the final CMake link step provides the library — no Meson build change is needed.
- **Surface-to-texture swizzle must be reset to identity for GPU-rendered content.** `generate_texture()` sets a corrective BGRA swizzle `{GL_BLUE, GL_GREEN, GL_RED, GL_ALPHA}` on GL texture objects for CPU-uploaded BGRA data. If that same texture object is later used as the render target in `pgraph_gl_render_surface_to_texture()` (FBO blit path), the swizzle must be reset to identity — FBO content is GPU-rendered and already in correct RGBA channel order. See `surface.c` fast path.
- **qcow2 block device flush on exit.** `xemu_android_flush_block_devices()` (defined in `ui/xemu.c`) stops the VM, drains all block I/O, and flushes all block devices — this clears the qcow2 dirty bit so QEMU does not run crash recovery on the next open. Called from: (1) `xemu_android_request_exit()` for in-app exits; (2) `NativeInterface.flushBlockDevices()` → `EmulationActivity.onDestroy()` for system lifecycle kills (swiped from recents, etc.). For SIGKILL (Android Studio force-stop, OOM killer), no flush is possible — QEMU's built-in qcow2 recovery handles consistency on the next open.

## Android Port — Current Status

As of April 2026, the Android port boots the Xbox BIOS, completes the boot animation, and loads Halo: Combat Evolved to the main menu and in-game. The boot animation, loading screen, and in-game rendering all display with correct colors.

**Working:**
- EGL + GLES 3.0 rendering pipeline (three-context model above)
- NV2A PGRAPH: surfaces, draws, flip/sync, display compositing
- GLSL ES shader patching (`patch_shader_source()`) for GLES 3.0/3.1/3.2
- Boot animation renders at ~0.7 fps under TCG (expected — TCG is software x86-on-ARM)
- Post-animation/dashboard renders at ~30-40 fps under TCG
- Fullscreen blit to device screen via `xemu_hud_stub.c` quad shader
- Logging: stdout/stderr → logcat via pipe (`xemu-stdout` tag)
- Game disc loading via file descriptor (`/proc/self/fd/<n>`) — avoids copying large ISOs
- Controller input: physical/wireless gamepad (`dispatchKeyEvent` + `dispatchGenericMotionEvent`), on-screen touch overlay (`GamepadView`), auto-hide overlay when physical controller connected, button remapping UI (`MappingActivity`), mappings persisted in `SharedPreferences`
- Audio: MCPx APU routes through AAudio (NDK) in `hw/xbox/mcpx/apu/monitor.c` via ring buffer + callback-mode stream at 48000 Hz S16LE stereo. QEMU audio API aaudio backend (`audio/aaudiosdk.c`) also wired up. Confirmed working.
- Player profile creation: works correctly. qcow2 is flushed cleanly on normal exits and Android lifecycle kills (`onDestroy`). SIGKILL (Android Studio force-stop) relies on QEMU's built-in qcow2 crash recovery.
- Halo CE loading screen and in-game rendering: correct colors (surface-to-texture swizzle reset fixed purple tint).

**Not yet implemented / known gaps:**

| Feature | Notes |
|---------|-------|
| Shader cache persistence | Implemented: individual shader binaries written to `filesDir/shaders/` as compiled. On next launch, `shader_android_scan_and_load()` scans that directory (since `_exit()` prevents writing `shader_cache_list`). Confirmed: 78 shaders loaded from disk on second launch. Note: `__android_log_print` from Meson-compiled objects does not appear in logcat; use `fprintf(stderr,...)` → `xemu-stdout` for diagnostics from those files. |
| Background idle threads | When backgrounded, `vm_stop()` halts the vCPU and NV2A PGRAPH (no draw calls, no audio). Two lightweight threads remain active: the render thread spins at 60fps checking `xemu_android_surface_valid()==false` and returning immediately; `vblank_timer_thread` fires every ~200ms. Neither does meaningful work but both consume small amounts of battery. Fix: block the render thread on a condition variable when paused+no-surface; gate `vblank_timer_thread` on VM runstate. |
| Aspect ratio / display scaling | Implemented: 16:9 stretch (default) and 4:3 pillarbox/letterbox. Toggle via the in-game menu; persisted in SharedPreferences. `xemu_hud_set_aspect_16x9(bool)` in `xemu_hud_stub.c`. |
| Save states | Not tested on Android |

## Diagnostic Logcat Filter

```bash
~/Library/Android/sdk/platform-tools/adb logcat -s \
  xemu-pgraph:I xemu-android:I xemu-android-hud-stub:I xemu-stdout:I xemu-renderer:I
```

Key tags:
- `xemu-renderer`: `pgraph draw_end #N` — NV2A draw call count
- `xemu-pgraph`: `FLIP_STALL`, `pgraph_gl_sync`, `pre-render` pixel samples, `draw_end` milestones
- `xemu-android-hud-stub`: `xemu_hud_render: center pixel rgba=(...)` — confirms non-black pixels reach the screen
- `xemu-stdout`: `glo_context_create` errors (EGL context/pbuffer creation failures)

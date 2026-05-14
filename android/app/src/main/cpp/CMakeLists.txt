# Add this at the very top to integrate vcpkg without triggering the AGP sysroot bug
set(VCPKG_TARGET_TRIPLET "arm64-android")
include("/Users/lorengaravaglia/projects/vcpkg/scripts/buildsystems/vcpkg.cmake")

cmake_minimum_required(VERSION 3.22.1)

project("xemu")

# Ensure ANDROID and XBOX are defined
add_definitions(-DANDROID=1)
add_definitions(-DXBOX=1)
add_definitions(-DCONFIG_XEMU=1)
add_definitions(-DCONFIG_SLIRP=1)

# These must match the Meson build's config-host.h so that the DisplayType enum
# has the same numeric values in both CMake-compiled and Meson-compiled code.
# Meson defines CONFIG_SDL and CONFIG_OPENGL for the arm64-android build, which
# inserts DISPLAY_TYPE_SDL and DISPLAY_TYPE_EGL_HEADLESS before DISPLAY_TYPE_XEMU.
# Without these, CMake sees DISPLAY_TYPE_XEMU=2 but Meson sees it as 4, causing
# qemu_display_register() to write to the wrong dpys[] slot.
add_definitions(-DCONFIG_SDL=1)
add_definitions(-DCONFIG_OPENGL=1)
add_definitions(-DCONFIG_VULKAN=1)

# CONFIG_GIO must match Meson's build/config-host.h (which defines CONFIG_GIO).
# audio_int.h adds a set_dbus_server field to struct audio_driver when CONFIG_GIO
# is defined, shifting pcm_ops from offset 24 to offset 32. Without this define,
# CMake-compiled aaudiosdk.c puts pcm_ops at offset 24, but Meson-compiled
# audio.c reads it at offset 32 (finding max_voices_out=1 instead of &aaudio_pcm_ops),
# then dereferences 1+0x68=0x69 → SIGSEGV.
add_definitions(-DCONFIG_GIO=1)

# Find necessary packages first to get include dirs
find_package(ZLIB REQUIRED)
find_package(OpenSSL REQUIRED)
find_package(SDL3 CONFIG REQUIRED)
find_package(PNG REQUIRED)

include(FindPkgConfig)
pkg_check_modules(GLIB2 REQUIRED glib-2.0 gio-2.0 gthread-2.0)
pkg_check_modules(PIXMAN REQUIRED pixman-1)
pkg_check_modules(EPOXY REQUIRED epoxy)
pkg_check_modules(SAMPLERATE REQUIRED samplerate)

set(XEMU_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../..")

# Include directories
include_directories(
    .
    generated
    ${XEMU_ROOT}/include
    ${XEMU_ROOT}/linux-user/aarch64
    ${XEMU_ROOT}/host/include/aarch64
    ${XEMU_ROOT}/host/include/generic
    ${XEMU_ROOT}/target/i386
    ${XEMU_ROOT}/qapi
    ${XEMU_ROOT}
    ${XEMU_ROOT}/build
    ${XEMU_ROOT}/subprojects/slirp/src
    ${XEMU_ROOT}/build/subprojects/slirp
    ${XEMU_ROOT}/subprojects/imgui
    ${XEMU_ROOT}/subprojects/implot
    ${XEMU_ROOT}/subprojects/tomlplusplus/include
    ${XEMU_ROOT}/subprojects/xxHash-0.8.3
    ${XEMU_ROOT}/ui/thirdparty/stb_image
    ${GLIB2_INCLUDE_DIRS}
    ${PIXMAN_INCLUDE_DIRS}
    ${EPOXY_INCLUDE_DIRS}
    ${SAMPLERATE_INCLUDE_DIRS}
)

# Core xemu shared library for Android
add_library(xemu SHARED
    NativeInterface.cpp
    NativeInput.cpp
    xemu_android.c
    ${CMAKE_CURRENT_SOURCE_DIR}/coroutine_android_asm.c
    ${XEMU_ROOT}/util/qemu-coroutine.c
    ${XEMU_ROOT}/util/qemu-coroutine-lock.c
    ${XEMU_ROOT}/util/qemu-coroutine-io.c
    ${XEMU_ROOT}/util/qemu-coroutine-sleep.c
    ${CMAKE_CURRENT_SOURCE_DIR}/xemu_hud_stub.c # Include stubbed HUD for Android
    ${XEMU_ROOT}/ui/xemu.c
    ${XEMU_ROOT}/ui/xemu-monitor.c
    ${XEMU_ROOT}/ui/xemu-input.c
    ${XEMU_ROOT}/ui/xemu-snapshots.c
    ${XEMU_ROOT}/ui/xemu-widescreen.c
    ${XEMU_ROOT}/ui/xemu-net.c
    ${XEMU_ROOT}/ui/xemu-data.c
    ${CMAKE_CURRENT_SOURCE_DIR}/xemu_os_utils_android.c # Android-specific OS utilities
    ${XEMU_ROOT}/audio/aaudiosdk.c                      # AAudio backend (NDK, registered explicitly)
)


# CRITICAL: Disable all hardening for the core logic
target_compile_options(xemu PRIVATE
    -mbranch-protection=none
    -fno-sanitize=shadow-call-stack
    -O2
)

# Find necessary libraries
find_library(ANDROID_LIB android REQUIRED)
find_library(LOG_LIB log REQUIRED)
find_library(JNIGRAPHICS_LIB jnigraphics REQUIRED)
find_library(GLESV3_LIB GLESv3 REQUIRED)
find_library(EGL_LIB EGL REQUIRED)
find_library(AAUDIO_LIB aaudio REQUIRED)
find_library(VULKAN_LIB vulkan REQUIRED)

# Link everything
target_link_libraries(xemu
    PRIVATE
    # Meson QEMU libraries are linked as archives (lazy loading). Only object
    # files whose symbols are actually needed get pulled in. This avoids pulling
    # in objects with unresolvable dependencies (e.g. virtio objects that need
    # CONFIG_VIRTIO symbols that were never compiled for our Android config).
    #
    # CMake-compiled sources (add_library above) are direct objects processed
    # before archives, so they win over any matching symbols in the archives.
    # --allow-multiple-definition handles: our patched sources vs archive originals,
    # and QEMU's intentional stub/real pairs within archives.
    -Wl,--allow-multiple-definition
    # All Meson QEMU libraries are linked with --whole-archive. QEMU's type
    # system requires every type in the hierarchy to be registered via
    # __attribute__((constructor)) before any type can be instantiated.
    # Abstract base types (device, bus, i2c-slave, smbus-device, event-loop-base,
    # vmstate-if, etc.) export no directly-called symbols, so lazy linking
    # silently drops their constructors and causes "missing parent type" aborts.
    # --whole-archive ensures every constructor runs at load time.
    #
    # libsystem.a is the only archive containing non-Xbox hardware objects
    # that have missing Android dependencies. Those are excluded at archive
    # creation time by build_android_libs.sh (vfio objects that depend on
    # backends/iommufd.c which fails to compile on the NDK). All other
    # archives contain only QEMU infrastructure or Xbox-specific code and
    # are safe to include unconditionally.
    -Wl,--whole-archive
    ${XEMU_ROOT}/build/libsystem.a
    ${XEMU_ROOT}/build/libcommon.a
    ${XEMU_ROOT}/build/libqom.a
    ${XEMU_ROOT}/build/libblock.a
    ${XEMU_ROOT}/build/libblockdev.a
    ${XEMU_ROOT}/build/libio.a
    ${XEMU_ROOT}/build/libauthz.a
    ${XEMU_ROOT}/build/libmigration.a
    ${XEMU_ROOT}/build/libqmp.a
    ${XEMU_ROOT}/build/libcrypto.a
    ${XEMU_ROOT}/build/libevent-loop-base.a
    ${XEMU_ROOT}/build/libhwcore.a
    ${XEMU_ROOT}/build/libchardev.a
    ${XEMU_ROOT}/build/libqemu-i386-softmmu.a
    ${XEMU_ROOT}/build/data/libpdata.fa
    ${XEMU_ROOT}/build/libqemuutil.a
    -Wl,--no-whole-archive
    ${XEMU_ROOT}/build/subprojects/imgui/libimgui.a
    ${XEMU_ROOT}/build/subprojects/implot/libimplot.a
    ${XEMU_ROOT}/build/subprojects/xxHash-0.8.3/libxxhash.a
    ${XEMU_ROOT}/build/subprojects/tomlplusplus/src/libtomlplusplus.a
    ${XEMU_ROOT}/build/subprojects/volk/libvolk.a
    ${XEMU_ROOT}/build/subprojects/glslang/libglslang.a
    ${XEMU_ROOT}/build/subprojects/glslang/libMachineIndependent.a
    ${XEMU_ROOT}/build/subprojects/glslang/libGenericCodeGen.a
    ${XEMU_ROOT}/build/subprojects/glslang/libOSDependent.a
    ${XEMU_ROOT}/build/subprojects/glslang/libglslang_default_resource_limits.a
    ${XEMU_ROOT}/build/subprojects/SPIRV-Reflect/libspirv_reflect_static.a
    ${XEMU_ROOT}/build/subprojects/berkeley-softfloat-3/libsoftfloat.a
    ${XEMU_ROOT}/build/subprojects/nv2a_vsh_cpu/libnv2a_vsh_emulator.a
    ${XEMU_ROOT}/build/subprojects/nv2a_vsh_cpu/libnv2a_vsh_disassembler.a
    ${XEMU_ROOT}/build/subprojects/nv2a_vsh_cpu/libnv2a_vsh_cpu.a
    ${XEMU_ROOT}/build/hw/xbox/nv2a/pgraph/thirdparty/liblibgloffscreen.a
    ${XEMU_ROOT}/build/hw/xbox/mcpx/apu/dsp/libdsp.a
    ${XEMU_ROOT}/build/thirdparty/libvma.a
    ${XEMU_ROOT}/build/ui/thirdparty/libstb_image.a
    ${XEMU_ROOT}/build/ui/thirdparty/libfpng.a
    ${XEMU_ROOT}/build/ui/thirdparty/libfatx.a
    ${XEMU_ROOT}/build/subprojects/slirp/libslirp.so
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${JNIGRAPHICS_LIB}
    ${GLESV3_LIB}
    ${EGL_LIB}
    ${AAUDIO_LIB}
    ${VULKAN_LIB}
    ZLIB::ZLIB
    OpenSSL::SSL
    OpenSSL::Crypto
    SDL3::SDL3
    PNG::PNG
    ${GLIB2_LIBRARIES}
    ${PIXMAN_LIBRARIES}
    ${EPOXY_LIBRARIES}
    ${SAMPLERATE_LIBRARIES}
    -latomic
)

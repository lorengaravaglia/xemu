#!/usr/bin/env bash
# fixup_archives.sh — Rebuild Meson archives and apply Android-specific fixups.
#
# Run this after any `ninja -C build` that regenerates libsystem.a or
# libqemu-i386-softmmu.a.  It is safe to run multiple times (llvm-ar d
# silently ignores members that are already absent).
#
# What it does:
#   1. Runs `ninja -C build` to ensure archives are up to date.
#   2. Converts libqemu-i386-softmmu.a from a fat thin archive to a slim
#      regular archive (own objects only) — required to avoid double
#      type_init() registration when CMake links it with --whole-archive.
#   3. Removes from libsystem.a:
#      a. CMake-compiled files (their Android-patched equivalents are compiled
#         directly by CMake and would double-register their QOM types).
#      b. Objects duplicated in other --whole-archive libraries (libqom.a,
#         libblock.a, etc.) — lld deduplicates thin-archive members by path,
#         but regular-archive copies look distinct, causing double registration.
#      c. Non-Xbox platform hardware with missing parents/interfaces on Android.
#      d. Other objects with Android-incompatible or missing dependencies.
#
# WARNING: Never apply step 2 (slim archive) to libsystem.a.  See CLAUDE.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$SCRIPT_DIR/build"
NDK_PATH='/Users/lorengaravaglia/Library/Android/sdk/ndk/29.0.14206865'
LLVM_AR="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"

# ── Sanity checks ─────────────────────────────────────────────────────────────

if [[ ! -x "$LLVM_AR" ]]; then
    echo "ERROR: llvm-ar not found at $LLVM_AR" >&2
    exit 1
fi

if [[ ! -d "$BUILD" ]]; then
    echo "ERROR: build/ directory not found — run rebuild_meson.sh first" >&2
    exit 1
fi

# ── Step 1: rebuild Meson archives ───────────────────────────────────────────

echo "==> Step 1: ninja -C build"
ninja -C "$BUILD"

# ── Step 2: slim libqemu-i386-softmmu.a ──────────────────────────────────────
# Replace the fat thin archive (which embeds references to objects from
# libsystem.a.p/ etc.) with a slim regular archive of only its own objects.

echo ""
echo "==> Step 2: slim libqemu-i386-softmmu.a"
SOFTMMU_A="$BUILD/libqemu-i386-softmmu.a"
SOFTMMU_P="$BUILD/libqemu-i386-softmmu.a.p"

if [[ ! -d "$SOFTMMU_P" ]]; then
    echo "ERROR: $SOFTMMU_P not found — ninja build may have failed" >&2
    exit 1
fi

rm -f "$SOFTMMU_A"
"$LLVM_AR" rcs "$SOFTMMU_A" "$SOFTMMU_P"/*.o
echo "    libqemu-i386-softmmu.a rebuilt from $(ls "$SOFTMMU_P"/*.o | wc -l | tr -d ' ') objects"

# ── Step 3a: remove CMake-compiled files from libsystem.a ────────────────────
# These are compiled directly by CMake with #if ANDROID patches and must not
# also come from Meson via --whole-archive.

echo ""
echo "==> Step 3a: removing CMake-compiled duplicates from libsystem.a"
"$LLVM_AR" d "$BUILD/libsystem.a" \
    ui_xemu-monitor.c.o \
    ui_xemu.c.o \
    ui_xemu-input.c.o \
    ui_xemu-snapshots.c.o \
    ui_xemu-widescreen.c.o \
    ui_xemu-net.c.o \
    ui_xemu-data.c.o \
    ui_xemu-os-utils-linux.c.o \
    ui_xui_main.cc.o \
    ui_xui_notifications.cc.o \
    2>/dev/null || true

# ── Step 3b: remove objects duplicated in other --whole-archive libraries ─────
# libsystem.a (as a regular archive after ninja) embeds copies of objects from
# libqom.a.p/, libio.a.p/, etc. — the same physical .o files that are already
# in libqom.a, libio.a, etc.  With --whole-archive on both, lld sees two copies
# and runs every type_init() constructor twice.  Remove all duplicates.

echo ""
echo "==> Step 3b: removing objects duplicated in other --whole-archive libraries"

OTHER_LIBS=(
    libqom.a libcommon.a libio.a libmigration.a libqmp.a
    libcrypto.a libevent-loop-base.a libhwcore.a libchardev.a
    libblock.a libblockdev.a libauthz.a libqemuutil.a
)

TMPDIR_WORK="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_WORK"' EXIT

# Collect basenames from all other archives
for lib in "${OTHER_LIBS[@]}"; do
    lib_path="$BUILD/$lib"
    if [[ -f "$lib_path" ]]; then
        "$LLVM_AR" t "$lib_path" | sed 's|.*/||'
    fi
done | sort -u > "$TMPDIR_WORK/other.txt"

# Collect basenames from libsystem.a
"$LLVM_AR" t "$BUILD/libsystem.a" | sed 's|.*/||' | sort -u > "$TMPDIR_WORK/sys.txt"

# Find intersection
comm -12 "$TMPDIR_WORK/other.txt" "$TMPDIR_WORK/sys.txt" > "$TMPDIR_WORK/dupes.txt"
DUPE_COUNT=$(wc -l < "$TMPDIR_WORK/dupes.txt" | tr -d ' ')

if [[ "$DUPE_COUNT" -gt 0 ]]; then
    echo "    removing $DUPE_COUNT duplicate objects..."
    xargs "$LLVM_AR" d "$BUILD/libsystem.a" < "$TMPDIR_WORK/dupes.txt" 2>/dev/null || true
else
    echo "    no duplicates found"
fi

# ── Step 3c: remove non-Xbox platform hardware ───────────────────────────────
# ARM interrupt controllers, Xilinx, BCM, Exynos, IMX — not needed for Xbox
# emulation and have missing parent/interface types on Android.

echo ""
echo "==> Step 3c: removing non-Xbox platform hardware objects"
"$LLVM_AR" d "$BUILD/libsystem.a" \
    hw_intc_arm_gic_common.c.o \
    hw_intc_arm_gic.c.o \
    hw_intc_allwinner-a10-pic.c.o \
    hw_intc_arm_gicv2m.c.o \
    hw_intc_arm_gicv3_its_common.c.o \
    hw_intc_aspeed_intc.c.o \
    hw_intc_aspeed_vic.c.o \
    hw_intc_bcm2835_ic.c.o \
    hw_intc_bcm2836_control.c.o \
    hw_intc_exynos4210_combiner.c.o \
    hw_intc_exynos4210_gic.c.o \
    hw_intc_goldfish_pic.c.o \
    hw_intc_heathrow_pic.c.o \
    hw_intc_imx_avic.c.o \
    hw_intc_imx_gpcv2.c.o \
    hw_intc_omap_intc.c.o \
    hw_intc_pl190.c.o \
    hw_intc_realview_gic.c.o \
    hw_intc_slavio_intctl.c.o \
    hw_intc_xilinx_intc.c.o \
    hw_intc_xlnx-pmu-iomod-intc.c.o \
    hw_intc_xlnx-zynqmp-ipi.c.o \
    hw_misc_arm_integrator_debug.c.o \
    hw_misc_arm_l2x0.c.o \
    hw_misc_arm_sysctl.c.o \
    hw_misc_arm11scu.c.o \
    hw_misc_armsse-cpu-pwrctrl.c.o \
    hw_misc_armsse-cpuid.c.o \
    hw_misc_armsse-mhu.c.o \
    hw_misc_armv7m_ras.c.o \
    hw_misc_xlnx-cfi-if.c.o \
    hw_misc_xlnx-versal-cframe-reg.c.o \
    hw_misc_xlnx-versal-cfu.c.o \
    hw_misc_xlnx-versal-pmc-iou-slcr.c.o \
    hw_misc_xlnx-versal-trng.c.o \
    hw_misc_xlnx-versal-xramc.c.o \
    hw_misc_xlnx-zynqmp-apu-ctrl.c.o \
    2>/dev/null || true

# ── Step 3d: remove virtio objects with removed parents or Android-incompatible deps ──

echo ""
echo "==> Step 3d: removing virtio objects with missing parents/deps"
"$LLVM_AR" d "$BUILD/libsystem.a" \
    hw_virtio_virtio-md-pci.c.o \
    hw_virtio_virtio-pci.c.o \
    hw_virtio_virtio-bus.c.o \
    hw_virtio_virtio-mmio.c.o \
    hw_virtio_vhost-user-vsock.c.o \
    hw_scsi_vhost-user-scsi.c.o \
    hw_char_virtio-console.c.o \
    hw_input_virtio-input.c.o \
    hw_input_virtio-input-host.c.o \
    hw_audio_virtio-snd.c.o \
    hw_audio_virtio-snd-pci.c.o \
    hw_virtio_virtio-mem.c.o \
    hw_virtio_virtio-pmem.c.o \
    hw_s390x_virtio-ccw-gpu.c.o \
    hw_vmapple_virtio-blk.c.o \
    hw_scsi_virtio-scsi-dataplane.c.o \
    2>/dev/null || true

# ── Step 3e: remove other objects with missing/Android-incompatible deps ──────

echo ""
echo "==> Step 3e: removing objects with other missing dependencies"
"$LLVM_AR" d "$BUILD/libsystem.a" \
    hw_remote_message.c.o \
    hw_remote_proxy.c.o \
    hw_remote_remote-obj.c.o \
    semihosting_arm-compat-semi.c.o \
    fsdev_qemu-fsdev.c.o \
    hw_gpio_omap_gpio.c.o \
    hw_i2c_omap_i2c.c.o \
    hw_sd_omap_mmc.c.o \
    hw_dma_omap_dma.c.o \
    hw_vfio-user_pci.c.o \
    hw_vfio-user_container.c.o \
    hw_vfio_cpr-load.c.o \
    hw_vfio_cpr-save.c.o \
    hw_vfio_device.c.o \
    hw_vfio_region.c.o \
    hw_vfio_migration-multifd.c.o \
    hw_vfio_migration-multifd-load.c.o \
    hw_vfio_migration.c.o \
    hw_vfio_display.c.o \
    hw_acpi_ich9.c.o \
    hw_acpi_ich9_tco.c.o \
    hw_acpi_ich9_timer.c.o \
    hw_intc_openpic.c.o \
    hw_hyperv_hv-balloon.c.o \
    hw_char_sclpconsole.c.o \
    hw_char_sclpconsole-lm.c.o \
    hw_block_xen-block.c.o \
    hw_core_guest-loader.c.o \
    migration_vfio.c.o \
    2>/dev/null || true

# ── Done ─────────────────────────────────────────────────────────────────────

echo ""
FINAL_COUNT=$("$LLVM_AR" t "$BUILD/libsystem.a" | wc -l | tr -d ' ')
echo "==> Done. libsystem.a now contains $FINAL_COUNT objects."
echo "    Rebuild the Android APK in Android Studio (Build → Clean → Rebuild)."

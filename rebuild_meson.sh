#!/bin/bash
export NDK_PATH='/Users/lorengaravaglia/Library/Android/sdk/ndk/29.0.14206865'
export TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export ANDROID_NDK_ROOT="$NDK_PATH"

# Add toolchain to path
export PATH="$TOOLCHAIN:$PATH"

# Clean build directory
rm -rf build/
mkdir build

# Generate minimal config-host.mak required by meson.build
cat > build/config-host.mak <<EOF
SRC_PATH=$(pwd)
TARGET_DIRS=i386-softmmu
CONFIG_XEMU=y
CONFIG_XBOX=y
CONFIG_LINUX=y
CONFIG_POSIX=y
CONFIG_ANDROID=y
CONFIG_AARCH64=y
CONFIG_SLIRP=y
CONFIG_TCG=y
GDB=gdb
PYTHON=python3
MESON=meson
NINJA=ninja
CC=aarch64-linux-android28-clang
CXX=aarch64-linux-android28-clang++
AR=llvm-ar
NM=llvm-nm
STRIP=llvm-strip
PKG_CONFIG=pkg-config
CFLAGS=-fPIC -DXBOX=1 -mbranch-protection=none -fno-sanitize=shadow-call-stack
GENISOIMAGE=
EOF

# Use the cross-file which now includes both Android and Native (FOR_BUILD) settings
meson setup build \
    --cross-file android_arm64.txt \
    --buildtype=release \
    -Dguest_agent=disabled \
    -Dtools=disabled \
    -Ddocs=disabled \
    -Dgettext=disabled \
    -Dgtk=disabled \
    -Dvirtfs=disabled \
    -Dvnc=disabled \
    -Dspice=disabled \
    -Dsmartcard=disabled \
    -Dusb_redir=disabled \
    -Dlibusb=disabled \
    -Dcapstone=disabled \
    -Dslirp=enabled \
    -Dopengl=enabled \
    -Dsdl=enabled \
    -Dkvm=disabled \
    -Dhvf=disabled \
    -Dwhpx=disabled \
    -Dnvmm=disabled \
    -Dxen=disabled \
    -Dmultiprocess=disabled \
    -Dvfio_user_server=disabled \
    -Ddbus_display=disabled \
    -Dcoroutine_backend=sigaltstack \
    -Dtpm=disabled \
    -Dvalgrind=disabled \
    -Digvm=disabled \
    -Dalsa=disabled \
    -Dpa=disabled \
    -Dsndio=disabled \
    -Doss=disabled \
    -Djack=disabled \
    -Dcoreaudio=disabled \
    -Ddsound=disabled \
    -Dpipewire=disabled

# Build only the static library targets needed by CMakeLists.txt.
# Do NOT run plain "ninja -C build" — it will also try to link qemu-system-i386
# as an Android executable, which fails (no main(), no EGL in sysroot).
bash "$(dirname "$0")/build_android_libs.sh"

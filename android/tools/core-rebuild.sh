#!/bin/zsh
# Recompile the whole Meson core after a compiler-flag change, and relink the APK.
#
#   android/tools/core-rebuild.sh [use|generate|none]
#
#   use       (default) the flags in android_arm64.txt: PGO from pgo/xemu.profdata
#   generate  instrumented for a new profile (-fprofile-generate); then run
#             android/tools/pgo-collect.sh on the Thor
#   none      no PGO (for an A/B against "use")
#
# Editing android_arm64.txt does not change an existing build dir (Meson copied
# c_args at setup), so this script sets c_args/cpp_args with `meson configure`,
# recompiles every object, swaps the members of the regular archives libsystem.a
# and libcommon.a in place, rebuilds the slim libqemu-i386-softmmu.a, and relinks
# (FINDINGS CH, CN).  ~10 min.  Expect 41 failed objects: 9p, vfio-user, TPM,
# libuser's translate-all and upstream's PTIMER and vsh-carryover unit tests,
# none of which the app links.
set -u
MODE=${1:-use}
ROOT=${0:A:h:h:h}
cd $ROOT
NDK=/Users/lorengaravaglia/Library/Android/sdk/ndk/29.0.14206865
B=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
export ANDROID_NDK_ROOT=$NDK PATH="$B:$PATH"
LOG=build/core-rebuild.log

# The cross-file's flags with any PGO flags removed, then this mode's.
ARGS=$(python3 - "$MODE" "$ROOT" <<'EOF'
import re, sys
mode, root = sys.argv[1], sys.argv[2]
src = open(f'{root}/android_arm64.txt').read()
flags = re.findall(r"'([^']*)'", re.search(r'^c_args = \[(.*)\]$', src, re.M).group(1))
flags = [f for f in flags if not re.match(r'-fprofile-|-Wno-profile-|-Wno-backend-plugin', f)]
if mode == 'use':
    flags += [f'-fprofile-use={root}/pgo/xemu.profdata', '-Wno-profile-instr-unprofiled',
              '-Wno-profile-instr-out-of-date', '-Wno-backend-plugin']
elif mode == 'generate':
    flags += ['-fprofile-generate']
elif mode != 'none':
    sys.exit(f'unknown mode {mode}')
print('[' + ', '.join(f"'{f}'" for f in flags) + ']')
EOF
) || exit 1
echo "c_args = $ARGS"
meson configure build -Dc_args="$ARGS" -Dcpp_args="$ARGS" > $LOG 2>&1 ||
    { echo "meson configure failed (see $LOG)"; exit 1; }

# CMakeLists.txt links the profile runtime while this marker exists. CMake reads
# it only when it configures, so touch CMakeLists.txt to make Gradle reconfigure.
if [[ $MODE == generate ]]; then touch build/pgo-generate; else rm -f build/pgo-generate; fi
touch android/app/src/main/cpp/CMakeLists.txt

ninja -C build -t targets all | awk -F': ' '$1 ~ /\.o$/ {print $1}' | sort -u > build/core-objs.txt
echo "objects: $(wc -l < build/core-objs.txt)"
xargs -n 2000 ninja -C build -k 0 < build/core-objs.txt >> $LOG 2>&1
echo "failed objects: $(grep -c '^FAILED' $LOG) (41 expected)"

for lib in libsystem libcommon; do
  $B/llvm-ar t build/$lib.a | sed "s|^|build/$lib.a.p/|" | xargs $B/llvm-ar r build/$lib.a
  echo "$lib.a members: $($B/llvm-ar t build/$lib.a | wc -l)"
done
rm -f build/libqemu-i386-softmmu.a
$B/llvm-ar rcs build/libqemu-i386-softmmu.a build/libqemu-i386-softmmu.a.p/*.o
echo "libqemu-i386-softmmu.a members: $($B/llvm-ar t build/libqemu-i386-softmmu.a | wc -l)"

find android/app/build -iname libxemu.so -delete
(cd android && ./gradlew assembleDebug -q 2>&1 | grep -vE '^w:|warning' | tail -5)
ls -la android/app/build/outputs/apk/debug/app-debug.apk

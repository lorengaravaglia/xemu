#!/bin/zsh
# Collect a PGO profile on the Thor and merge it into pgo/xemu.profdata.
#
#   android/tools/core-rebuild.sh generate    # instrumented APK
#   android/tools/pgo-collect.sh              # this: install, play, pull, merge
#   android/tools/core-rebuild.sh use         # optimised APK from the new profile
#
# Workload (C6, FINDINGS CN): Halo's Quick resume, benchmark slots 1/5/7 twice,
# then ~80 s cold boots of Halo 2, JSRF and Jade Empire. The instrumented build
# writes its counters every 10 s (pgo_dump in pgraph/vk/texture.c), one
# .profraw per process, because the app never exits normally.
# Needs the library in grid view at the usual card positions, the benchmark
# slots present, and the screen awake. Turns bench_autocapture off for the run
# and restores it.
set -u
ROOT=${0:A:h:h:h}
ADB=~/Library/Android/sdk/platform-tools/adb
DEV=${DEV:-192.168.1.193:5555}
B=/Users/lorengaravaglia/Library/Android/sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/darwin-x86_64/bin
OUT=$ROOT/build/pgo-raw
a(){ $ADB -s $DEV "$@"; }
launch(){  # x y: tap a library card on the lower screen
  a shell am force-stop com.boxxy; a shell input keyevent KEYCODE_WAKEUP
  a shell am start -c android.intent.category.LAUNCHER -a android.intent.action.MAIN \
      -f 0x10200000 -n com.boxxy/.MainActivity >/dev/null
  sleep 6; a shell input keyevent KEYCODE_WAKEUP; a shell input -d 4 tap $1 $2
}

[[ -f $ROOT/build/pgo-generate ]] ||
    { echo "build is not instrumented: run core-rebuild.sh generate first"; exit 1; }
a shell am force-stop com.boxxy
a install -r $ROOT/android/app/build/outputs/apk/debug/app-debug.apk | tail -1
a shell run-as com.boxxy rm -rf files/pgo
PREF=shared_prefs/main_prefs.xml
a shell "run-as com.boxxy sed -i 's/name=\"bench_autocapture\" value=\"true\"/name=\"bench_autocapture\" value=\"false\"/' $PREF"

launch 322 80; sleep 40    # Halo: Resume
for s in 1 5 7 1 5 7; do
  a shell input keyevent KEYCODE_WAKEUP
  a shell "am broadcast -a com.boxxy.action.BENCHMARK -p com.boxxy --ei slot $s --ei frames 450 >/dev/null"
  sleep 45
done
sleep 12                   # one more dump
for card in "215 850" "560 900" "627 300"; do   # Halo 2, JSRF, Jade Empire
  launch ${=card}; sleep 40; a shell input keyevent KEYCODE_WAKEUP; sleep 40
done
a shell am force-stop com.boxxy
a shell "run-as com.boxxy sed -i 's/name=\"bench_autocapture\" value=\"false\"/name=\"bench_autocapture\" value=\"true\"/' $PREF"

rm -rf $OUT; mkdir -p $OUT
for f in $(a shell run-as com.boxxy ls files/pgo); do
  a exec-out run-as com.boxxy cat files/pgo/$f > $OUT/$f
done
ls -la $OUT
$B/llvm-profdata merge -o $ROOT/pgo/xemu.profdata $OUT/*.profraw &&
    $B/llvm-profdata show $ROOT/pgo/xemu.profdata | head -3
a shell run-as com.boxxy rm -rf files/pgo

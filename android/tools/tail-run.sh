#!/bin/bash
# One tail-benchmark run (FINDINGS AP): fresh launch, load a slot, replay a
# recording, benchmark N frames, save the full log.  Every step is verified;
# a run that did not engage says so instead of producing a plausible log.
#
# Usage: tail-run.sh OUTFILE [slot=7] [recording=walk_fps_dip|none] [frames=400]
#   SETTLE=seconds after the load before replay/benchmark (default 9)
#
# Slot 7's dip is the state it loads into (a full-screen effect); walking into
# the health pack ENDS it.  To measure the dip itself: recording "none",
# SETTLE=1.5 -- past the two one-off reload hitches, inside the effect.
set -u
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s ${ADB_SERIAL:-192.168.1.193:5555}"
OUT=$1; SLOT=${2:-7}; REC=${3:-walk_fps_dip}; FRAMES=${4:-400}

$ADB shell input keyevent KEYCODE_WAKEUP
$ADB shell svc power stayon true
$ADB shell am force-stop com.boxxy; sleep 2
$ADB shell "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n com.boxxy/.MainActivity >/dev/null"
sleep 6
P=""
for t in 1 2 3; do
    $ADB shell input tap 250 600; sleep 8
    P=$($ADB shell pidof com.boxxy:EmulationProcess | tr -d '\r'); [ -n "$P" ] && break
done
[ -z "$P" ] && { echo "LAUNCH FAILED"; exit 1; }
sleep 8

$ADB logcat -c
$ADB shell am broadcast -a com.boxxy.action.LOAD_STATE --ei slot "$SLOT" >/dev/null
sleep "${SETTLE:-9}"
$ADB logcat -d | grep -q "loadstate: loaded slot $SLOT" || { echo "LOAD FAILED"; exit 1; }
if [ "$REC" != "none" ]; then
    $ADB shell am broadcast -a com.boxxy.action.PLAY_RECORDING --es recording_name "$REC" >/dev/null; sleep 2
    $ADB logcat -d | grep -q "PLAYBACK_START" || { echo "REPLAY DID NOT START"; exit 1; }
fi
$ADB shell am broadcast -a com.boxxy.action.BENCHMARK --ei frames "$FRAMES" >/dev/null
for i in $(seq 90); do $ADB logcat -d | grep -q "bench: RESULT" && break; sleep 2; done
sleep 2
$ADB logcat -d -v time > "$OUT"
grep -q "bench: RESULT" "$OUT" || { echo "BENCH DID NOT FINISH"; exit 1; }
grep -E "bench: (RESULT|FLOOR)" "$OUT" | sed -E 's/.*bench: //' | cut -c1-160

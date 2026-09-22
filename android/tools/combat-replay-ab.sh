#!/bin/bash
# Deterministic heavy-combat A/B: identical save state + identical replayed
# input under each config.  This is the harness section AL said was missing --
# slot 5 alone lets the guest idle, so savings vanish into the clock spin.
adb() { "$HOME/Library/Android/sdk/platform-tools/adb" -s 192.168.1.193:5555 "$@"; }
SLOT="${SLOT:-5}"; REC="${REC:-combat2}"; FRAMES="${FRAMES:-800}"
OUT=/Users/lorengaravaglia/.claude/jobs/16b69e39/tmp/replay_results.txt
run() {
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  adb shell setprop debug.xemu.ldapr $1
  adb shell am force-stop com.boxxy; adb logcat -c; sleep 2
  adb shell "am start -n com.boxxy/.MainActivity >/dev/null 2>&1"; sleep 7
  local P=""
  for t in 1 2 3 4; do
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    adb shell input tap 250 600; sleep 8
    P=$(adb shell pidof com.boxxy:EmulationProcess | tr -d '\r'); [ -n "$P" ] && break
  done
  [ -z "$P" ] && { echo "ldapr=$1 LAUNCH FAILED" | tee -a $OUT; return; }
  sleep 14
  # known state, settle, then replay input and measure over the same window
  adb shell "am broadcast -a com.boxxy.action.LOAD_STATE --ei slot $SLOT >/dev/null 2>&1"
  sleep 9
  adb shell "am broadcast -a com.boxxy.action.PLAY_RECORDING --es recording_name $REC >/dev/null 2>&1"
  sleep 2
  # Verify the replay actually engaged.  PLAY_RECORDING fails silently to the
  # log -- a wrong extra name gives PLAYBACK_REJECTED and the guest simply
  # idles, which reads as a believable light workload rather than an error.
  # Two complete A/B runs were measured that way before anyone noticed.
  if ! adb logcat -d 2>/dev/null | grep -q "PLAYBACK_START"; then
    echo "ldapr=$1 ABORT: replay did not start -- $(adb logcat -d 2>/dev/null | grep -oE 'PLAYBACK_REJECTED reason=[a-z_]+' | tail -1)" | tee -a $OUT
    adb shell am force-stop com.boxxy
    return
  fi
  adb shell "am broadcast -a com.boxxy.action.BENCHMARK --ei frames $FRAMES >/dev/null 2>&1"
  for i in $(seq 1 50); do adb logcat -d 2>/dev/null | grep -q "bench: RESULT" && break; sleep 3; done
  local L=$(adb logcat -d 2>/dev/null)
  local V=$(echo "$L" | grep "bench: RESULT" | tail -1 | grep -oE "vcpu [0-9.]+" | awk '{print $2}')
  local F=$(echo "$L" | grep "bench: RESULT" | tail -1 | grep -oE "\([0-9.]+ fps" | tr -d '(' | awk '{print $1}')
  local O5=$(echo "$L" | grep "bench: FLOOR" | tail -1 | grep -oE "20fps\): [0-9]+" | grep -oE "[0-9]+$")
  local O6=$(echo "$L" | grep "bench: FLOOR" | tail -1 | grep -oE "15fps\): [0-9]+" | grep -oE "[0-9]+$")
  local MN=$(echo "$L" | grep "bench: HIST" | tail -1 | grep -oE "mean [0-9.]+" | awk '{print $2}')
  local PB=$(echo "$L" | grep -c "PLAYBACK_START\|playback")
  printf "ldapr=%s mean %-6s vcpu %-6s fps %-6s over50 %-5s over66 %-4s\n" \
     "$1" "${MN:-?}" "${V:-?}" "${F:-?}" "${O5:-?}" "${O6:-?}" | tee -a $OUT
  echo "$L" | grep "bench: HIST" | tail -1 | sed 's/.*bench: /   /' | cut -c1-140 | tee -a $OUT
  adb shell am force-stop com.boxxy; sleep 25
}
: > $OUT
for cfg in "$@"; do run $cfg; done
# Leave the knob to the Advanced-settings toggle rather than pinning it off:
# a leftover property silently overrides the UI on the next run.
adb shell setprop debug.xemu.ldapr 1

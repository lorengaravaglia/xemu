#!/bin/bash
# Compare Vulkan drivers on the deterministic slot-5 benchmark.
#
# The metric that decides fps is `vcpu ms/frame` from the emulator's own
# instrumentation -- NOT average fps (section: Halo paces its own engine).
# Driver CPU shows up in total process CPU, which is the thermal question.
ADB="$HOME/Library/Android/sdk/platform-tools/adb"; DEV=192.168.1.193:5555
adb() { "$ADB" -s $DEV "$@"; }
FRAMES=1200
OUT=/Users/lorengaravaglia/.claude/jobs/16b69e39/tmp/turnip_results.txt

set_driver() {   # $1 = zip basename, or "none"
  adb shell am force-stop com.boxxy; sleep 2
  if [ "$1" = "none" ]; then
    adb shell "run-as com.boxxy sed -i 's|<boolean name=\"driver_enabled\" value=\"true\" />|<boolean name=\"driver_enabled\" value=\"false\" />|' shared_prefs/main_prefs.xml"
    return
  fi
  local zip="/sdcard/Download/$1"
  local lib=$(adb shell "unzip -p '$zip' meta.json 2>/dev/null" | tr -d '\r' | grep -o '"libraryName"[^,}]*' | cut -d'"' -f4)
  adb shell "rm -rf /data/local/tmp/drv && mkdir -p /data/local/tmp/drv && unzip -o -j '$zip' -d /data/local/tmp/drv >/dev/null 2>&1 && chmod -R 755 /data/local/tmp/drv"
  adb shell "run-as com.boxxy sh -c 'rm -rf files/driver && mkdir -p files/driver && cp /data/local/tmp/drv/$lib files/driver/'"
  adb shell "run-as com.boxxy sed -i \
    -e 's|<string name=\"driver_name\">[^<]*</string>|<string name=\"driver_name\">$lib</string>|' \
    -e 's|<boolean name=\"driver_enabled\" value=\"false\" />|<boolean name=\"driver_enabled\" value=\"true\" />|' \
    shared_prefs/main_prefs.xml"
}

run_bench() {    # $1 = label
  adb logcat -c
  adb shell "am start -n com.boxxy/.MainActivity >/dev/null 2>&1"; sleep 6
  adb shell input tap 250 600
  local P=""
  for i in $(seq 1 30); do sleep 2; P=$(adb shell pidof com.boxxy:EmulationProcess | tr -d '\r'); [ -n "$P" ] && break; done
  [ -z "$P" ] && { echo "$1 | LAUNCH FAILED" | tee -a $OUT; return; }
  sleep 20
  local drv=$(adb logcat -d 2>/dev/null | grep -c "Custom Vulkan driver loaded")
  local fell=$(adb logcat -d 2>/dev/null | grep -c "falling back to system Vulkan")
  local T0=$(adb shell "cat /proc/$P/stat 2>/dev/null | awk '{print \$14+\$15}'" | tr -d '\r')
  adb shell "am broadcast -a com.boxxy.action.BENCHMARK --ei slot 5 --ei frames $FRAMES >/dev/null 2>&1"
  local t0=$(date +%s)
  for i in $(seq 1 60); do adb logcat -d 2>/dev/null | grep -q "bench: RESULT" && break; sleep 3; done
  local t1=$(date +%s)
  local T1=$(adb shell "cat /proc/$P/stat 2>/dev/null | awk '{print \$14+\$15}'" | tr -d '\r')
  local R=$(adb logcat -d 2>/dev/null | grep "bench: RESULT" | tail -1)
  local ms=$(echo "$R" | grep -o 'vcpu [0-9.]* ms/frame' | awk '{print $2}')
  local fps=$(echo "$R" | grep -o '([0-9.]* fps' | tr -d '(' | awk '{print $1}')
  local temp=$(adb shell dumpsys battery 2>/dev/null | grep temperature | awk '{print $2/10}')
  local cpu=$(awk -v a="$T0" -v b="$T1" -v s=$((t1-t0)) 'BEGIN{if(s>0)printf "%.0f", 100*(b-a)/100/s; else print "?"}')
  local crash=$(adb logcat -d 2>/dev/null | grep -cE "FATAL|SIGSEGV")
  printf "%-34s vcpu %-6s ms  fps %-6s  procCPU %-4s%%  %sC  drv=%s fallback=%s crash=%s\n" \
    "$1" "${ms:-?}" "${fps:-?}" "${cpu:-?}" "${temp:-?}" "$drv" "$fell" "$crash" | tee -a $OUT
  adb shell am force-stop com.boxxy; sleep 8
}

: > $OUT
echo "=== driver comparison, slot 5, $FRAMES frames ===" | tee -a $OUT
for pass in 1 2; do
  echo "--- pass $pass ---" | tee -a $OUT
  for d in none Turnip_v26.2.0_R4.zip Turnip_v26.3.0-R3.zip Turnip_v26.3.0-R5.zip \
           turnip_mrpurple_T23-toasted.adpkg.zip turnip_mrpurple_T24-toasted.adpkg.zip \
           turnip_mrpurple_T30-toasted.adpkg.zip Qualcomm_840_adpkg.zip; do
    set_driver "$d"
    run_bench "$(echo $d | sed 's/\.adpkg\.zip//;s/\.zip//')"
  done
done

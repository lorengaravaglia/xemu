#!/bin/sh
# Neutral emulator benchmark -- same instrument for any app, debuggable or not.
#
# Reports CPU only.  SurfaceFlinger frame timing is deliberately NOT used:
# it counts panel presentations (~60/s, each 30fps guest frame shown twice),
# not emulated frames, so it cannot discriminate between emulators.  Take the
# emulated fps from each app's own on-screen overlay and combine:
#
#     vcpu ms/frame = busiest_thread_pct / 100 * 1000 / emulated_fps
#
# The busiest thread is the vCPU (~68% of process cycles in every profile
# taken here).  Validated against our own benchmark: this reports 96% busiest
# where the in-process benchmark reported 98% util.
ADB="$HOME/Library/Android/sdk/platform-tools/adb"; DEV=192.168.1.193:5555
PKG="$1"; SECS="${2:-15}"
PID=$($ADB -s $DEV shell "pidof $PKG" | tr -d '\r' | awk '{print $1}')
[ -z "$PID" ] && { echo "  $PKG NOT RUNNING"; exit 1; }
T0=$($ADB -s $DEV shell "cat /proc/$PID/stat | awk '{print \$14+\$15}'" | tr -d '\r')
sleep "$SECS"
T1=$($ADB -s $DEV shell "cat /proc/$PID/stat | awk '{print \$14+\$15}'" | tr -d '\r')
$ADB -s $DEV shell "top -H -b -n 1 -p $PID 2>/dev/null" | tr -d '\r' \
  | awk -v pkg="$PKG" -v t0="$T0" -v t1="$T1" -v secs="$SECS" '
      $1 ~ /^[0-9]+$/ { cpu[n++]=$9+0 }
      END {
        for (i=0;i<n;i++) for (j=i+1;j<n;j++) if (cpu[j]>cpu[i]) { t=cpu[i];cpu[i]=cpu[j];cpu[j]=t }
        printf "  %-26s process %3.0f%% of a core | vCPU thread %3.0f%% | 2nd %3.0f%% | 3rd %3.0f%%\n",
               pkg, 100*(t1-t0)/100/secs, cpu[0], cpu[1], cpu[2]
        printf "  %-26s at 30 fps that is %.2f ms/frame; divide 10*%.0f by your overlay fps\n",
               "", cpu[0]/100*1000/30, cpu[0]
      }'

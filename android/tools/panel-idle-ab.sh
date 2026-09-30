#!/bin/bash
# D3 measurement: what does the lower panel cost while a game plays?
#
# Interleaved A/B/C inside ONE emulator process (see CLAUDE.md measurement
# rules): the panel is forced awake / dim / blank with the PANEL_SLEEP debug
# broadcast, a warmup is discarded after every switch, then battery power,
# CPU temperature and panel backlight are sampled.  The order rotates each
# round so thermal drift does not land on one mode.
#
# The device must be UNPLUGGED: while charging, current_now is the charger's
# story, not the load's.  The script refuses to run otherwise.
#
# Usage: panel-idle-ab.sh [rounds=4] [slot=7]
set -u
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s ${ADB_SERIAL:-192.168.1.193:5555}"
ROUNDS=${1:-4}
SLOT=${2:-7}
WARM=${WARM:-20}
SAMPLE=${SAMPLE:-40}
PS=/sys/class/power_supply/battery

if [ -z "${DRY_RUN:-}" ] && { [ "$($ADB shell cat $PS/status | tr -d '\r')" = "Charging" ] ||
   [ "$($ADB shell cat /sys/class/power_supply/usb/online | tr -d '\r')" = "1" ]; }; then
    echo "device is on USB power -- unplug it first" >&2
    exit 1
fi

$ADB shell am broadcast -a com.boxxy.action.LOAD_STATE --ei slot "$SLOT" >/dev/null
sleep 8

names=(awake dim blank)
order=(0 1 2)
for ((r = 1; r <= ROUNDS; r++)); do
    for m in "${order[@]}"; do
        $ADB shell am broadcast -a com.boxxy.action.PANEL_SLEEP --ei mode "$m" >/dev/null
        sleep $WARM
        # A benchmark over the sample window gives fps and vCPU cost.
        $ADB logcat -c
        $ADB shell am broadcast -a com.boxxy.action.BENCHMARK --ei frames $((SAMPLE * 30)) >/dev/null
        # One device-side loop, so adb round trips do not set the sample rate.
        out=$($ADB shell "
            p=0; t=0; n=0
            i=0
            while [ \$i -lt $SAMPLE ]; do
                c=\$(cat $PS/current_now); v=\$(cat $PS/voltage_now)
                p=\$((p + (c / 1000) * (v / 1000) / 1000))
                s=0; k=0
                for z in /sys/class/thermal/thermal_zone3[5-9] /sys/class/thermal/thermal_zone4[0-5]; do
                    s=\$((s + \$(cat \$z/temp))); k=\$((k + 1))
                done
                t=\$((t + s / k)); n=\$((n + 1))
                sleep 1; i=\$((i + 1))
            done
            echo \$((p / n)) \$((t / n)) \$(cat /sys/class/backlight/panel1-backlight/brightness)
        ")
        read -r mw mc bl <<<"$(echo "$out" | tr -d '\r')"
        res=""
        for _ in $(seq 30); do
            res=$($ADB shell "logcat -d -s xemu-android:I | grep 'bench: RESULT'" | tr -d '\r')
            [ -n "$res" ] && break
            sleep 1
        done
        fps=$(echo "$res" | sed -nE 's/.*\(([0-9.]+) fps.*/\1/p')
        vcpu=$(echo "$res" | sed -nE 's/.*vcpu ([0-9.]+) ms.*/\1/p')
        printf "round %d  %-5s  power %6s mW  cpu %5s m°C  backlight %4s  fps %s  vcpu %s ms\n" \
               "$r" "${names[$m]}" "$mw" "$mc" "$bl" "${fps:-?}" "${vcpu:-?}"
    done
    order=("${order[@]:1}" "${order[0]}")
done
$ADB shell am broadcast -a com.boxxy.action.PANEL_SLEEP --ei mode 0 >/dev/null

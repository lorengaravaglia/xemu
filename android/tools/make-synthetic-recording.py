#!/usr/bin/env python3
"""Generate a repeatable input recording for performance measurement.

A hand-played recording drifts: replay delivers events on a wall-clock
schedule, so if the two configurations run at different speeds the inputs land
on different guest frames and the player ends up somewhere else. Synthetic
input avoids that -- rotation plus forward describes a closed circular path,
so position stays bounded however the timing shifts.

What this can and cannot do, measured (FINDINGS AL):

  repeatability  comes from the input, and it works: two runs of the same
                 state matched at 6/6 frames over 50 ms with near-identical
                 histograms.
  intensity      comes from the SAVE STATE, not the input.  The same synthetic
                 input is light from one state and heavy from another, and no
                 amount of stick-waggling changes that.  Pair it with a state
                 saved inside sustained action.

Axis indices match CONTROLLER_AXIS_* in xemu-input.h (see NativeInterface.kt):
0=LTRIG 1=RTRIG 2=LSTICK_X 3=LSTICK_Y 4=RSTICK_X 5=RSTICK_Y, range +-32767.

  ./make-synthetic-recording.py spinfire2 > spinfire2.rec
  adb push spinfire2.rec /data/local/tmp/
  adb shell "run-as com.boxxy cp /data/local/tmp/spinfire2.rec \\
      files/recordings/<game_id>/spinfire2.rec"
"""
import sys

GAME = "halo_combat_evolved_usa_"
DUR = 30000
SPIN = 9000
FWD = 20000


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else "spinfire2"
    out = ["# xemu-input-recording v1", f"# game={GAME}"]
    t = 400
    out += [f"{t},AXIS,4,{SPIN}", f"{t},AXIS,5,0",
            f"{t},AXIS,2,0", f"{t},AXIS,3,{FWD}"]
    while t < DUR - 1200:
        out.append(f"{t},AXIS,1,32767")          # fire
        out.append(f"{t+340},AXIS,1,0")
        # Re-assert both axes: a dropped event would otherwise change the
        # trajectory for the rest of the run rather than for one interval.
        out.append(f"{t+520},AXIS,4,{SPIN}")
        out.append(f"{t+520},AXIS,3,{FWD}")
        t += 850
    out += [f"{DUR-400},AXIS,1,0", f"{DUR-300},AXIS,4,0",
            f"{DUR-250},AXIS,3,0", f"{DUR-200},AXIS,2,0"]
    print("\n".join(out))


if __name__ == "__main__":
    main()

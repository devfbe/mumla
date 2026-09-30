#!/usr/bin/env python3
# Copyright (C) 2026 The Mumla Authors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
"""PC side of RoomAcousticsDeviceTest: the near-end talker in the room.

The phone lies next to the PC speaker. This script
1. builds the near-end file: the Piper clip near_en_f.wav, silence, and the same clip again
   NEAR_B_OFFSET_SECONDS after the first copy's start (the test finds the first copy with a
   matched filter and takes the second from this offset, so both must agree);
2. enables the test (adb shell setprop debug.mumla.roomtest true, and optionally
   debug.mumla.roomtest.only for a subset of configurations);
3. watches logcat (tag RoomTest) and plays the file on the PC's analog output with pw-play on every
   ROOM_TRIGGER line, until ROOM_DONE;
4. pulls the recordings and results.csv from the phone while the test waits for it (Studio
   uninstalls the test APK, and its files, when the run ends), then lets the test finish by setting
   debug.mumla.roomtest.pulled and clears the properties.

Start this script first, then RoomAcousticsDeviceTest from Android Studio. Uses only the Python
standard library, adb and pw-play. The clip is read with `git show`, so the checkout stays untouched.

With --selftest it drives DoubleTalkSelfTestDeviceTest instead: it sets debug.mumla.selftest, plays
the plain clip once on every SELFTEST_TRIGGER line (tag SelfTest) and stops at SELFTEST_DONE; that
test writes no files, so nothing is pulled.
"""

import argparse
import array
import datetime
import os
import subprocess
import sys
import time
import wave

NEAR_CLIP = "libraries/humla/src/testSpeech/speech/near_en_f.wav"
NEAR_B_OFFSET_SECONDS = 26  # RoomAcousticsDeviceTest.NEAR_B_OFFSET_SECONDS
ENABLE_PROPERTY = "debug.mumla.roomtest"
ONLY_PROPERTY = "debug.mumla.roomtest.only"
PULLED_PROPERTY = "debug.mumla.roomtest.pulled"
SELFTEST_PROPERTY = "debug.mumla.selftest"
DEVICE_DIR = "/sdcard/Android/data/se.lublin.humla.test/files/room"
DEFAULT_SINK = "alsa_output.pci-0000_00_1f.3.analog-stereo"


def compose(repo: str, out: str, gain_db: float, twice: bool = True) -> None:
    """The near-end file: the clip at 0 s and, if twice, again at NEAR_B_OFFSET_SECONDS."""
    clip = subprocess.run(
        ["git", "-C", repo, "show", f"HEAD:{NEAR_CLIP}"], check=True, capture_output=True
    ).stdout
    raw = out + ".clip.wav"
    with open(raw, "wb") as f:
        f.write(clip)
    with wave.open(raw) as w:
        params = w.getparams()
        frames = w.readframes(params.nframes)
    if gain_db != 0:
        if params.sampwidth != 2 or sys.byteorder != "little":
            sys.exit("--gain-db needs 16-bit samples on a little-endian host")
        samples = array.array("h", frames)
        scale = 10 ** (gain_db / 20)
        for i, s in enumerate(samples):
            samples[i] = max(-32768, min(32767, round(s * scale)))
        frames = samples.tobytes()
    gap = NEAR_B_OFFSET_SECONDS * params.framerate - params.nframes
    if gap < 0:
        sys.exit(f"the clip is longer than {NEAR_B_OFFSET_SECONDS} s")
    silence = b"\0" * (gap * params.sampwidth * params.nchannels)
    with wave.open(out, "wb") as o:
        o.setnchannels(params.nchannels)
        o.setsampwidth(params.sampwidth)
        o.setframerate(params.framerate)
        o.writeframes(frames + silence + frames if twice else frames)
    os.remove(raw)


def adb(serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", serial, *args], check=check, capture_output=True, text=True)


def selftest(args: argparse.Namespace) -> int:
    """Drives DoubleTalkSelfTestDeviceTest: the clip once per SELFTEST_TRIGGER, until SELFTEST_DONE."""
    near = os.path.join(args.out, "near-once.wav")
    compose(args.repo, near, args.gain_db, twice=False)
    adb(args.serial, "shell", "setprop", SELFTEST_PROPERTY, "true")
    logcat = subprocess.Popen(
        ["adb", "-s", args.serial, "logcat", "-T", "1", "-s", "SelfTest:I"], stdout=subprocess.PIPE, text=True,
    )
    players = []
    deadline = time.monotonic() + args.timeout
    print(f"near end: {near}; now start DoubleTalkSelfTestDeviceTest from Android Studio", flush=True)
    try:
        with open(os.path.join(args.out, "selftest.log"), "a") as log:
            for line in logcat.stdout:
                log.write(line)
                log.flush()
                print(line.rstrip(), flush=True)
                if "SELFTEST_TRIGGER" in line:
                    players.append(subprocess.Popen(["pw-play", "--target", args.sink, near]))
                if "SELFTEST_DONE" in line or time.monotonic() > deadline:
                    break
    finally:
        logcat.terminate()
        for player in players:
            try:
                player.wait(timeout=60)
            except subprocess.TimeoutExpired:
                player.terminate()
        adb(args.serial, "shell", "setprop", SELFTEST_PROPERTY, "false", check=False)
    return 0


def main() -> int:
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""), help="adb serial of the phone")
    parser.add_argument("--sink", default=DEFAULT_SINK, help="PipeWire sink of the PC speaker")
    parser.add_argument("--only", default="", help="comma-separated parts of configuration names to run")
    parser.add_argument("--out", default="room-test-results", help="directory for the log and the pulled files")
    parser.add_argument("--repo", default=os.path.normpath(os.path.join(here, "..", "..")))
    parser.add_argument("--timeout", type=int, default=3600, help="give up after this many seconds")
    parser.add_argument("--gain-db", type=float, default=0.0,
                        help="scale the near-end file (a quieter talker) instead of touching the sink volume")
    parser.add_argument("--selftest", action="store_true",
                        help="drive DoubleTalkSelfTestDeviceTest instead of RoomAcousticsDeviceTest")
    args = parser.parse_args()
    if not args.serial:
        parser.error("--serial (or ANDROID_SERIAL) is required")

    os.makedirs(args.out, exist_ok=True)
    if args.selftest:
        return selftest(args)
    near = os.path.join(args.out, "near.wav")
    compose(args.repo, near, args.gain_db)
    adb(args.serial, "shell", "setprop", ENABLE_PROPERTY, "true")
    adb(args.serial, "shell", "setprop", ONLY_PROPERTY, args.only or '""')
    adb(args.serial, "shell", "setprop", PULLED_PROPERTY, "false")
    logcat = subprocess.Popen(
        ["adb", "-s", args.serial, "logcat", "-T", "1", "-v", "epoch", "-s", "RoomTest:I"],
        stdout=subprocess.PIPE, text=True,
    )
    players = []
    deadline = time.monotonic() + args.timeout
    print(f"near end: {near}; now start RoomAcousticsDeviceTest from Android Studio", flush=True)
    try:
        with open(os.path.join(args.out, "room-test.log"), "a") as log:
            for line in logcat.stdout:
                log.write(line)
                log.flush()
                if "ROOM_TRIGGER" in line:
                    host = time.time()
                    players.append(subprocess.Popen(["pw-play", "--target", args.sink, near]))
                    device = float(line.split()[0])
                    note = (f"{datetime.datetime.now():%H:%M:%S} played near end for "
                            f"{line.split('ROOM_TRIGGER', 1)[1].strip()} "
                            f"(host clock minus device stamp {1000 * (host - device):.0f} ms)")
                    print(note, flush=True)
                    log.write(f"# {note}\n")
                else:
                    print(line.rstrip(), flush=True)
                if "ROOM_DONE" in line or time.monotonic() > deadline:
                    break
        pulled = adb(args.serial, "pull", DEVICE_DIR, args.out, check=False)
        print(pulled.stdout.strip() or pulled.stderr.strip())
    finally:
        adb(args.serial, "shell", "setprop", PULLED_PROPERTY, "true", check=False)
        logcat.terminate()
        for player in players:
            try:
                player.wait(timeout=60)
            except subprocess.TimeoutExpired:
                player.terminate()
        adb(args.serial, "shell", "setprop", ENABLE_PROPERTY, "false", check=False)
        adb(args.serial, "shell", "setprop", ONLY_PROPERTY, '""', check=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())

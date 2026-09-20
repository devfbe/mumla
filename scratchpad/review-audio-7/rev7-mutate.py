#!/usr/bin/env python3
"""Reviewer's own mutation harness for audio task 7.

Deliberately NOT named mutate.py: the shared scratchpad already ate one
implementer's mutate.py mid-run and handed back a clean-looking empty result.

Contract per mutation:
  0. refuse to start unless the worktree is clean
  1. apply exactly one textual substitution, assert it changed the file
  2. run the humla unit tests
  3. classify from the JUnit XML *and* the exit code *and* stderr
  4. restore the file, assert the worktree is clean again
Anything unexpected aborts loudly rather than recording a survivor.
"""
import glob
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

WT = "/home/becker/git/mumla-wt-audio"
OUT = os.path.join(WT, "scratchpad/review-audio-7")
RESULTS = os.path.join(WT, "libraries/humla/build/test-results/testDebugUnitTest")
GRADLE = ["nix", "develop", "--command", "./gradlew", "--console=plain",
          ":libraries:humla:testDebugUnitTest"]
TIMEOUT = 1500


def sh(args, **kw):
    return subprocess.run(args, cwd=WT, capture_output=True, text=True, **kw)


def tree_clean():
    return sh(["git", "status", "--porcelain", "--untracked-files=no"]).stdout.strip() == ""


def parse_results():
    """Return (tests, failed, [failing test names]) from the JUnit XML.

    Counts come from the suite attributes, never from a regex over the text:
    a passing test is a self-closing <testcase/>, and a regex that pairs
    <testcase with the next <failure attaches a later failure to an earlier
    passing test.
    """
    tests = failed = 0
    names = []
    files = glob.glob(os.path.join(RESULTS, "*.xml"))
    for p in files:
        try:
            root = ET.parse(p).getroot()
        except ET.ParseError:
            return -1, -1, ["XML PARSE ERROR in %s" % p]
        tests += int(root.get("tests", 0))
        failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                names.append("%s.%s" % (tc.get("classname", "?"), tc.get("name", "?")))
    return tests, failed, names


def run_tests():
    for p in glob.glob(os.path.join(RESULTS, "*.xml")):
        os.remove(p)
    t0 = time.time()
    try:
        r = subprocess.run(GRADLE, cwd=WT, capture_output=True, text=True, timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return {"verdict": "TIMEOUT", "secs": TIMEOUT}
    blob = r.stdout + r.stderr
    # The shared daemon: another worktree's --stop makes the run fail for a
    # reason that has nothing to do with the mutation. Every verdict in a batch
    # measured after one of these is inverted.
    if "daemon has been stopped" in blob or "Gradle build daemon disappeared" in blob:
        return {"verdict": "DAEMON_STOPPED", "secs": time.time() - t0}
    # Kotlin writes compile errors to stderr; "e: file:...: error:" is its shape.
    compile_err = bool(re.search(r"^e: ", blob, re.M)) or "Compilation error" in blob
    tests, failed, names = parse_results()
    if compile_err:
        return {"verdict": "COMPILE_ERROR", "rc": r.returncode, "secs": time.time() - t0,
                "tests": tests, "failed": failed, "names": names[:8]}
    if tests <= 0:
        return {"verdict": "NO_TESTS_RAN", "rc": r.returncode, "secs": time.time() - t0,
                "tail": blob[-1500:]}
    return {"verdict": "KILLED" if failed else "SURVIVED", "rc": r.returncode,
            "secs": time.time() - t0, "tests": tests, "failed": failed, "names": names}


def mutate(path, old, new, count=1):
    full = os.path.join(WT, path)
    src = open(full).read()
    n = src.count(old)
    if n != count:
        raise SystemExit("PATTERN %r occurs %d times in %s, expected %d" % (old, n, path, count))
    open(full, "w").write(src.replace(old, new))


def main():
    import json
    spec_file = sys.argv[1]
    muts = json.load(open(spec_file))
    if not tree_clean():
        raise SystemExit("worktree dirty before starting; refusing")
    log = open(os.path.join(OUT, "rev7-mutation-log.txt"), "a")
    for m in muts:
        mid, path, old, new = m["id"], m["file"], m["old"], m["new"]
        cnt = m.get("count", 1)
        print("=== %s : %s ===" % (mid, m.get("why", "")), flush=True)
        mutate(path, old, new, cnt)
        try:
            res = run_tests()
            while res["verdict"] == "DAEMON_STOPPED":
                print("   daemon was stopped by another worktree -- rerunning", flush=True)
                time.sleep(5)
                res = run_tests()
        finally:
            sh(["git", "checkout", "--", path])
        if not tree_clean():
            raise SystemExit("worktree dirty after %s; aborting" % mid)
        expect = m.get("expect")
        flag = ""
        if expect and expect != res["verdict"]:
            flag = "   *** EXPECTED %s ***" % expect
        line = "%-8s %-9s tests=%s failed=%s %.0fs %s%s" % (
            mid, res["verdict"], res.get("tests"), res.get("failed"), res.get("secs", 0),
            ",".join(res.get("names", [])[:4]), flag)
        print(line, flush=True)
        log.write(line + "\n")
        log.flush()
    log.close()


if __name__ == "__main__":
    main()

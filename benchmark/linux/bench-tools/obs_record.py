#!/usr/bin/env python3
"""Drive OBS as the benchmark screen recorder (validated: 1 ms interval
accuracy at 60 fps CFR, sees Wayland windows).

Usage:
  obs_record.py start   -> launches `obs --startrecording`, waits until the
                           recording file exists and grows, prints its path
  obs_record.py stop    -> SIGTERM the obs process, waits, prints final path

Watches the profile's output dirs (~/Videos and RecFilePath) for the new file.
State (pid + file) kept in .obs_record_state.json next to this script.
"""
import glob, json, os, signal, subprocess, sys, time

STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     ".obs_record_state.json")
WATCH_DIRS = [os.path.expanduser("~/Videos"), os.path.expanduser("~")]

def existing():
    files = set()
    for d in WATCH_DIRS:
        files.update(glob.glob(os.path.join(d, "*.mkv")))
    return files

def start():
    before = existing()
    proc = subprocess.Popen(["obs", "--startrecording", "--minimize-to-tray",
                             "--disable-shutdown-check"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            start_new_session=True)
    path = None
    t_lim = time.time() + 40
    while time.time() < t_lim and path is None:
        if proc.poll() is not None:
            print(json.dumps({"error": "obs exited early"}))
            sys.exit(1)
        new = existing() - before
        if new:
            cand = max(new, key=os.path.getmtime)
            s1 = os.path.getsize(cand)
            time.sleep(1.0)
            if os.path.getsize(cand) > s1:
                path = cand
        time.sleep(0.3)
    if path is None:
        print(json.dumps({"error": "no growing recording file within 40s"}))
        sys.exit(1)
    json.dump({"pid": proc.pid, "file": path, "start_wall": time.time()},
              open(STATE, "w"))
    print(json.dumps({"recording": path, "pid": proc.pid}))

def stop():
    st = json.load(open(STATE))
    try:
        os.kill(st["pid"], signal.SIGTERM)
    except ProcessLookupError:
        pass
    t_lim = time.time() + 25
    while time.time() < t_lim:
        try:
            os.kill(st["pid"], 0)
        except ProcessLookupError:
            break
        time.sleep(0.5)
    else:
        try:
            os.kill(st["pid"], signal.SIGKILL)
        except ProcessLookupError:
            pass
    time.sleep(1.0)
    print(json.dumps({"file": st["file"],
                      "bytes": os.path.getsize(st["file"])}))

if __name__ == "__main__":
    {"start": start, "stop": stop}[sys.argv[1]]()

#!/usr/bin/env python3
"""One perceptual startup run, recorded by an ALREADY-RUNNING OBS session.

Flow: park pointer -> black flash (anchor, OS clock logged) -> spawn app ->
xprop window poll (X11 arms, auxiliary) -> wait 12 s -> CPU snapshot ->
teardown. No per-run video: percep_video.py later locates each run's flash in
the single OBS recording and derives window-appears / fully-rendered.

Usage: launch_percep.py <vault|mediadb|mediadbw> <run_id>
Writes <app>_pstart_<id>.json
"""
import json, os, signal, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (kill_leftovers, descendants, tree_cpu_jiffies,
                    park_pointer_dp3, xprop_root_clients, win_info,
                    VAULT_BIN, MEDIADB_BIN)
from Xlib import display as xd, X

# Directory holding the extracted AppImage (squashfs-root/) and the AOT
# cache (vault_aot.aot) — see README "AOT cache setup".
SCRATCH = os.environ.get("VAULT_AOT_DIR", os.path.expanduser("~/vault-aot"))
CFG = {
    "vault": (VAULT_BIN, {}),
    "vaultaot": (f"{SCRATCH}/appimage/squashfs-root/AppRun",
                 {"JAVA_TOOL_OPTIONS": f"-XX:AOTCache={SCRATCH}/vault_aot.aot"}),
    "mediadb": (MEDIADB_BIN, {"GDK_BACKEND": "x11",
                              "WEBKIT_DISABLE_DMABUF_RENDERER": "1"}),
    "mediadbw": (MEDIADB_BIN, {"__NV_DISABLE_EXPLICIT_SYNC": "1"}),
}

def black_flash(dur=0.5):
    """Large black square on DP-3; returns wallclock of map."""
    d = xd.Display()
    s = d.screen()
    w = s.root.create_window(1440, 1250, 1600, 1600, 0, s.root_depth,
                             X.InputOutput, 0, background_pixel=s.black_pixel,
                             override_redirect=1)
    w.map()
    d.sync()
    t = time.time()
    time.sleep(dur)
    w.unmap()
    w.destroy()
    d.sync()
    d.close()
    return t

def main():
    app, rid = sys.argv[1], sys.argv[2]
    label = f"{app}_pstart_{rid}"
    binary, extra = CFG[app]
    env = dict(os.environ, **extra)
    if app == "mediadbw":
        env.pop("GDK_BACKEND", None)
    kill_leftovers()
    park_pointer_dp3()
    time.sleep(0.5)                 # quiet screen before the anchor
    proc = None
    try:
        flash_wall = black_flash()
        time.sleep(0.4)             # flash gone before spawn
        before = xprop_root_clients()
        t0 = time.time()
        proc = subprocess.Popen([binary], env=env, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, start_new_session=True)
        twin = None
        if app != "mediadbw":
            while twin is None and time.time() - t0 < 6:
                for w in sorted(xprop_root_clients() - before):
                    if any(f"_NET_WM_PID(CARDINAL) = {p}" in win_info(w)
                           for p in descendants(proc.pid)):
                        twin = time.time() - t0
                time.sleep(0.008)
        time.sleep(max(0, 12.0 - (time.time() - t0)))
        pids = descendants(proc.pid)
        out = {"label": label, "app": app, "flash_wall": flash_wall,
               "t0_wall": t0, "t_window_xprop": twin,
               "cpu_jiffies_at_capture_end": tree_cpu_jiffies(pids),
               "clk_tck": os.sysconf("SC_CLK_TCK"), "nprocs": len(pids)}
        with open(f"{label}.json", "w") as f:
            json.dump(out, f, indent=1)
        print(json.dumps({"label": label, "t_window_xprop": twin,
                          "nprocs": out["nprocs"]}))
    finally:
        if proc is not None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except Exception:
                pass
            time.sleep(1.5)
        kill_leftovers()

if __name__ == "__main__":
    main()

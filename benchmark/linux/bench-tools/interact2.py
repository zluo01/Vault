#!/usr/bin/env python3
"""Stage-2 interaction session (recorded by an already-running OBS session).

Uniform choreography, all arms:
  flash anchor -> spawn -> load wait -> focus click -> pre-click Anime ->
  3 folder switches (Movie/TV/Anime) -> move to grid center ->
  16 s wheel scroll @20 Hz alternating every 2 s -> 3 s hold (settle) -> end.
Every input event is logged with its OS wallclock; a sampler thread records
CPU jiffies, disk reads, and per-process GPU SM% (nvidia-smi pmon, summed
over the app's process tree) at ~1 Hz; PSS is snapshotted before input and
after the hold.

X11 arms (vault, vaultaot, mediadb): closed-loop pointer with position
verification; aborts on stray pointer. Wayland arm (mediadbw): open-loop
pointer calibrated pre-spawn on a temporary X window (no X feedback exists
over Wayland surfaces); precision clicks happen at the sidebar before the
single long blind move to the grid center.

Usage: interact2.py <vault|vaultaot|mediadb|mediadbw> <run_id>
Writes <arm>_inter2_<id>.json (run data + events + samples).
"""
import json, os, signal, subprocess, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (kill_leftovers, descendants, tree_cpu_jiffies,
                    proc_metrics, park_pointer_dp3, xprop_root_clients,
                    win_info, win_geometry, VAULT_BIN, MEDIADB_BIN)
from Xlib import display as xd, X
from Xlib.ext import xtest

# Directory holding the extracted AppImage (squashfs-root/) and the AOT
# cache (vault_aot.aot) — see README "AOT cache setup".
SCRATCH = os.environ.get("VAULT_AOT_DIR", os.path.expanduser("~/vault-aot"))
MON = {"x": 0, "y": 809, "w": 4480, "h": 2520}
CFG = {
    "vault": (VAULT_BIN, {}, "x11",
              {"anime": (63, 341), "movie": (63, 177), "tv": (63, 260)}),
    "vaultaot": (f"{SCRATCH}/appimage/squashfs-root/AppRun",
                 {"JAVA_TOOL_OPTIONS": f"-XX:AOTCache={SCRATCH}/vault_aot.aot"},
                 "x11",
                 {"anime": (63, 341), "movie": (63, 177), "tv": (63, 260)}),
    "mediadb": (MEDIADB_BIN,
                {"GDK_BACKEND": "x11", "WEBKIT_DISABLE_DMABUF_RENDERER": "1"},
                "x11",
                {"anime": (60, 320), "movie": (60, 157), "tv": (60, 237)}),
    "mediadbw": (MEDIADB_BIN, {"__NV_DISABLE_EXPLICIT_SYNC": "1"}, "wayland",
                 {"anime": (66, 406), "movie": (66, 238), "tv": (66, 322)}),
}
SCROLL_SECS, HOLD_SECS = 16.0, 3.0
SCROLL_HZ = float(os.environ.get("SCROLL_HZ", "20"))


def black_flash(dur=0.5):
    d = xd.Display()
    s = d.screen()
    w = s.root.create_window(1440, 1250, 1600, 1600, 0, s.root_depth,
                             X.InputOutput, 0, background_pixel=s.black_pixel,
                             override_redirect=1)
    w.map(); d.sync()
    t = time.time()
    time.sleep(dur)
    w.unmap(); w.destroy(); d.sync(); d.close()
    return t


class Ptr:
    """Pointer driver; closed-loop when feedback works, open-loop otherwise."""
    def __init__(self):
        self.d = xd.Display()
        self.root = self.d.screen().root
        self.k = 1.75

    def q(self):
        p = self.root.query_pointer()
        return p.root_x, p.root_y

    def rel(self, dx, dy):
        xtest.fake_input(self.d, X.MotionNotify, detail=1, x=int(dx), y=int(dy))
        self.d.sync()

    def move_closed(self, tx, ty):
        for _ in range(40):
            qx, qy = self.q()
            ex, ey = tx - qx, ty - qy
            if abs(ex) <= 3 and abs(ey) <= 3:
                return True
            rx = max(-300, min(300, ex / self.k))
            ry = max(-300, min(300, ey / self.k))
            self.rel(rx, ry)
            time.sleep(0.03)
            nx, ny = self.q()
            moved = ((nx - qx) ** 2 + (ny - qy) ** 2) ** 0.5
            sent = (rx * rx + ry * ry) ** 0.5
            if sent > 5 and moved > 1:
                self.k = max(0.2, min(5.0, 0.5 * self.k + 0.5 * moved / sent))
        return False

    def move_blind(self, dx, dy):
        steps = max(1, int(max(abs(dx), abs(dy)) / (280 * self.k)) + 1)
        for _ in range(steps):
            self.rel(dx / self.k / steps, dy / self.k / steps)
            time.sleep(0.02)

    def calibrate_park(self, tx, ty):
        """Full-monitor X window for feedback; corner-pin, converge, destroy."""
        s = self.d.screen()
        win = self.root.create_window(MON["x"], MON["y"], MON["w"], MON["h"],
                                      0, s.root_depth, X.InputOutput, 0,
                                      background_pixel=s.black_pixel,
                                      override_redirect=1)
        win.map(); self.d.sync(); time.sleep(0.3)
        try:
            for _ in range(10):
                self.rel(-3000, 3000)
                time.sleep(0.02)
            ok = self.move_closed(tx, ty)
        finally:
            win.unmap(); win.destroy(); self.d.sync()
        return ok

    def click(self, btn=1):
        xtest.fake_input(self.d, X.ButtonPress, btn); self.d.sync()
        xtest.fake_input(self.d, X.ButtonRelease, btn); self.d.sync()


def gpu_pmon_sm(pids):
    """Summed SM% for pids from one pmon sample; None on failure."""
    try:
        out = subprocess.run(["nvidia-smi", "pmon", "-c", "1", "-s", "u"],
                             capture_output=True, text=True, timeout=8).stdout
        tot = 0.0
        for line in out.splitlines():
            f = line.split()
            if len(f) >= 4 and f[0].isdigit() is False and not line.startswith("#"):
                continue
            if line.startswith("#") or len(f) < 4:
                continue
            try:
                pid, sm = int(f[1]), f[3]
            except (ValueError, IndexError):
                continue
            if pid in pids and sm not in ("-", ""):
                tot += float(sm)
        return tot
    except Exception:
        return None


def main():
    arm, rid = sys.argv[1], sys.argv[2]
    label = f"{arm}_inter2_{rid}"
    binary, extra, backend, sidebar = CFG[arm]
    env = dict(os.environ, **extra)
    if backend == "wayland":
        env.pop("GDK_BACKEND", None)
    kill_leftovers()
    park_pointer_dp3()
    time.sleep(0.5)
    ev = {"label": label, "arm": arm, "clicks": [], "wheel": [], "aborted": None}
    samples = []
    stop = threading.Event()
    proc = None
    ptr = Ptr()
    try:
        if backend == "wayland":
            ax, ay = sidebar["anime"]
            if not ptr.calibrate_park(MON["x"] + ax, MON["y"] + ay):
                ev["aborted"] = "wayland pre-spawn calibration failed"
                return
        ev["flash_wall"] = black_flash()
        time.sleep(0.4)
        before = xprop_root_clients()
        ev["t0_wall"] = time.time()
        proc = subprocess.Popen([binary], env=env, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, start_new_session=True)

        def sampler():
            while not stop.is_set():
                pids = set(descendants(proc.pid))
                rb = 0
                for p in pids:
                    try:
                        for line in open(f"/proc/{p}/io"):
                            if line.startswith("read_bytes"):
                                rb += int(line.split(":")[1])
                    except Exception:
                        pass
                samples.append({"wall": time.time(),
                                "jiffies": tree_cpu_jiffies(pids),
                                "read_bytes": rb,
                                "gpu_sm": gpu_pmon_sm(pids)})
                stop.wait(1.0)

        threading.Thread(target=sampler, daemon=True).start()
        time.sleep(4.0)   # all arms fully rendered well before this (sec. 2)
        ev["pss_before_kb"] = sum(p.get("pss", 0)
                                  for p in proc_metrics(descendants(proc.pid)))

        # window-relative coordinate base
        if backend == "x11":
            wid = None
            for w in sorted(xprop_root_clients() - before):
                if any(f"_NET_WM_PID(CARDINAL) = {p}" in win_info(w)
                       for p in descendants(proc.pid)):
                    wid = w
            if wid is None:
                ev["aborted"] = "window not found"
                return
            wx, wy, ww, wh = win_geometry(wid)
        else:
            wx, wy, ww, wh = MON["x"], MON["y"], MON["w"], MON["h"]
        cx, cy = wx + ww // 2, wy + wh // 2

        def to_target(px, py):
            if backend == "x11":
                if not ptr.move_closed(px, py):
                    raise RuntimeError("pointer positioning failed")
            else:
                qx, qy = ev.get("_ptr_at", (MON["x"] + sidebar["anime"][0],
                                            MON["y"] + sidebar["anime"][1]))
                ptr.move_blind(px - qx, py - qy)
            ev["_ptr_at"] = (px, py)

        def click_at(lbl, px, py):
            to_target(px, py)
            time.sleep(0.6)   # let hover-highlight repaints settle pre-click
            ev["clicks"].append({"label": lbl, "wall": time.time()})
            ptr.click()
            time.sleep(2.2)

        # focus + choreography (precision clicks first, blind center move last)
        if backend == "x11":
            to_target(cx, cy)
            ptr.click()
            time.sleep(0.6)
            ev["_ptr_at"] = (cx, cy)
        click_at("pre_anime", wx + sidebar["anime"][0], wy + sidebar["anime"][1])
        click_at("switch_movie", wx + sidebar["movie"][0], wy + sidebar["movie"][1])
        click_at("switch_tv", wx + sidebar["tv"][0], wy + sidebar["tv"][1])
        click_at("switch_anime", wx + sidebar["anime"][0], wy + sidebar["anime"][1])
        to_target(cx, cy)
        time.sleep(0.4)

        period = 1.0 / SCROLL_HZ
        ev["scroll_start_wall"] = time.time()
        t_total = time.time() + SCROLL_SECS
        btn = 5
        while time.time() < t_total:
            tend = min(time.time() + 2.0, t_total)
            while time.time() < tend:
                if backend == "x11":
                    qx, qy = ptr.q()
                    if abs(qx - cx) > 40 or abs(qy - cy) > 40:
                        raise RuntimeError("pointer moved during session")
                ev["wheel"].append(time.time())
                ptr.click(btn)
                time.sleep(period)
            btn = 9 - btn
        ev["scroll_end_wall"] = time.time()
        time.sleep(HOLD_SECS)
        ev["hold_end_wall"] = time.time()
        ev["pss_after_kb"] = sum(p.get("pss", 0)
                                 for p in proc_metrics(descendants(proc.pid)))
    except RuntimeError as e:
        ev["aborted"] = str(e)
    finally:
        stop.set()
        ev.pop("_ptr_at", None)
        ev["samples"] = samples
        with open(f"{label}.json", "w") as f:
            json.dump(ev, f)
        if proc is not None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except Exception:
                pass
            time.sleep(1.5)
        kill_leftovers()
        print(json.dumps({"label": label, "aborted": ev.get("aborted"),
                          "wheel_events": len(ev.get("wheel", [])),
                          "clicks": len(ev.get("clicks", []))}))

if __name__ == "__main__":
    main()

"""Shared utilities for the JavaFX-vs-Tauri benchmark."""
import datetime, os, subprocess, time

# Set VAULT_BIN / MEDIADB_BIN in the environment, or edit the defaults.
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRACE = os.environ.get("BENCH_TRACE", os.path.join(_ROOT, "analysis-trace.log"))
VAULT_BIN = os.environ.get("VAULT_BIN", os.path.join(_ROOT, "Vault-x86_64.AppImage"))
MEDIADB_BIN = os.environ.get("MEDIADB_BIN", os.path.join(_ROOT, "mediadb"))

def trace(phase, msg):
    line = f"{datetime.datetime.now().isoformat(timespec='seconds')} [{phase}] {msg}\n"
    with open(TRACE, "a") as f:
        f.write(line)

# ---------- process helpers ----------

def _ancestors():
    """PIDs of this process and its ancestors (never kill these)."""
    res, p = set(), os.getpid()
    while p > 1:
        res.add(p)
        try:
            with open(f"/proc/{p}/stat") as f:
                p = int(f.read().rsplit(")", 1)[1].split()[1])
        except Exception:
            break
    return res

def kill_leftovers():
    """Kill stray app processes by /proc scan (never by pkill -f patterns,
    which can match our own shell's command line)."""
    protect = _ancestors()
    victims = []
    for p in os.listdir("/proc"):
        if not p.isdigit() or int(p) in protect:
            continue
        pid = int(p)
        try:
            with open(f"/proc/{pid}/comm") as f:
                comm = f.read().strip()
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                cmd = f.read().decode("utf-8", "replace")
        except Exception:
            continue
        first_arg = cmd.split("\0", 1)[0]
        if (first_arg in (VAULT_BIN, MEDIADB_BIN)
                or comm in ("Vault", "mediadb", "Vault-x86_64.App")
                or "/squashfs-root/usr/bin/Vault" in first_arg
                or (comm == "AppRun" and "squashfs-root" in cmd)
                or comm in ("WebKitWebProces", "WebKitNetworkPr") and "mediadb" in cmd):
            victims.append(pid)
    for pid in victims:
        try:
            os.kill(pid, 9)
        except Exception:
            pass
    if victims:
        time.sleep(0.5)
    return victims

def descendants(root_pid):
    kids = {}
    for p in os.listdir("/proc"):
        if not p.isdigit():
            continue
        try:
            with open(f"/proc/{p}/stat") as f:
                st = f.read()
            ppid = int(st.rsplit(")", 1)[1].split()[1])
            kids.setdefault(ppid, []).append(int(p))
        except Exception:
            pass
    res, stack = [], [root_pid]
    while stack:
        p = stack.pop()
        res.append(p)
        stack.extend(kids.get(p, []))
    return res

def tree_cpu_jiffies(pids):
    total = 0
    for p in pids:
        try:
            with open(f"/proc/{p}/stat") as f:
                parts = f.read().rsplit(")", 1)[1].split()
            total += int(parts[11]) + int(parts[12])
        except Exception:
            pass
    return total

def proc_metrics(pids):
    procs = []
    for p in pids:
        m = {"pid": p}
        try:
            with open(f"/proc/{p}/comm") as f:
                m["comm"] = f.read().strip()
            with open(f"/proc/{p}/smaps_rollup") as f:
                for line in f:
                    if line.startswith(("Rss:", "Pss:", "Private_Dirty:", "Swap:")):
                        k, v = line.split(":", 1)
                        m[k.rstrip(":").lower()] = int(v.split()[0])
            with open(f"/proc/{p}/status") as f:
                for line in f:
                    if line.startswith("Threads:"):
                        m["threads"] = int(line.split()[1])
            m["fds"] = len(os.listdir(f"/proc/{p}/fd"))
            with open(f"/proc/{p}/io") as f:
                for line in f:
                    if line.startswith(("read_bytes", "write_bytes")):
                        k, v = line.split(":")
                        m[k.strip()] = int(v)
            procs.append(m)
        except Exception:
            if "comm" in m:
                procs.append(m)
    return procs

def summarize_procs(procs):
    return {
        "pss_total_kb": sum(p.get("pss", 0) for p in procs),
        "rss_total_kb": sum(p.get("rss", 0) for p in procs),
        "threads_total": sum(p.get("threads", 0) for p in procs),
        "fds_total": sum(p.get("fds", 0) for p in procs),
        "read_bytes_total": sum(p.get("read_bytes", 0) for p in procs),
        "nprocs": len(procs),
        "procs": procs,
    }

def gpu_mem_for(pids):
    try:
        import xml.etree.ElementTree as ET
        x = subprocess.run(["nvidia-smi", "-q", "-x"], capture_output=True, text=True,
                           timeout=15).stdout
        root = ET.fromstring(x)
        tot, found = 0, []
        for pi in root.iter("process_info"):
            pid = int(pi.findtext("pid", "0"))
            if pid in pids:
                mem = int(pi.findtext("used_memory", "0 MiB").split()[0])
                tot += mem
                found.append({"pid": pid, "type": pi.findtext("type", "?"), "mib": mem})
        return {"total_mib": tot, "procs": found}
    except Exception as e:
        return {"error": str(e)}

# ---------- X11 helpers (subprocess xprop; python-xlib only where needed) ----------

def xprop_root_clients():
    try:
        out = subprocess.run(["xprop", "-root", "_NET_CLIENT_LIST"],
                             capture_output=True, text=True, timeout=2).stdout
        if "#" not in out:
            return set()
        return set(w.strip() for w in out.split("#", 1)[1].split(",") if w.strip())
    except Exception:
        return set()

def win_info(wid):
    try:
        return subprocess.run(["xprop", "-id", wid, "WM_CLASS", "_NET_WM_NAME",
                               "_NET_WM_PID"], capture_output=True, text=True,
                              timeout=2).stdout
    except Exception:
        return ""

def active_window():
    try:
        out = subprocess.run(["xprop", "-root", "_NET_ACTIVE_WINDOW"],
                             capture_output=True, text=True, timeout=2).stdout
        return out.split("#", 1)[1].strip() if "#" in out else None
    except Exception:
        return None

def win_geometry(wid):
    """Return (x, y, w, h) in root coordinates via python-xlib."""
    from Xlib import display as xdisplay
    d = xdisplay.Display()
    try:
        win = d.create_resource_object("window", int(wid, 16))
        g = win.get_geometry()
        c = win.translate_coords(d.screen().root, 0, 0)
        return (-c.x, -c.y, g.width, g.height)
    finally:
        d.close()

def normalize_window(wid, x, y, w, h):
    """Unmaximize and move/resize a window via EWMH + ConfigureWindow."""
    from Xlib import display as xdisplay, X, protocol
    d = xdisplay.Display()
    try:
        root = d.screen().root
        win = d.create_resource_object("window", int(wid, 16))
        state = d.intern_atom("_NET_WM_STATE")
        maxh = d.intern_atom("_NET_WM_STATE_MAXIMIZED_HORZ")
        maxv = d.intern_atom("_NET_WM_STATE_MAXIMIZED_VERT")
        ev = protocol.event.ClientMessage(window=win, client_type=state,
                                          data=(32, [0, maxh, maxv, 1, 0]))  # 0=remove
        root.send_event(ev, event_mask=X.SubstructureRedirectMask | X.SubstructureNotifyMask)
        d.flush(); time.sleep(0.3)
        win.configure(x=x, y=y, width=w, height=h)
        d.flush(); time.sleep(0.3)
    finally:
        d.close()

def sync_flash(x=1840, y=1569, w=1200, h=1200, dur=0.4):
    """Map a white override-redirect X window briefly; returns wallclock of
    appearance. Used to sync portal recordings (relative PTS) to wallclock."""
    from Xlib import display as xd, X
    d = xd.Display()
    s = d.screen()
    win = s.root.create_window(x, y, w, h, 0, s.root_depth, X.InputOutput,
                               0, background_pixel=s.black_pixel,
                               override_redirect=1)  # black: visible on the
                               # white benchmark background (white was invisible)
    win.map()
    d.sync()
    t = time.time()
    time.sleep(dur)
    win.unmap()
    win.destroy()
    d.sync()
    d.close()
    return t

def park_pointer_dp3():
    """Pin the pointer onto DP-3 (bottom-left of the virtual desktop) with
    relative XTEST moves — edge clamping makes this deterministic without any
    position feedback. KWin opens new windows on the screen holding the
    pointer, so this makes window placement independent of where the user's
    mouse was left."""
    from Xlib import display as xd, X
    from Xlib.ext import xtest
    d = xd.Display()
    try:
        for _ in range(10):
            xtest.fake_input(d, X.MotionNotify, detail=1, x=-3000, y=3000)
            d.sync()
            time.sleep(0.015)
        # nudge off the exact corner so nothing corner-sensitive triggers
        xtest.fake_input(d, X.MotionNotify, detail=1, x=120, y=-120)
        d.sync()
    finally:
        d.close()

def kwin_resize(resource_class, lx, ly, lw, lh):
    """Unmaximize + resize a window via KWin scripting (logical coordinates).
    EWMH client resizes are unreliable under KWin Wayland for XWayland windows."""
    import tempfile
    js = f"""
for (const w of workspace.windowList()) {{
  if (w.resourceClass == "{resource_class}") {{
    if (typeof w.setMaximize === "function") w.setMaximize(false, false);
    w.frameGeometry = Qt.rect({lx}, {ly}, {lw}, {lh});
  }}
}}
"""
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as f:
        f.write(js)
        path = f.name
    try:
        rid = subprocess.run(["busctl", "--user", "call", "org.kde.KWin",
                              "/Scripting", "org.kde.kwin.Scripting",
                              "loadScript", "s", path],
                             capture_output=True, text=True, timeout=10).stdout
        subprocess.run(["busctl", "--user", "call", "org.kde.KWin", "/Scripting",
                        "org.kde.kwin.Scripting", "start"],
                       capture_output=True, timeout=10)
        time.sleep(0.5)
        subprocess.run(["busctl", "--user", "call", "org.kde.KWin", "/Scripting",
                        "org.kde.kwin.Scripting", "unloadScript", "s", path],
                       capture_output=True, timeout=10)
    finally:
        os.unlink(path)

def corner_flicker(dur=0.5, x=40, y=850, size=360, hz=30):
    """Finite black/white flicker burst in the DP-3 top-left corner.
    Detectable over ANY underlying content (temporal variance, not contrast),
    visible over maximized apps (override-redirect). Returns wallclock of the
    first toggle — used to calibrate the portal recording's skewed clock."""
    from Xlib import display as xd, X
    d = xd.Display()
    s = d.screen()
    win = s.root.create_window(x, y, size, size, 0, s.root_depth, X.InputOutput,
                               0, background_pixel=s.white_pixel,
                               override_redirect=1)
    win.map()
    d.sync()
    gcw = win.create_gc(foreground=s.white_pixel)
    gcb = win.create_gc(foreground=s.black_pixel)
    t0 = time.time()
    on = True
    while time.time() - t0 < dur:
        win.fill_rectangle(gcb if on else gcw, 0, 0, size, size)
        d.sync()
        on = not on
        time.sleep(1.0 / hz)
    win.unmap()
    win.destroy()
    d.sync()
    d.close()
    return t0

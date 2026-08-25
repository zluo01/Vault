#!/usr/bin/env python3
"""Stage-3 idle-footprint run. No video, no input — pure /proc sampling.

Flow: spawn -> CPU-quiescence settle -> idle window with full snapshots at
settle+15/+40/+65 s -> teardown. Each snapshot sums over the app's process
tree: PSS/RSS/Private_Dirty/Swap (smaps_rollup), RssAnon/RssFile/RssShmem/
VmHWM/threads/ctx-switches (/proc/status), fd and socket-fd counts, disk
read_bytes, per-process GPU SM% (nvidia-smi pmon) and GPU memory.
Idle CPU%% and wakeups/s are computed over the s1->s3 span.

Arms: vault | vaultch (JDK 25 -XX:+UseCompactObjectHeaders, JEP 519) |
mediadb | mediadbw.

Usage: resource2.py <arm> <run_id>   -> writes <arm>_res2_<id>.json
"""
import json, os, signal, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (kill_leftovers, descendants, tree_cpu_jiffies,
                    gpu_mem_for, park_pointer_dp3, VAULT_BIN, MEDIADB_BIN)

CFG = {
    "vault": (VAULT_BIN, {}),
    "vaultch": (VAULT_BIN, {"JAVA_TOOL_OPTIONS": "-XX:+UseCompactObjectHeaders"}),
    "mediadb": (MEDIADB_BIN, {"GDK_BACKEND": "x11",
                              "WEBKIT_DISABLE_DMABUF_RENDERER": "1"}),
    "mediadbw": (MEDIADB_BIN, {"__NV_DISABLE_EXPLICIT_SYNC": "1"}),
}
STATUS_KEYS = {"RssAnon": "rss_anon", "RssFile": "rss_file",
               "RssShmem": "rss_shmem", "VmHWM": "vm_hwm",
               "Threads": "threads"}


def pmon_sm(pids):
    try:
        out = subprocess.run(["nvidia-smi", "pmon", "-c", "1", "-s", "u"],
                             capture_output=True, text=True, timeout=8).stdout
        tot = 0.0
        for line in out.splitlines():
            f = line.split()
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


def snapshot(pids):
    s = {"wall": time.time(), "nprocs": len(pids), "pss": 0, "rss": 0,
         "private_dirty": 0, "swap": 0, "rss_anon": 0, "rss_file": 0,
         "rss_shmem": 0, "vm_hwm": 0, "threads": 0, "fds": 0, "sockets": 0,
         "read_bytes": 0, "ctxt": 0}
    for p in pids:
        try:
            with open(f"/proc/{p}/smaps_rollup") as f:
                for line in f:
                    if line.startswith("Pss:"):
                        s["pss"] += int(line.split()[1])
                    elif line.startswith("Rss:"):
                        s["rss"] += int(line.split()[1])
                    elif line.startswith("Private_Dirty:"):
                        s["private_dirty"] += int(line.split()[1])
                    elif line.startswith("Swap:"):
                        s["swap"] += int(line.split()[1])
            with open(f"/proc/{p}/status") as f:
                for line in f:
                    k = line.split(":")[0]
                    if k in STATUS_KEYS:
                        s[STATUS_KEYS[k]] += int(line.split()[1])
            # ctxt switches in /proc/pid/status cover the leader thread only;
            # sum every task or an idle JVM reads as zero wakeups
            for tid in os.listdir(f"/proc/{p}/task"):
                try:
                    with open(f"/proc/{p}/task/{tid}/status") as f:
                        for line in f:
                            if line.startswith(("voluntary_ctxt_switches",
                                                "nonvoluntary_ctxt_switches")):
                                s["ctxt"] += int(line.split()[1])
                except OSError:
                    pass
            fdd = f"/proc/{p}/fd"
            for fd in os.listdir(fdd):
                s["fds"] += 1
                try:
                    if os.readlink(f"{fdd}/{fd}").startswith("socket:"):
                        s["sockets"] += 1
                except OSError:
                    pass
            with open(f"/proc/{p}/io") as f:
                for line in f:
                    if line.startswith("read_bytes"):
                        s["read_bytes"] += int(line.split(":")[1])
        except Exception:
            pass
    s["jiffies"] = tree_cpu_jiffies(pids)
    s["gpu_sm"] = pmon_sm(set(pids))
    return s


def main():
    arm, rid = sys.argv[1], sys.argv[2]
    label = f"{arm}_res2_{rid}"
    binary, extra = CFG[arm]
    env = dict(os.environ, **extra)
    if arm == "mediadbw":
        env.pop("GDK_BACKEND", None)
    kill_leftovers()
    park_pointer_dp3()
    out = {"label": label, "arm": arm}
    proc = None
    try:
        t0 = time.time()
        proc = subprocess.Popen([binary], env=env, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, start_new_session=True)
        out["t0_wall"] = t0
        # settle: <=5 jiffies per 500 ms, 4 consecutive
        prev = tree_cpu_jiffies(descendants(proc.pid))
        quiet, t_settle = 0, None
        end = time.time() + 45
        while time.time() < end:
            time.sleep(0.5)
            cur = tree_cpu_jiffies(descendants(proc.pid))
            quiet = quiet + 1 if cur - prev <= 5 else 0
            prev = cur
            if quiet >= 4:
                t_settle = time.time() - t0
                break
        out["t_settle_s"] = t_settle
        base = time.time()
        snaps = []
        for delay in (15, 40, 65):
            time.sleep(max(0, base + delay - time.time()))
            snaps.append(snapshot(descendants(proc.pid)))
        out["snapshots"] = snaps
        # rates over s2->s3 only: transient WebKit helpers exit before +40 s,
        # and a task leaving the tree takes its counters out of the s1 sum
        span = snaps[2]["wall"] - snaps[1]["wall"]
        out["idle_cpu_pct"] = round(100.0 * (snaps[2]["jiffies"] - snaps[1]["jiffies"])
                                    / os.sysconf("SC_CLK_TCK") / span, 3)
        out["wakeups_per_s"] = round((snaps[2]["ctxt"] - snaps[1]["ctxt"]) / span, 1)
        out["idle_read_bytes"] = snaps[2]["read_bytes"] - snaps[1]["read_bytes"]
        out["pss_drift_kb"] = snaps[2]["pss"] - snaps[1]["pss"]
        out["gpu"] = gpu_mem_for(set(descendants(proc.pid)))
    finally:
        with open(f"{label}.json", "w") as f:
            json.dump(out, f)
        if proc is not None:
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except Exception:
                pass
            time.sleep(1.5)
        kill_leftovers()
        s1 = out.get("snapshots", [{}])[0]
        print(json.dumps({"label": label, "settle": out.get("t_settle_s"),
                          "pss_mb": round(s1.get("pss", 0) / 1024, 1),
                          "idle_cpu": out.get("idle_cpu_pct"),
                          "wakeups_s": out.get("wakeups_per_s")}))

if __name__ == "__main__":
    main()

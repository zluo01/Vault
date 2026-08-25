#!/usr/bin/env python3
"""Aggregate stage-3 idle-footprint runs (<arm>_res2_r*.json).

Footprint values come from the steady s2 snapshot (+40 s); rates were
derived in resource2.py over the s2->s3 window. Prints median [min-max]
(plus p95 where distributions matter) per arm for every collected metric.
Run from the directory holding the JSONs.
"""
import glob, json, statistics, sys

ARMS = ["vault", "vaultch", "mediadb", "mediadbw"]


def pct(vals, q):
    v = sorted(vals)
    i = (len(v) - 1) * q
    lo = int(i)
    return v[lo] + (v[min(lo + 1, len(v) - 1)] - v[lo]) * (i - lo)


def agg(vals, unit=1.0, nd=1):
    v = [x / unit for x in vals if x is not None]
    if not v:
        return "n/a"
    return (f"{statistics.median(v):.{nd}f} "
            f"[{min(v):.{nd}f}-{max(v):.{nd}f}] p95 {pct(v, 0.95):.{nd}f}")


def main():
    data = {a: [] for a in ARMS}
    for a in ARMS:
        for f in sorted(glob.glob(f"{a}_res2_r*.json")):
            data[a].append(json.load(open(f)))
    rows = [
        ("n runs", lambda d: len(d), None),
        ("settle s", lambda d: [r["t_settle_s"] for r in d], (1.0, 2)),
        ("pss MB", lambda d: [r["snapshots"][1]["pss"] for r in d], (1024, 1)),
        ("rss MB", lambda d: [r["snapshots"][1]["rss"] for r in d], (1024, 1)),
        ("  anon MB", lambda d: [r["snapshots"][1]["rss_anon"] for r in d], (1024, 1)),
        ("  file MB", lambda d: [r["snapshots"][1]["rss_file"] for r in d], (1024, 1)),
        ("  shmem MB", lambda d: [r["snapshots"][1]["rss_shmem"] for r in d], (1024, 1)),
        ("pdirty MB", lambda d: [r["snapshots"][1]["private_dirty"] for r in d], (1024, 1)),
        ("vm_hwm MB", lambda d: [r["snapshots"][1]["vm_hwm"] for r in d], (1024, 1)),
        ("swap kB", lambda d: [r["snapshots"][1]["swap"] for r in d], (1.0, 0)),
        ("gpu mem MiB", lambda d: [(r.get("gpu") or {}).get("total_mib") for r in d], (1.0, 0)),
        ("nprocs", lambda d: [r["snapshots"][1]["nprocs"] for r in d], (1.0, 0)),
        ("threads", lambda d: [r["snapshots"][1]["threads"] for r in d], (1.0, 0)),
        ("fds", lambda d: [r["snapshots"][1]["fds"] for r in d], (1.0, 0)),
        ("sockets", lambda d: [r["snapshots"][1]["sockets"] for r in d], (1.0, 0)),
        ("idle cpu %", lambda d: [r["idle_cpu_pct"] for r in d], (1.0, 2)),
        ("wakeups /s", lambda d: [r["wakeups_per_s"] for r in d], (1.0, 1)),
        ("idle read B", lambda d: [r["idle_read_bytes"] for r in d], (1.0, 0)),
        ("pss drift kB", lambda d: [r["pss_drift_kb"] for r in d], (1.0, 0)),
        ("gpu sm % (s2)", lambda d: [r["snapshots"][1]["gpu_sm"] for r in d], (1.0, 1)),
    ]
    for name, fn, spec in rows:
        print(f"{name}:")
        for a in ARMS:
            v = fn(data[a])
            out = v if spec is None else agg(v, spec[0], spec[1])
            print(f"  {a:9s} {out}")


if __name__ == "__main__":
    main()

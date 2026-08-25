#!/usr/bin/env python3
"""Aggregate stage-1 startup runs into per-configuration distributions.

Reads <config>_pstart_r*.analysis.json (timings, written by percep_video.py)
and the matching <config>_pstart_r*.json (CPU snapshot, written by
launch_percep.py). Prints median [min-max] p95 per metric.
Run from the directory holding the JSONs.
"""
import glob, json, statistics

CONFIGS = ["vault", "vaultaot", "mediadb", "mediadbw"]


def pct(v, q):
    v = sorted(v)
    i = (len(v) - 1) * q
    lo = int(i)
    return v[lo] + (v[min(lo + 1, len(v) - 1)] - v[lo]) * (i - lo)


def agg(vals, nd=3):
    v = [x for x in vals if x is not None]
    if not v:
        return "n/a"
    return (f"{statistics.median(v):.{nd}f} [{min(v):.{nd}f}-{max(v):.{nd}f}] "
            f"p95 {pct(v, 0.95):.{nd}f}")


def main():
    rows = {"registered s": [], "window visible s": [], "fully rendered s": [],
            "registered->visible ms": [], "startup CPU s": []}
    data = {}
    for c in CONFIGS:
        runs = []
        for f in sorted(glob.glob(f"{c}_pstart_r*.analysis.json")):
            a = json.load(open(f))
            r = json.load(open(f.replace(".analysis", "")))
            cpu = (r["cpu_jiffies_at_capture_end"] / r["clk_tck"]
                   if r.get("cpu_jiffies_at_capture_end") else None)
            runs.append({
                "registered s": a.get("t_window_xprop"),
                "window visible s": a.get("window_ready_s"),
                "fully rendered s": a.get("fully_rendered_s"),
                "registered->visible ms":
                    (a["window_ready_s"] - a["t_window_xprop"]) * 1000
                    if a.get("t_window_xprop") and a.get("window_ready_s") else None,
                "startup CPU s": cpu,
            })
        data[c] = runs
    for name in rows:
        print(f"{name}:")
        for c in CONFIGS:
            print(f"  {c:9s} n={len(data[c]):3d} "
                  f"{agg([r[name] for r in data[c]])}")


if __name__ == "__main__":
    main()

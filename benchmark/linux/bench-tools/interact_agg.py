#!/usr/bin/env python3
"""Aggregate stage-2 interaction sessions into per-configuration distributions.

Reads <config>_inter2_r*.analysis.json (written by interact_video.py) and
prints median [min-max] p95 per metric. Switch latencies pool the three
switch_* clicks of every session; values below one display frame (17 ms)
appear as ~0. Run from the directory holding the JSONs.
"""
import glob, json, statistics

CONFIGS = ["vault", "vaultaot", "mediadb", "mediadbw"]


def pct(v, q):
    v = sorted(v)
    i = (len(v) - 1) * q
    lo = int(i)
    return v[lo] + (v[min(lo + 1, len(v) - 1)] - v[lo]) * (i - lo)


def agg(vals, nd=1):
    v = [x for x in vals if x is not None]
    if not v:
        return "n/a"
    return (f"{statistics.median(v):.{nd}f} [{min(v):.{nd}f}-{max(v):.{nd}f}] "
            f"p95 {pct(v, 0.95):.{nd}f}")


def main():
    rows = [
        ("input latency median ms", lambda a: a["input_latency_ms"]["median"]),
        ("input latency p95 ms", lambda a: a["input_latency_ms"]["p95"]),
        ("miss rate %", lambda a: a["miss_rate"] * 100),
        ("effective FPS", lambda a: a["effective_fps"]),
        ("pacing p95 ms", lambda a: a["pacing_p95_ms"]),
        ("settle after scroll s", lambda a: a["settle_after_scroll_s"]),
        ("scroll CPU cores", lambda a: a["scroll_cpu_cores"]),
        ("scroll GPU SM %", lambda a: a["scroll_gpu_sm_pct"]),
        ("PSS growth MB", lambda a: a["pss_delta_mb"]),
    ]
    data = {c: [json.load(open(f)) for f in
                sorted(glob.glob(f"{c}_inter2_r*.analysis.json"))]
            for c in CONFIGS}
    for name, fn in rows:
        print(f"{name}:")
        for c in CONFIGS:
            vals = []
            for a in data[c]:
                try:
                    vals.append(fn(a))
                except (KeyError, TypeError):
                    pass
            print(f"  {c:9s} n={len(vals):3d} {agg(vals)}")
    for key in ("first_ms", "settle_ms"):
        print(f"switch {key} (pooled switch_* clicks):")
        for c in CONFIGS:
            vals = [cl[key] for a in data[c] for cl in a.get("clicks", [])
                    if cl["label"].startswith("switch_") and cl.get(key) is not None]
            print(f"  {c:9s} n={len(vals):3d} {agg(vals)}")


if __name__ == "__main__":
    main()

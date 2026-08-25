#!/usr/bin/env python3
"""Analyze one OBS recording covering N stage-2 interaction sessions.

Usage: interact_video.py <video> <run1.json> [run2.json ...]
Per run (matched to its black-flash anchor, same detection as percep_video):
  input_latency_ms   median / p95 of wheel-event -> next changed frame
  miss_rate          fraction of wheel events with no changed frame in 100 ms
  effective_fps      changed frames / scroll duration (ceiling 60)
  pacing_p95_ms      p95 gap between changed frames during scroll
                     (gaps overlapping a direction flip excluded)
  click first/settle per switch label
  settle_after_scroll_s   last wheel -> last change (needs >=1.0 s quiet)
Writes <run>.analysis.json per run and prints one summary line each.
"""
import json, subprocess, sys
import numpy as np

W, H = 384, 216
DIFF_EPS = 1.0

def main():
    video, runfiles = sys.argv[1], sys.argv[2:]
    runs = [json.load(open(f)) for f in runfiles]

    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
                          "-show_entries", "frame=pts_time", "-of", "csv=p=0",
                          video], capture_output=True, text=True).stdout
    pts = [float(x.split(",")[0]) for x in out.strip().splitlines() if x.strip()]
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-i", video,
                          "-fps_mode", "passthrough", "-f", "rawvideo",
                          "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-"],
                         stdout=subprocess.PIPE)
    n = W * H * 3
    means, diffs = [], [0.0]
    prev = None
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n:
            break
        img = np.frombuffer(buf, dtype=np.uint8).astype(np.int16).reshape(H, W, 3)
        means.append(float(img[38:175, 123:260].mean()))
        fr = img.reshape(-1, 3)
        if prev is not None:
            diffs.append(float(np.abs(fr - prev).max(axis=1).mean()))
        prev = fr
    p.wait()
    m = min(len(pts), len(means))
    pts, diffs = pts[:m], diffs[:m]

    anchors = []
    i = 1
    while i < m:
        if means[i - 1] > 200 and means[i] <= 200:
            start, j, recovered = i, i + 1, False
            while j < m and pts[j] - pts[start] < 1.6:
                if means[j] > 200 and pts[j] - pts[start] > 0.3:
                    recovered = True
                    break
                j += 1
            if recovered and min(means[start:j]) < 100:
                anchors.append(pts[start])
            i = j
        else:
            i += 1
    if len(anchors) < len(runs):
        print(json.dumps({"error": f"{len(runs)} runs, {len(anchors)} anchors"}))
        sys.exit(1)

    changed_all = [i for i in range(1, m) if diffs[i] > DIFF_EPS]
    for k, run in enumerate(runs):
        if run.get("aborted"):
            continue
        fa = (anchors[k] if len(anchors) == len(runs) else
              min(anchors, key=lambda a: abs((a - anchors[0]) -
                  (run["flash_wall"] - runs[0]["flash_wall"]))))
        off = run["flash_wall"] - fa
        wall = lambda pv: pv + off
        res = {"label": run["label"]}

        s0, s1 = run["scroll_start_wall"], run["scroll_end_wall"]
        ch = [i for i in changed_all if s0 <= wall(pts[i]) <= s1]
        res["effective_fps"] = round(len(ch) / (s1 - s0), 2)

        # per-wheel-event latency + miss rate
        ch_walls = np.array([wall(pts[i]) for i in ch])
        lats, miss = [], 0
        for wv in run["wheel"]:
            nxt = ch_walls[ch_walls > wv]
            if len(nxt) == 0 or nxt[0] - wv > 0.1:
                miss += 1
            if len(nxt):
                lats.append((nxt[0] - wv) * 1000)
        if lats:
            res["input_latency_ms"] = {"median": round(float(np.median(lats)), 1),
                                       "p95": round(float(np.percentile(lats, 95)), 1),
                                       "n": len(lats)}
        res["miss_rate"] = round(miss / max(1, len(run["wheel"])), 4)

        # pacing: gaps between changed frames, excluding direction flips
        flips = [s0 + 2.0 * q for q in range(1, 8)]
        gaps = []
        for a, b in zip(ch, ch[1:]):
            ga, gb = wall(pts[a]), wall(pts[b])
            if any(ga < fl < gb for fl in flips):
                continue
            gaps.append((gb - ga) * 1000)
        if gaps:
            res["pacing_p95_ms"] = round(float(np.percentile(gaps, 95)), 1)
            res["pacing_max_ms"] = round(max(gaps), 1)

        # clicks: first change + settle (last change followed by >=0.4 s quiet
        # inside the 2.1 s post-click window)
        cl = []
        for c in run["clicks"]:
            cw = c["wall"]
            # -17 ms tolerance: a repaint triggered by the click can land in
            # the 60 fps frame whose capture interval spans the click instant
            win = [i for i in changed_all if cw - 0.017 < wall(pts[i]) <= cw + 2.1]
            if not win:
                cl.append({"label": c["label"], "first_ms": None})
                continue
            settle = win[-1]
            for a, b in zip(win, win[1:]):
                if wall(pts[b]) - wall(pts[a]) >= 0.4:
                    settle = a
                    break
            cl.append({"label": c["label"],
                       "first_ms": round((wall(pts[win[0]]) - cw) * 1000, 1),
                       "settle_ms": round((wall(pts[settle]) - cw) * 1000, 1)})
        res["clicks"] = cl

        # settle after scroll stop
        hold_end = run.get("hold_end_wall", s1 + 3.0)
        tail = [i for i in changed_all if s1 < wall(pts[i]) <= hold_end]
        if tail:
            last = tail[-1]
            res["settle_after_scroll_s"] = (round(wall(pts[last]) - s1, 3)
                                            if hold_end - wall(pts[last]) >= 1.0
                                            else None)
        else:
            res["settle_after_scroll_s"] = 0.0

        # cost side from run json
        seg = [x for x in run["samples"] if s0 <= x["wall"] <= s1]
        if len(seg) > 2:
            span = seg[-1]["wall"] - seg[0]["wall"]
            res["scroll_cpu_cores"] = round(
                (seg[-1]["jiffies"] - seg[0]["jiffies"]) / 100.0 / span, 3)
            res["scroll_read_mb"] = round(
                (seg[-1]["read_bytes"] - seg[0]["read_bytes"]) / 1e6, 1)
            g = [x["gpu_sm"] for x in seg if x.get("gpu_sm") is not None]
            if g:
                res["scroll_gpu_sm_pct"] = round(sum(g) / len(g), 1)
        res["pss_delta_mb"] = round(
            (run.get("pss_after_kb", 0) - run.get("pss_before_kb", 0)) / 1024, 1)

        json.dump(res, open(run["label"] + ".analysis.json", "w"))
        print(json.dumps(res))

if __name__ == "__main__":
    main()

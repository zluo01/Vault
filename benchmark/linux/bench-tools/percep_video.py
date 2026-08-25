#!/usr/bin/env python3
"""Analyze one OBS recording covering N perceptual runs.

Usage: percep_video.py <video> <run1.json> [run2.json ...]
Locates each run's black-flash anchor (full-frame darkening events, in order),
maps video time -> OS clock per run (offset = flash_wall - flash_pts; the OBS
clock RATE is validated to 1 ms over 4 s), then computes per run:
  window_pixels_s   first frame after t0 with RGB max-channel diff > 5
  fully_rendered_s  last changed frame (diff > 0.35) with >= 1.5 s quiet after
Writes <run>.analysis.json next to each run json and prints a summary line.
"""
import json, subprocess, sys
import numpy as np

W, H = 384, 216
DIFF_EPS = 1.0   # OBS lossy encode: keyframe noise measures <=0.85 on static
                 # content; real changes measure 2-250. (Lossless x11grab used 0.35.)
BIG = 5.0

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
    C = 14  # corner patch ~14px at 384x216 (~40px video), inset 4px
    means, diffs, corners = [], [0.0], []
    prev = None
    while True:
        buf = p.stdout.read(n)
        if len(buf) < n:
            break
        img = np.frombuffer(buf, dtype=np.uint8).astype(np.int16).reshape(H, W, 3)
        fr = img.reshape(-1, 3)
        means.append(float(img[38:175, 123:260].mean()))  # flash region
        pats = [img[4:4+C, 4:4+C], img[4:4+C, W-4-C:W-4],
                img[H-4-C:H-4, 4:4+C], img[H-4-C:H-4, W-4-C:W-4]]
        corners.append(all(abs(float(q.mean()) - 252.0) > 30 for q in pats))
        if prev is not None:
            diffs.append(float(np.abs(fr - prev).max(axis=1).mean()))
        prev = fr
    p.wait()
    m = min(len(pts), len(means))
    if abs(len(pts) - len(means)) > 2:
        print(json.dumps({"error": f"pts/frames mismatch {len(pts)}/{len(means)}"}))
        sys.exit(1)
    pts, means, diffs = pts[:m], means[:m], diffs[:m]

    # flash anchors: KWin fades windows in/out (~150 ms ramps), so both the
    # flash and app windows darken the region gradually. Discriminator: the
    # flash RECOVERS to white within ~1.2 s (it unmaps); the app stays dark.
    # anchor = fade start (first frame leaving white), closest to map time.
    anchors = []
    i = 1
    while i < m:
        if means[i - 1] > 200 and means[i] <= 200:
            start = i
            j = i + 1
            recovered = False
            while j < m and pts[j] - pts[start] < 1.6:
                if means[j] > 200 and pts[j] - pts[start] > 0.3:
                    recovered = True
                    break
                j += 1
            if recovered and min(means[start:j]) < 100:
                anchors.append(pts[start])
                i = j
            else:
                i = j
        else:
            i += 1
    if len(anchors) < len(runs):
        print(json.dumps({"error": f"{len(runs)} runs but only "
                          f"{len(anchors)} flash anchors found"}))
        sys.exit(1)
    # match: runs are sequential; take the first len(runs) anchors that are
    # ordered consistently with the runs' flash_wall spacing
    anchors = anchors[:len(runs)] if len(anchors) == len(runs) else anchors

    for k, run in enumerate(runs):
        # nearest anchor by expected relative position
        if len(anchors) == len(runs):
            fa = anchors[k]
        else:
            rel = run["flash_wall"] - runs[0]["flash_wall"]
            fa = min(anchors, key=lambda a: abs((a - anchors[0]) - rel))
        off = run["flash_wall"] - fa

        def wall(pv):
            return pv + off
        t0, t_end = run["t0_wall"], run["t0_wall"] + 12.0
        idx = [i for i in range(1, m) if t0 < wall(pts[i]) <= t_end]
        # window ready = all four screen corners covered (apps open maximized;
        # corner deviates >30 from the white baseline)
        wr = next((i for i in idx if corners[i]), None)
        changed = [i for i in idx if diffs[i] > DIFF_EPS]
        res = {"label": run["label"], "anchor_video_t": round(fa, 3),
               "window_ready_s": (round(wall(pts[wr]) - t0, 3)
                                  if wr is not None else None),
               "t_window_xprop": run.get("t_window_xprop")}
        if changed:
            last = changed[-1]
            quiet = [i for i in idx if i > last]
            tail = (wall(pts[quiet[-1]]) if quiet else t_end) - wall(pts[last])
            res["fully_rendered_s"] = (round(wall(pts[last]) - t0, 3)
                                       if tail >= 1.5 else None)
            res["stable_tail_s"] = round(tail, 2)
        else:
            res["fully_rendered_s"] = None
        json.dump(res, open(run["label"] + ".analysis.json", "w"))
        print(json.dumps(res))

if __name__ == "__main__":
    main()

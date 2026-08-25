# bench-tools — reproduction manual

Measurement pipeline behind `javafx-vs-tauri-report.md`: three stages
(startup, interaction, idle footprint), each run for four application
configurations, n = 25 per configuration. Every stage follows the same
shape: a **run driver** launches one instrumented run and writes one JSON,
a **series script** loops driver runs, an **analyzer** turns recordings
into per-run metrics, and an **aggregator** prints per-configuration
distributions (median [min–max] p95).

Series scripts can be run from anywhere (they cd themselves); every
output file lands in `../bench-results/`, which is droppable data —
deleting it loses raw runs, never tooling. Aggregators are run from
inside `bench-results/`.

## Requirements

- Fedora/KDE Plasma (Wayland) with XWayland; a static **white** wallpaper
  on the test monitor (frame-diff analysis assumes the only pixel changes
  are the app and the black flash anchor).
- OBS Studio, configured manually once: record **the test monitor only**
  (its frame edges are what the four-corner criterion checks) at
  **60 fps CFR**, NVENC, output `.mkv` into `~/Videos`. (Hardware encode
  is required: CPU encoding contends with the JVM's parallel JIT and
  pollutes timing.)
- `python3` with `python-xlib` and `numpy`
  (`pip install --user python-xlib numpy`), `ffmpeg`, `xprop`,
  `nvidia-smi`, `kde-inhibit`.
- The two applications: the JavaFX AppImage and the Tauri binary. Set
  `VAULT_BIN` and `MEDIADB_BIN` in the environment (defaults: those
  filenames next to `bench-tools/`). `VAULT_AOT_DIR` points at the
  extracted-AppImage + AOT-cache directory used by the `vaultaot`
  configuration (default `~/vault-aot`); `BENCH_TRACE` overrides the
  trace-log path.
- **Identical datasets imported into both apps before any run**: the
  benchmarks render each app's media library, so both must have scanned
  the same folders to the same item counts (verify in each app's UI or
  database). All runs assume the apps open maximized on the test monitor
  showing that library.

## Configurations

| name | binary | environment |
|---|---|---|
| `vault` | JavaFX AppImage | none |
| `vaultaot` | extracted AppImage `AppRun` (stages 1–2) | `JAVA_TOOL_OPTIONS=-XX:AOTCache=<cache>` |
| `vaultch` | JavaFX AppImage (stage 3) | `JAVA_TOOL_OPTIONS=-XX:+UseCompactObjectHeaders` |
| `mediadb` | Tauri binary | `GDK_BACKEND=x11 WEBKIT_DISABLE_DMABUF_RENDERER=1` |
| `mediadbw` | Tauri binary | `__NV_DISABLE_EXPLICIT_SYNC=1` (native Wayland) |

The Tauri flags are the NVIDIA workarounds established in report §1 —
without them the app renders a blank window (X11) or crashes (Wayland).

**AOT cache setup** (once, before stage 1–2 `vaultaot` runs):

1. `./Vault-x86_64.AppImage --appimage-extract` → `squashfs-root/`
   (the extracted tree avoids re-measuring squashfs mount overhead
   inside the AOT comparison).
2. Training run: `JAVA_TOOL_OPTIONS="-XX:AOTMode=record -XX:AOTConfiguration=vault_aot.conf" squashfs-root/AppRun`
   — exercise the app briefly, close it.
3. Create: `JAVA_TOOL_OPTIONS="-XX:AOTMode=create -XX:AOTConfiguration=vault_aot.conf -XX:AOTCache=vault_aot.aot" squashfs-root/AppRun`
4. Put `squashfs-root/` and `vault_aot.aot` under one directory and set
   `VAULT_AOT_DIR` to it (default `~/vault-aot`).

## Files

| file | role |
|---|---|
| `common.py` | shared infrastructure: binary paths, exact-path process cleanup, process-tree enumeration (`descendants`), tree CPU/PSS/GPU readers, xprop window helpers, pointer parking, black-flash sync window |
| `obs_record.py` | OBS as a subprocess: `start` launches OBS recording and waits for a growing `.mkv`; `stop` ends it and prints the file path |
| `launch_percep.py` | stage-1 run driver: flash → spawn → xprop poll → 12 s CPU snapshot |
| `percep_video.py` | stage-1 analyzer: per-run timings from the session video |
| `percep_agg.py` | stage-1 aggregator |
| `interact2.py` | stage-2 run driver: scripted clicks + scrolling with event log and 1 Hz resource sampler |
| `interact_video.py` | stage-2 analyzer: per-session interaction metrics from the video + event log |
| `interact_agg.py` | stage-2 aggregator |
| `resource2.py` | stage-3 run driver: idle process-tree snapshots (no video, no input) |
| `resource_agg.py` | stage-3 aggregator |
| `percep_series.sh` | stage-1 series: OBS + 1 warmup/config + 25×4 runs + analysis |
| `interact_series.sh` | stage-2 series: same shape, with per-run abort detection and one retry |
| `resource_series.sh` | stage-3 series: 1 warmup/config + 25×4 runs, no OBS |

## Shared mechanisms

- **Timing instrument**: one OBS screen recording per series; all events
  are located in the video by frame-diffing, so wall-clock timestamps and
  frames share one 60 fps timeline (±17 ms, one frame). Before trusting
  any recorder, calibrate: flash a window at OS-clock-scheduled intervals
  and verify the video reproduces the intervals to ±1 frame
  (the xdg-portal/PipeWire path fails this test; OBS passes).
- **Black-flash anchor**: each run flashes a black square (override-
  redirect X window) just before spawning the app; the analyzer finds the
  flash in the video (a brightness dip with recovery — KWin fades windows,
  so the detector accepts ramps) and maps `flash_wall` → video time.
- **Run hygiene** (`common.py`): before every run, leftover app processes
  are killed by exact binary path read from `/proc` (never `pkill -f` — a
  pattern can match the calling shell); the pointer is parked at the
  bottom-left corner of the test monitor with relative XTEST moves, which
  both pins KWin's new-window placement to that monitor and keeps the
  cursor from hovering the app.
- **Process-tree accounting**: every CPU/memory/GPU number sums the app's
  full tree (`descendants()` walks `/proc` PPIDs) — the Tauri tree
  includes WebKitWebProcess/WebKitNetworkProcess. GPU is attributed
  per-process (`nvidia-smi pmon`/`nvidia-smi`), never whole-GPU: whole-GPU
  counters include the compositor and recorder and read ~10× higher.

## Stage 1 — startup

Mechanism, per run (`launch_percep.py <config> <run_id>`): black flash →
spawn → poll `xprop -root _NET_CLIENT_LIST` at 8 ms matching
`_NET_WM_PID` against the process tree (the "registered" event) → wait
12 s → snapshot tree CPU. The analyzer (`percep_video.py <video>
<run>.json...`) then locates per run: the flash (time anchor), **window
visible** (first frame where all four monitor corners are covered by the
app — apps open maximized), and **fully rendered** (last visual change
followed by ≥1.5 s of quiet; RGB max-channel frame diff, threshold
1.0/255 against OBS keyframe noise ≤0.85).

Execute:

```bash
kde-inhibit --screenSaver --power bash bench-tools/percep_series.sh   # ~1 h; hands off mouse
cd bench-results && python3 ../bench-tools/percep_agg.py              # distributions
```

The series script starts/stops OBS itself and runs the analyzer at the
end (`percep_results.jsonl`, per-run `*_pstart_r*.analysis.json`).

## Stage 2 — interaction

Mechanism, per session (`interact2.py <config> <run_id>`): flash → spawn
→ 4 s load wait → focus click → four sidebar folder clicks (0.6 s hover
pause before each, so hover repaints don't contaminate click latency) →
move to grid center → 16 s wheel scrolling at 20 notches/s alternating
direction every 2 s (`SCROLL_HZ` overrides the rate) → 3 s hold. Every
input event is logged with its OS wallclock; a 1 Hz sampler records tree
CPU jiffies, disk reads, and per-process GPU; PSS is snapshotted before
input and after the hold.

Pointer control: X11 configurations use closed-loop XTEST moves with
position verification (KWin's EIS bridge drops *absolute* XTEST motion;
relative deltas work, with gain ≈ the compositor scale, converged
per-move). The Wayland configuration gets no X position feedback over
its surface, so the pointer is calibrated on a temporary X window before
spawn and then driven open-loop.

The analyzer (`interact_video.py <video> <run>.json...`) computes per
session: per-wheel-event input latency (event → next displayed change)
and miss rate (no change within 100 ms), effective FPS and frame pacing
during scroll, click → first change / settled (0.4 s quiet rule, −17 ms
click-frame tolerance), settle after scroll stop, and the cost metrics
from the sampler.

Coordinates: the sidebar click targets and monitor geometry are
constants in `interact2.py` (`CFG`, `MON`) measured for this desktop —
re-measure them for any other machine, theme, or scale (take a
screenshot, read pixel positions). The X11 and Wayland configurations
need separate coordinates (different devicePixelRatio → different
layout).

Two more display-layout constants live elsewhere and must match your
setup: the black-flash window position in `common.py` (must land inside
the recorded monitor), and the flash-region crop in `percep_video.py`
(the flash window's position within the 384×216 downscaled recording —
re-derive it as `pixel / recording_width × 384`). `percep_video.py` also
assumes the wallpaper reads ~white (mean ≈ 252) in the recording, and
pointer parking in `common.py` assumes the test monitor contains the
bottom-left corner of the virtual desktop.

Execute:

```bash
kde-inhibit --screenSaver --power bash bench-tools/interact_series.sh   # ~80 min; hands off mouse
cd bench-results && python3 ../bench-tools/interact_agg.py
```

## Stage 3 — idle footprint

Mechanism, per run (`resource2.py <config> <run_id>`): spawn → settle
(≤5 tree jiffies per 500 ms, 4 consecutive) → full process-tree
snapshots at settle+15/40/65 s → teardown. Each snapshot sums
PSS/RSS/Private_Dirty/Swap (`smaps_rollup`), RssAnon/RssFile/RssShmem/
VmHWM/threads (`/proc/status`), context switches (summed over every
`task/*/status` — the top-level file counts only the leader thread,
which in a JVM is parked and would read zero), fd and socket counts,
disk reads, per-process GPU SM% and GPU memory. Rates (idle CPU,
wakeups/s, reads, PSS drift) are derived over the **s2→s3 window**
(+40 → +65 s): WebKitGTK reaps transient helper processes before +40 s,
and a task exiting earlier would make s1-based deltas negative.

No video and no input — but the mouse must still stay off the app
windows (hover repaints add CPU to the idle window).

Execute:

```bash
kde-inhibit --screenSaver --power bash bench-tools/resource_series.sh   # ~2¼ h
cd bench-results && python3 ../bench-tools/resource_agg.py
```

## Environment quirks worth knowing before touching anything

- `kde-inhibit --screenSaver --power` around every series: display
  idle-sleep silently empties recordings and changes compositor behavior.
- OBS "ready" is detected by the output file growing, not by process
  start; static screens encode at very low bitrate, so any byte-count
  heuristic must be tiny.
- ffmpeg decoding of the session video must use `-fps_mode passthrough`
  everywhere: default rate conversion silently drops/duplicates frames.
- The flash detector must pick the *earliest qualifying* brightness dip,
  not the largest — KWin's fade makes later dips deeper.
- A wheel notch in WebKitGTK starts a ~150–200 ms eased scroll animation
  (`gtk-enable-animations` does **not** disable it); JavaFX scrolls
  discretely. Frame-count metrics across the two reflect these semantics,
  not rendering capability.
- `JAVA_TOOL_OPTIONS` is the zero-code-change way to feed JVM flags into
  a jpackage launcher; verify a flag took effect with
  `jcmd <pid> VM.flags` from a matching JDK.

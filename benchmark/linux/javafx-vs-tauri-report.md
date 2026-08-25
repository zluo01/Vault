# JavaFX vs Tauri: an empirical desktop-stack comparison

Two builds of the same media-library manager — same features, same version, same imported dataset (3 folders; ~70 movies, 106 shows, ~3.7k episodes) — measured head-to-head as a controlled experiment on desktop stacks:

- **JavaFX app** — JavaFX 25.0.3 on Java 25.0.4.1, packaged with jpackage as a self-contained AppImage (43 MB).
- **Tauri app** — Tauri 2.11.2, Rust backend (embedded axum HTTP server) + SolidJS frontend in the system WebKitGTK 2.52.5 webview (25 MB binary).

**TL;DR** — Both implementations were measured head-to-head on startup, interaction, idle footprint, storage, and rendering robustness on Linux/NVIDIA. The full comparison table and conclusion are in §7.

## Environment

| | |
|---|---|
| OS / desktop | Fedora 44, KDE Plasma (Wayland), XWayland for X11 clients |
| CPU / RAM | 24 cores, 62 GB |
| GPU | NVIDIA GeForce RTX 5090 (proprietary driver) — the hardware class affected by the [WebKitGTK DMABUF issue](https://v2.tauri.app/develop/debug/linux-graphics/) |
| Monitor under test | 3840×2160 @ scale 1.5 (XWayland scale 1.75) |
| WebKitGTK | webkit2gtk4.1 2.52.5 (system) |

## The three measured configurations

| Configuration | Flags | Why it exists |
|---|---|---|
| A — JavaFX | XWayland (JavaFX has no Wayland backend) | the JavaFX stack |
| B — Tauri / X11 | `GDK_BACKEND=x11` + `WEBKIT_DISABLE_DMABUF_RENDERER=1` | pipeline-matched head-to-head vs A: same XWayland display path, same capture and input harness — A↔B isolates the stack |
| C — Tauri / Wayland | native + `__NV_DISABLE_EXPLICIT_SYNC=1` | the real-world config; B↔C isolates the rendering path for the same stack |

## 1. Graphics compatibility on NVIDIA (RTX 5090, webkit2gtk 2.52.5)

| Tauri config | Result |
|---|---|
| X11, no flags | **Blank window** + `Failed to create GBM buffer` errors (app alive, renders nothing) |
| X11 + `__NV_DISABLE_EXPLICIT_SYNC=1` | Still blank |
| X11 + `WEBKIT_DISABLE_DMABUF_RENDERER=1` | ✅ Renders (software rasterization — 0 MiB VRAM) |
| Wayland, no flags | **Crash**: `Gdk-Message: Error 71 (Protocol error) dispatching to Wayland display` |
| Wayland + `__NV_DISABLE_EXPLICIT_SYNC=1` | ✅ Renders, hardware DMABUF path (880 MiB VRAM) |

JavaFX (Prism ES2) renders correctly with no flags — it does not use the webview and is not exposed to this bug class. The dependency on the system webview means the rendering backend changes independently of the app; on this GPU, **no default configuration of the Tauri app renders correctly**.

## 2. Startup (perceptual measurement; n = 25 per configuration, warm cache)

Measured **perceptually, from screen footage**, with all four configurations on one
instrument: OBS screen recording (60 fps CFR, NVENC), validated against
ground truth to ±17 ms (one frame) by flashing events at known OS-clock
intervals. Each run is anchored by a black flash fired just before spawn;
the desktop uses a static white wallpaper so every pixel change after spawn
is the app. Three events are measured per run; **fully rendered** serves as
the primary comparison metric, the other two as diagnostic context:

- **window visible** — the first frame in which all four screen corners are
  covered by the application. This is intended as a proxy for the moment a
  first-time user would perceive the window as being on screen: not the
  window's registration with the window manager, not a half-faded or pre-maximize
  intermediate state, but the full window physically present in the frame
  (apps open maximized, so corner coverage implies the complete window).
- **fully rendered** — the last visual change followed by ≥1.5 s of quiet.
  This captures the moment the application is actually *done* from the
  user's point of view: every cover loaded, no images still popping in, the
  screen stable. It is the number that answers "how long until I can start
  using a finished UI?" — the metric a user would quote as the app's
  startup time.
- **registered** (auxiliary) — the moment the window manager registers the
  window (xprop, PID-verified). This is the event commonly reported as
  "time to window", included here for comparability; it is a protocol event
  with no visual counterpart, and the gap row quantifies its distance from
  the first visible state. It also isolates *runtime boot cost*: everything
  before this point is the toolkit/runtime starting up, before a single
  pixel exists.

| metric (s, median [min–max]) | JavaFX | JavaFX + Leyden AOT ¹ | Tauri X11 | Tauri Wayland |
|---|---|---|---|---|
| registered (aux) | 1.142 [1.129–1.152] | 0.521 [0.509–0.536] | 0.091 [0.090–0.093] | — |
| window **visible** | 1.214 [1.182–1.231] | 0.598 [0.564–0.615] | **0.365** [0.332–0.398] | 0.415 [0.398–0.465] |
| **fully rendered** | 1.432 [1.413–1.465] | **0.698** [0.681–0.715] | 0.798 [0.781–0.831] | 0.881 [0.847–0.915] |
| registered→visible gap | **+72 ms** | +70 ms | +275 ms ² | — |
| startup CPU-seconds ³ | 5.37 [5.13–5.59] | 3.70 [3.58–3.86] | **1.22** [1.17–1.24] | 4.83 [4.75–4.88] |

¹ Runs the *extracted* app image (no squashfs mount, measured worth
~−150 ms); as a packaged AppImage expect ~0.85 s fully rendered, at
parity with Tauri X11.

Exclusions: one Tauri X11 run's fully-rendered tail did not stabilize
within the 12 s analysis window; that cell is n = 24. All other cells are
n = 25 with no exclusions.

² Tauri registers its window ~0.09 s after spawn, but that window is blank
white — not visually distinguishable on a white desktop — for another
~275 ms. JavaFX registers an essentially finished window (+72 ms = one
composite + fade). A benchmark reporting only "time to window" therefore
differs from the first user-visible state by ~4× for Tauri while remaining
close for JavaFX.

³ Total CPU time consumed by the application's entire process tree during
startup (all processes and threads, user+system, summed across cores;
snapshotted from /proc 12 s after spawn, n = 25). Where the wall-clock rows
measure how long the user *waits*, this measures how much *work* starting
the app costs — a proxy for efficiency and energy that exposes what
parallelism hides: on a 24-core machine a configuration can consume 4× the CPU of
another yet finish at nearly the same wall time.

Readings:
- **Fastest to fully rendered: JavaFX + AOT (0.70 s)** — ahead of both
  Tauri configurations. The AOT cache is a standard JDK 25 feature
  (Project Leyden, JEP 483/515), applied with zero code changes.
- **Tauri Wayland matches Tauri X11 in wall-clock (0.88 vs 0.80 s) but
  costs 3.7× the CPU** (4.83 vs 1.22 CPU-s): it boots the GPU rendering
  path — accelerated compositing, driver buffer work, three extra
  processes — where Tauri X11 rasterizes in software. 24 cores hide the
  extra work in wall-clock.
- **Startup CPU contradicts the expectation that the JVM is the heavier
  runtime**: JavaFX's 5.37 CPU-s is 4.4× Tauri's software-rendered
  configuration but close to Tauri Wayland's 4.83 — and with the AOT
  cache the JVM app starts with less CPU than the webview app (3.70 vs
  4.83).
- **The two stacks spend startup CPU on different things**: JavaFX on
  runtime warmup — JIT and class loading — which the AOT cache
  compresses; Tauri Wayland on booting the GPU path, which is intrinsic
  to that configuration and has no switch to turn off.
- **Tauri X11 shows a window first (0.37 s), by architecture**: a
  pre-compiled Rust binary plus the system-shared WebKit library leave
  almost no runtime to boot.
- **The waiting experiences are opposite**: Tauri registers a window at
  0.09 s and fills it in place; JavaFX spends ~1.1 s booting its runtime
  before a window can exist, then arrives nearly finished —
  show-then-fill versus build-then-show, at comparable totals.

**Instrumentation note** (for anyone reproducing this on Wayland): screen
recordings made via the xdg-desktop-portal/PipeWire + gstreamer path proved
unusable as a timing instrument on this system — frame delivery is
damage-driven and the stream's clock failed ground-truth calibration (a
known 2.001 s interval measured as 2.2–2.8 s, varying with delivery rate).
Every timing instrument used here was therefore calibrated against
OS-clock-scheduled on-screen events before its measurements were accepted;
OBS was used for the startup series and passed that calibration at ±1 frame.
Full methodology and the calibration procedure: `bench-tools/README.md`.

## 3. Interaction (perceptual measurement; n = 25 sessions per configuration)

Measured on the same calibrated OBS instrument as §2 (60 fps; input events
and frames share one clock). Each session: open the largest folder, three
folder-switch clicks, then 16 s of wheel scrolling at 20 notches/s
(direction alternating every 2 s), then a 3 s hold. Per configuration this yields
~7,900 wheel events and 75 switch clicks. GPU usage is attributed
per-process (`nvidia-smi pmon`, summed over each app's process tree), so
compositor and recorder activity are excluded. Metrics:

- **input latency** — each wheel event to the next displayed change: does
  the UI respond when the user acts. **miss rate** — fraction of wheel
  events with no displayed response within 100 ms: inputs a user would
  perceive as swallowed.
- **effective FPS** and **frame pacing p95** — distinct frames per second
  during scrolling and the evenness of their spacing. These are
  *descriptive*, not a head-to-head score: the toolkits implement different
  scroll semantics (JavaFX repaints once per notch, discretely; WebKitGTK
  animates each notch over ~150 ms), so frame counts reflect the semantics,
  not rendering capability.
- **folder switch, first response / settled** — click to first displayed
  change, and to the view being complete.
- **settle after scroll stop** — last wheel event to a stable screen:
  residual pop-in or animation the user sees after they stop.
- **scroll CPU / per-process GPU / PSS growth** — the cost side.

| metric (median over 25 sessions) | JavaFX | JavaFX + AOT | Tauri X11 | Tauri Wayland |
|---|---|---|---|---|
| input latency median / p95 (ms) | 22 / 42 | 29 / 44 | 27 / 58 | **17** / 158 |
| miss rate (no response in 100 ms) | **0** | **0** | **0** | 10% |
| effective FPS (ceiling 60) | 19.8 | 19.8 | 18.5 | 28.9 |
| frame pacing p95 (ms) ² | 67 | 67 | 67 | 50 |
| switch → first response (ms) | **<17 ¹** | **<17 ¹** | 114 | 752 |
| switch → settled (ms) | <17 | 13 | 120 | 768 |
| settle after scroll stop (s) | **0.0** | **0.0** | 0.2 | 0.6 |
| scroll CPU (cores) | **0.5** | **0.5** | 1.2 | 1.1 |
| per-process GPU (SM %) | 0.5 | 0.6 | 0.0 | 2.0 |
| PSS growth per session (MB) | +285 | +393 | +273 | +280 |

¹ Below the instrument's resolution: the repaint lands in the same 60 fps
frame as the click. ~84 % of JavaFX clicks resolved; the remainder were
lost to the frame boundary and are excluded.

² Not comparable across columns. An input-rate sweep (10/20/30 notches/s)
shows the XWayland configurations' p95 gap is always the input period plus exactly
one display frame — a constant +17 ms of display-path jitter, not a toolkit
property — while Tauri Wayland's 50 ms is its scroll animation's own
render cadence, independent of input rate (27 fps even at 10 notches/s).
The row is reported for completeness; it ranks nothing.

Readings:
- **The largest interaction difference is folder-switch response**:
  JavaFX repaints within a single display frame; Tauri X11 takes
  ~114 ms, Tauri Wayland ~752 ms.
- **Switches render atomically in every configuration**: first response
  and completion nearly coincide — each draws its new view in one step.
- **JavaFX is equal or ahead on every performance metric**: worst-case
  input latency (42 ms p95 vs 58/158), missed inputs (0 vs 10 %),
  switch response, post-scroll settle, and CPU.
- **Tauri Wayland's one better cell carries a cost**: its 17 ms median
  scroll latency comes with a 4× worse tail — its rendering arrives in
  bursts that leave one input in ten without a timely response.
- **Discrete versus animated scrolling is a design difference, not a
  performance one**: JavaFX moves content by the full step in one frame
  per notch; Tauri Wayland eases each notch over ~150–200 ms. JavaFX
  could add such an animation.
- **The animation is why Tauri Wayland feels smoother**: it draws every
  intermediate position (1.4–1.5 frames per notch, frames still flowing
  between inputs), consecutive notches chain into one continuous glide,
  and the eye reads continuous motion as smoother than equally fast
  jumps.
- **The same animation causes the 10 % miss rate**: a notch arriving
  mid-animation merges into the ongoing glide instead of producing an
  immediate distinct change.
- **JavaFX scrolls at less than half the CPU**: ~0.5 cores vs ~1.1–1.2
  for both Tauri configurations.
- **Scrolling is not GPU-bound in any stack**: per-process GPU is
  ≤2 % SM everywhere.
- **The AOT cache does not change interaction** — FPS, latency, and CPU
  all within noise of baseline JavaFX: its effect is confined to
  startup (§2).
- **Session memory growth is similar everywhere** (~+280 MB; +393 MB
  with the AOT cache, which is additionally mapped): image caches
  heating under a full-folder scroll, not a stack differentiator.

**Scope.** All input is synthetic mouse-wheel at a fixed 20 notches/s;
touchpad gestures, kinetic/inertial scrolling, and scrollbar dragging are
untested and can behave differently in both toolkits. Scroll distance per
notch is each toolkit's default and was not normalized, so the apps may
move different pixel distances per event — a caveat on per-notch frame and
CPU comparisons.

**Measurement note.** GPU usage is attributed per-process for a reason
worth stating: whole-GPU utilization sampled during these same workloads
reads an order of magnitude higher (13–24 %), because it includes
compositor and screen-recorder activity — whole-GPU counters are not a
valid proxy for an application's own usage. Timing instrumentation follows
the calibration described in §2's instrumentation note.

## 4. Idle footprint (n = 25 per configuration)

What the application costs while doing nothing: each run spawns the app,
waits for CPU quiescence, and snapshots the full process tree at +15, +40
and +65 s after settling. Footprint values are read at the +40 s snapshot
(steady state); rate metrics are computed over the +40→+65 s window. A
fourth column replaces the AOT column of §§2–3: **JavaFX + compact headers**
runs the same binary with `-XX:+UseCompactObjectHeaders` (JEP 519, final
in JDK 25), which shrinks every Java object header from 12 to 8 bytes —
the JVM-level lever aimed specifically at heap footprint. The flag was
verified active in the bundled JVM via `jcmd VM.flags`. Metrics:

- **PSS** — each process's proportional share of the pages it maps,
  summed over the tree. This is the RAM the application actually costs
  the system: RSS counts shared libraries once per process that maps
  them, which overstates multi-process apps.
- **RSS** — every page currently resident in RAM for the process; a
  shared page counts fully in each process that maps it, so RSS
  overstates the true cost.
- **RSS: private data** — the anonymous part of RSS: heap and data the
  app allocated itself. It exists only in RAM; no file holds a copy.
- **RSS: file-backed** — pages mapped from files on disk (program code,
  libraries). Disk holds a copy, so the kernel can share them between
  processes and drop them under memory pressure without swapping.
- **private dirty** — pages that belong to this app alone and cannot be
  reclaimed or shared: the part of the footprint that competes hardest
  with other applications under memory pressure.
- **peak RSS (VmHWM)** — the high-water mark since launch: the transient
  memory the app demanded on its way to steady state.
- **GPU memory** — VRAM held for the same displayed content.
- **fds / sockets** — open file handles (files, device handles, event
  objects) and network connections held at idle: the OS state the app
  ties up, and a direct view of its process architecture.
- **idle CPU / wakeups** — background processing and timer activity with
  no user input: what the app costs in power (battery) when left open.
- **PSS drift** — memory growth over the idle window: a leak check.

| metric (median over 25 runs) | JavaFX | JavaFX + compact headers | Tauri X11 | Tauri Wayland |
|---|---|---|---|---|
| PSS (MB) | 344 [324–389] | 339 [318–370] | 342 [342–344] | 376 [370–383] |
| private dirty (MB) | 317 | 312 | **197** | 267 |
| RSS (MB) | 403 | 398 | 628 | 639 |
| RSS: private data (MB) | 292 | 287 | 197 | 207 |
| RSS: file-backed, libraries (MB) | 110 | 110 | 347 | 431 |
| peak RSS since launch (MB) | **403** | **398** | 689 | 765 |
| GPU memory (MiB) | **162** | **162** | 0 ¹ | 879 |
| processes | **1** | **1** | 3 | 3 |
| threads | 60 | 60 | 60 | 66 |
| open fds | **72** | **72** | 126 | 192 |
| sockets | **11** | **11** | 32 | 33 |
| idle CPU (%) | 0.20 | 0.20 | **0.00** | **0.00** |
| wakeups per s | 66 | 66 | **0.5** | 2.5 |
| PSS drift over 25 s (MB) | −0.2 | −0.2 | 0.0 | 0.0 |
| swap (MB) | 0 | 0 | 0 | 0 |
| idle disk reads (B) | 0 | 0 | 0 | 0 |

¹ Tauri X11 renders purely in software (`WEBKIT_DISABLE_DMABUF_RENDERER=1`,
§1): 0 MiB reflects CPU rasterization, not GPU efficiency — its frame
buffers appear as ~85 MB of shared memory instead.

Because Tauri's totals sum three processes, a per-process split was
probed at steady state (single runs; totals match the series medians):

| metric | main process (Rust backend + GTK window) | WebKitWebProcess (web engine) | WebKitNetworkProcess |
|---|---|---|---|
| PSS, X11 (MB) | 148 | 173 | 21 |
| PSS, Wayland (MB) | 85 | 270 | 21 |
| private dirty, X11 (MB) | 74 | 109 | 12 |
| private dirty, Wayland (MB) | 52 | 202 | 12 |
| GPU memory, Wayland (MiB) | 132 | 747 | — |

Readings:
- **Idle RAM is at parity**: JavaFX 344 MB PSS, Tauri X11 342, Tauri
  Wayland 376. The expectation that a bundled JVM costs substantially
  more RAM at idle than a Rust-plus-webview stack does not hold here.
- **The 225 MB RSS difference is page accounting, not extra cost**:
  Tauri maps 347–431 MB of file-backed library pages (WebKitGTK, GTK —
  shareable with the rest of the system); JavaFX carries its runtime as
  private heap instead. PSS already weighs this correctly.
- **Private dirty is lower in both Tauri configurations, clearly so
  only on X11**: 197 MB (X11) and 267 MB (Wayland) vs JavaFX's 317 MB
  (the written-to Java heap). This is the floor each footprint can
  shrink to under memory pressure before the system must swap.
- **Most of Tauri's private dirty belongs to the web engine, not the
  Rust backend**: on X11 the main (Rust + GTK) process holds 74 MB
  against 109 + 12 MB in the two WebKit processes; on Wayland the web
  process alone holds 202 MB — the GPU configuration's higher total
  (267 vs 197 MB) is entirely web-engine growth.
- **GPU memory depends on the rendering path, not the stack**: for
  identical content the same Tauri binary holds 0 MiB on X11 (software
  rendering — the CPU draws the window into ~85 MB of memory shared
  with the compositor) and 879 MiB on Wayland (hardware rendering).
- **Most of Tauri Wayland's 879 MiB sits in the web engine**: 747 MiB
  in WebKitWebProcess, which first rasterizes the page into its own GPU
  buffers — per layer, larger than the viewport, double-buffered —
  before the main process (132 MiB) presents the window. JavaFX does
  both in 162 MiB total: the scene draws directly into the window's
  buffers, with no intermediate copy.
- **Both stacks idle at ≤0.2 % of a core** — background CPU does not
  separate them.
- **JavaFX polls for frames**: 62 of its ~66 wakeups/s are the 60 Hz
  *pulse* timer checking a static scene for work. Verified:
  `-Djavafx.animation.pulse=10` drops them to 10/s, and the render
  thread logs 0/s — nothing is ever drawn.
- **The remaining JavaFX wakeups are JVM background threads**: lock
  cleanup 4/s, GC ~1/s.
- **WebKitGTK is woken for frames**: it renders only on the
  compositor's frame callbacks, which stop when nothing changes — so it
  sleeps (0.5–2.5 wakeups/s). Wakeup rate is the metric relevant to
  battery-powered idle.
- **Compact object headers save little at this heap size**: −5 MB
  median (−1.5 %), the whole distribution shifted down (max 370 vs
  389 MB), no cost on any other metric — but below the JVM's ±30 MB
  run-to-run heap-sizing variance visible in the PSS ranges.
- **Neither stack leaks at idle**: swap, idle disk reads, and PSS drift
  are all ~0 in every configuration.
- **fd and socket counts fingerprint the process architecture**:
  - JavaFX, 72 fds / 11 sockets: open jars and fonts, GPU device
    handles, one display and one D-Bus connection.
  - Tauri X11, 126 / 32: three processes, each with its own event loop
    and display/D-Bus connection, unix-socket IPC between them, and ~12
    loopback connections to its own localhost image server (§5).
  - Tauri Wayland, 192 / 33: GPU buffers shared between processes are
    each held as a file descriptor — GPU device handles triple
    (14 → 54).

**Measurement note.** Every value sums the full process tree
(`smaps_rollup` per process; context switches summed over every thread —
`/proc/<pid>/status` alone counts only the main thread, which in a JVM is
parked and would read as zero wakeups). Tauri Wayland briefly
runs 6 processes; WebKitGTK reaps its transient helpers within ~40 s of
launch, which is why rates and footprints are read from the steady
+40→+65 s window. GPU attribution is per-process, as in §3.

## 5. Storage & the image-loading pathway

How each stack moves a local cover image from disk to screen, and what
it leaves on disk while running — a structural difference no RAM metric
shows.

- **JavaFX loads images in-process**: a background loader reads and
  decodes the file, then hands it to the scene graph. No sockets, no
  extra copies on disk.
- **Tauri loads images over local HTTP**: Tauri's built-in route for
  local files — the asset protocol, a custom URI scheme handled in
  Rust — uses a synchronous, blocking handler. Serving covers from an
  HTTP server on `127.0.0.1` instead is fully for asynchronous image
  loading: the HTML `<img>` element's native async and lazy loading
  work as they would on a website. Verified live: 12 established
  loopback connections from WebKitNetworkProcess at idle.

On-disk state while the applications run:

| on disk while running | JavaFX | Tauri |
|---|---|---|
| database | 19.9 MB (incl. 3.7k episode rows) | 1.7 MB |
| cover files | 66 MB (JPEG, incl. episode previews) | 7.4 MB (AVIF) |
| webview HTTP cache | — | 697 MB |
| total | ~86 MB | ~706 MB |

Readings:
- **The webview caches the application's own files a second time**:
  because covers arrive over HTTP, WebKit treats them as web resources
  and writes them into its disk cache — 697 MB duplicating images that
  already sit on the same disk.
- **The duplication follows from the HTTP pathway itself**: any webview
  application serving local media through a local server pays it.
- **With caches warm, neither pathway touches the disk**: idle disk
  reads are zero in every configuration (§4).

## 6. Control experiments

Two experiments test alternative explanations for the §2 results.

### 6a. Is Tauri X11 fast because its layout loads fewer covers? — No.

Under X11 the grid renders 6 covers per row (~20 visible); under Wayland,
8 per row. To test whether the lighter layout explains Tauri X11's
startup times, other densities were forced (`GDK_SCALE=1` +
`GDK_DPI_SCALE=0.571` + window sizing):

| Tauri X11 layout | covers visible | fully rendered (s) | startup CPU (s) |
|---|---|---|---|
| 6 per row, maximized (default) | ~20 | 0.905 | 1.30 |
| 8 per row (Wayland-equivalent viewport) | ~21 | 0.802 | 1.11 |
| 12 per row, maximized | all 33 | 0.970 | 1.17 |

- **Loading all 33 covers instead of ~20 costs +65 ms** (0.905 →
  0.970 s): layout is a ~0.1 s effect, an order of magnitude below the
  differences between configurations (§2).
- **The layout difference itself is a display-scaling effect**: X11
  reports devicePixelRatio 2 and Wayland 1.5, so the same CSS
  breakpoints resolve to different column counts.

### 6b. What if JavaFX also rendered in software? — Slower on every axis.

`-Dprism.order=sw` forces JavaFX's software pipeline (packaged, n = 5):

| JavaFX pipeline | fully rendered (s) | startup CPU (s) | GPU memory (MiB) |
|---|---|---|---|
| GPU (default) | 1.517 | 5.49 | 162 |
| software (`-Dprism.order=sw`) | 1.833 | 7.32 | 0 |

- **JavaFX's GPU pipeline outperforms its software pipeline on every
  measured axis**: software rendering adds ~0.3 s startup and +33 % CPU
  to save 162 MiB of VRAM.
- **Tauri X11's startup speed is therefore not explained by software
  rendering being fast**: it is fast because it boots almost no runtime
  (§2).

## 7. Overall comparison

The major metrics from §§1–5, with the better configuration per row:

| category (median) | JavaFX | Tauri X11 | Tauri Wayland | better |
|---|---|---|---|---|
| renders on NVIDIA defaults (§1) | yes | no — flag required | no — flag required | JavaFX |
| fully rendered (s) (§2) | 1.43 (0.70 with AOT) | 0.80 | 0.88 | JavaFX with AOT; else Tauri X11 |
| startup CPU (CPU-s) (§2) | 5.37 (3.70 with AOT) | 1.22 | 4.83 | Tauri X11 |
| input latency p95 (ms) (§3) | 42 | 58 | 158 | JavaFX |
| missed inputs (§3) | 0 | 0 | 10 % | JavaFX, Tauri X11 |
| folder switch (ms) (§3) | <17 | 114 | 752 | JavaFX |
| scroll CPU (cores) (§3) | 0.5 | 1.2 | 1.1 | JavaFX |
| scroll presentation (§3) | discrete | discrete | animated, 28.9 FPS | — (Wayland rendering path, not a stack property) |
| idle memory, PSS (MB) (§4) | 344 | 342 | 376 | tie |
| private dirty (MB) (§4) | 317 | 197 | 267 | Tauri X11 |
| GPU memory (MiB) (§4) | 162 | 0 (software) | 879 | JavaFX |
| idle wakeups (/s) (§4) | 66 | 0.5 | 2.5 | Tauri |
| disk while running (MB) (§5) | ~86 | ~706 | ~706 | JavaFX |
| download size (MB) | 43, self-contained | 25 + system webview | 25 + system webview | Tauri |

**Conclusion.** This application is rendering-heavy by nature — the
backend only reads a database and scans directories, so what these
experiments compare is each stack's UI layer: the toolkit, the renderer,
and the process architecture. For that kind of workload, JavaFX is
better in most measured categories, and its
wins are the user-facing ones: interaction latency and reliability,
folder-switch response, scroll CPU, GPU memory, on-disk footprint,
rendering on NVIDIA defaults — and, with the AOT cache, startup. Tauri
is better on startup cost and first window, download size, and idle
efficiency (wakeups, unreclaimable memory). These results do not
necessarily carry over to a backend-heavy application — indexing, media
processing, large queries — where Rust-versus-JVM compute would also
matter, and which these experiments do not measure.

## 8. Limitations

One machine, one GPU vendor (the graphics findings are NVIDIA-specific),
warm cache only, and synthetic mouse-wheel input only (§3 scope note).
Tauri Wayland's folder-switch latency saturates its 2 s analysis window.
Full tooling, calibration procedures, and raw per-run data:
[`bench-tools/README.md`](bench-tools/README.md), `bench-results/`.



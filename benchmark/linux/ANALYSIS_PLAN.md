# Analysis plan — JavaFX vs Tauri desktop-stack comparison

## Objective

An empirical comparison of two desktop stacks — JavaFX (bundled JVM,
jpackage AppImage) and Tauri 2 (Rust backend + system WebKitGTK
webview) — using two implementations of the same media-library
application with identical imported datasets. The unit of comparison is
the **stack**: toolkit, renderer, runtime, and process architecture.
Implementation choices (schema, cover formats, UI styling) are out of
scope. Results go to `javafx-vs-tauri-report.md`; reproduction
instructions to `bench-tools/README.md`; raw per-run data to
`bench-results/`.

## Configurations

| name | what it is |
|---|---|
| JavaFX | AppImage on XWayland (JavaFX has no Wayland backend) |
| JavaFX + AOT (startup/interaction) / + compact headers (idle) | same binary with one JVM feature flag, isolating that feature's effect |
| Tauri X11 | `GDK_BACKEND=x11` + `WEBKIT_DISABLE_DMABUF_RENDERER=1` — pipeline-matched against JavaFX: same display path, same instrument |
| Tauri Wayland | native Wayland + `__NV_DISABLE_EXPLICIT_SYNC=1` — the GPU-accelerated real-world path |

JavaFX ↔ Tauri X11 isolates the stack (same rendering path class);
Tauri X11 ↔ Tauri Wayland isolates the rendering path (same stack).

## Measured categories

1. **Graphics compatibility** (report §1) — does each stack render on
   NVIDIA hardware without workaround flags; failure modes per
   configuration.
2. **Startup** (§2) — perceptual, from screen recordings: window
   visible (four-corner coverage), **fully rendered** (last visual
   change + quiet period; the primary metric), window-manager
   registration as diagnostic, and startup CPU-seconds as the work/cost
   axis.
3. **Interaction** (§3) — scripted identical input across
   configurations: per-event input latency and miss rate, effective FPS
   and pacing during scrolling, folder-switch first-response/settled,
   post-scroll settle, and cost (CPU, per-process GPU, memory growth).
4. **Idle footprint** (§4) — process-tree snapshots at steady state:
   PSS, RSS composition (private/file-backed/shmem), private dirty,
   peak RSS, GPU memory, threads/fds/sockets, idle CPU, wakeups/s,
   drift/swap/reads as leak checks; per-process split for the
   multi-process stack.
5. **Storage & image pathway** (§5) — how each stack moves local images
   to the screen, and the on-disk state while running.
6. **Control experiments** (§6) — alternative explanations tested and
   ruled in or out (layout density, software rendering).

## Method principles

- **Instruments are validated before use, with a hard gate**: any
  timing recorder must reproduce OS-clock-scheduled ground-truth events
  to ±1 frame before its measurements are accepted. All tooling is
  built and validated on live runs before a measured series starts.
- **Perceptual measurement**: startup and interaction timings come from
  screen footage (what a user sees), anchored to OS wallclock by a
  per-run flash event — not from process or window-manager events,
  which are diagnostics only.
- **Distributions, not points**: n = 25 per configuration per series;
  report median with [min–max] and p95. Warmup runs precede each series
  and are discarded; series alternate configurations to spread ambient
  drift.
- **Whole-tree accounting**: every CPU/memory/GPU figure sums the app's
  full process tree; GPU is attributed per-process, never whole-GPU.
- **Uniform pipeline**: all configurations in a series run under the
  same instrument, choreography, and analysis code.
- Warm cache throughout; cold-start is out of scope.

## Statistical treatment

Median [min–max] with p95 per metric per configuration; with n = 25,
p99 ≈ max and is not reported separately. Runs are excluded only for
evidenced instrument failure (logged), never for being outliers.

## Deliverables

- `javafx-vs-tauri-report.md` — the comparison (tables anchored to raw
  data, one claim per reading, §7 overall comparison + conclusion,
  §8 limitations).
- `bench-tools/` — the complete pipeline (driver → series → analyzer →
  aggregator per stage) with a reproduction README.
- `bench-results/` — raw per-run JSON, recordings, per-run analysis
  files (droppable data).
- `analysis-trace.log` — timestamped journal of every executed step.

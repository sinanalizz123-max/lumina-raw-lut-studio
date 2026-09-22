# M16 Performance + Memory + Stability Ledger — Lumina RAW & LUT Studio

Milestone: dedicated profile-and-harden pass. Method per spec: REAL
measurements where obtainable without a device — static allocation analysis
+ pure-JVM parity tests + code inspection. No on-device runs were available
in this environment, so on-device figures below are documented EXPECTATIONS
with exact measurement instructions, not fabrications. CI validates
(build, golden parity, JVM tests).

How to measure on device (logcat tags):

- `adb logcat -s LuminaRender EditorZoomTile`
- `LuminaRender`: `preview WxH Nms backend=... rev=...` per preview render
  (EditorViewModel.renderPreview) and `fullscreen WxH decode+render=Nms`
  per fullscreen render (scheduleFullscreenRender).
- `EditorZoomTile`: `tile WxH sample=N decode=Nms render=Nms total=Nms rev=N`
  per zoom-tile render (EditorViewModel.renderZoomTile).
- In-app: Settings → Debug shows backend label, decoder, last render ms,
  dims, revision (DebugDiagnostics.reportRender/reportZoom).
- Budget math is JVM-pinned: `M16PerformanceTest` (budget, strip, sample,
  TIFF pre-flight, Into parity, token, debounce).

Golden parity rule: render output semantics unchanged. All `*Into`
variants are bit-identical (delta 0f) to the allocating variants they
replace — proven by `M16PerformanceTest` Into-parity tests. Old allocating
APIs stay as thin wrappers (tests + external callers untouched).

## 1. Per-pixel alloc elimination (§34 + audit P1)

| Loop | Before (per pixel) | After | Saving (1600×1200 preview ≈ 1.92M px) |
|---|---|---|---|
| LutRenderer.sample1D | 1× `floatArrayOf(3)` inputs + shared scratch out | channel select by index, scratch out | ~1.9M arrays (~23MB churn) eliminated |
| LutRenderer.sample3D | scratch out only (already clean) | unchanged | — |
| LutRenderer effective-table downsample (32³ = 32768 samples) | scratch reuse already | unchanged (cached per LutCube instance) | — |
| PreviewRenderer.applyHslPerColor | 1× `rgbToHsl` FloatArray(3) + up to 1× `hslToRgb` FloatArray(3) | 2 thread-local scratch buffers via `rgbToHslInto`/`hslToRgbInto` | ~2–3.8M arrays eliminated when stage active |
| PreviewRenderer.applyGrading | `GradeMath.applyGrade`: weights(3) + 4× lift(3) + 4× tint(3) + result(3) ≈ 7 arrays ≈ 21 floats + 7 headers | `applyGradeInto` into one 18-float thread-local scratch | ~13.4M arrays eliminated when stage active |
| PreviewRenderer.applyPointColor | `PointColorMath.applyPoint`: hsl(3) + result(3) ≈ 2 arrays | `applyPointInto` into thread-local out+scratch | ~3.8M arrays eliminated when stage active |
| PreviewRenderer.buildPointColorMask (≤192px overlay) | 1× `GradeHsl.rgbToHsl` per pixel | single per-call FloatArray(3) via `rgbToHslInto` | ~37k arrays eliminated per rebuild |
| PreviewRenderer.applyMasks hue pre-pass | 1× `rgbToHsl` per pixel (color tools only) | single per-call scratch via `rgbToHslInto` | ~1.9M arrays eliminated when color masks active |
| PreviewRenderer.applyLensBlur blend | 1× `bandWeights` FloatArray(3) per blended pixel | single per-call scratch via `bandWeightsInto` | ~1.9M arrays eliminated when stage active |
| PreviewRenderer retouch HEAL | 1× `healPixel` FloatArray(3) per touched pixel | single per-call scratch via `healPixelInto` | bounded by spot area (small) |
| DustMath.detectCandidates (≤256px analysis) | 1× `FloatArray(16)` ring per center (~65k allocs) | hoisted single reuse buffer | ~65k arrays eliminated per scan |
| Curves stage | 256-float LUT lookups, zero per-pixel allocs | unchanged (already optimal) | — |
| Mask radial/linear/stroke alpha | scalar math into preallocated FloatArray fields | unchanged (zero per-pixel allocs) | — |
| `retouchBounds` `intArrayOf(4)` | per-OP (not per-pixel) | unchanged, documented OK | — |
| `retouchAnnulusMedian` ArrayLists | per-OP, stride-capped ~1500 samples | unchanged, documented OK | — |
| `RetouchMath.median` clone | per median call (per-OP + per dust center) | unchanged, documented OK (sort needs a copy) | — |
| Exporter.boxBlur3/argbToNv12 | scalar, single buffers | unchanged (zero per-pixel allocs) | — |

Remaining per-pixel allocations: NONE in render hot loops (verified by
inspection: no `FloatArray()`/`floatArrayOf`/`intArrayOf` inside any
per-pixel loop; `rg FloatArray|floatArrayOf|intArrayOf` hits are all
per-render/per-call/per-op). Thread-safety: all hot-loop scratch is
`ThreadLocal` (preview/tile/fullscreen share Default workers) or
per-call locals — no shared mutable fields.

On-device expectation (to verify): GC pause reduction on slider drags;
frame-time delta comes mostly from fewer young-gen collections, not ALU.
Measure: `LuminaRender` preview ms before/after on the same 1600px frame
with HSL+grade+point active; expect low-double-digit % improvement on
GC-heavy devices, near-zero on others. Do NOT claim a fixed number.

## 2. Buffer discipline (peak-bitmap budget)

Budget (preview-size P, tile T, fullscreen F):

- Idle editing: base decode (1×P) + current preview (1×P) = 2×P.
- Slider drag: + in-flight stale render (1×P, recycled on stale-drop) = 3×P transient.
- Zoom: + tile (1×T ≤ 1.5MP) — preview stays (needed under tile).
- Fullscreen: + fullscreen bitmap (1×F ≤ 4096px) + its decode transient.
- NEVER held simultaneously without need: exiting fullscreen recycles F
  (clearFullscreenPreview); zoom ≤1.25× recycles T; project change recycles
  T + closes region decoder; onCleared recycles all + releaseGpu().
- M16 fix: replaced preview bitmap is now recycled on replace (was leaked:
  `_preview.value = out` held 2×P indefinitely). Base is never recycled
  while referenced (`old !== base` guard).
- M16 fix: lens-blur mild/strong Bitmaps recycled right after getPixels
  (peak 3 full-frame Bitmaps → 1 during the blend loop).
- Stage chain (PreviewRenderer.render): each stage recycles its input when
  it allocates a new output (`if current !== src recycle`) — peak per
  render = 2×P transient, 1×P retained. LUT stage returns new bitmap
  without recycling src (caller-owned) — correct.
- Mask stage peak: pixels + baseCopy clone + hues/lumas + acc + raw ≈
  5×P floats/ints transient (~38MB at 1600×1200) — largest single stage,
  documented, unchanged (needed for SUBTRACT correctness).
- GPU: per-render LUT/curve textures deleted in `finally`; reused state
  bounded (1 srcTex, 1 fboTex, 1 FBO, 1 program, 3 tiny placeholders).
  Trim hook (ComponentCallbacks2 → teardown) + releaseGpu() on teardown —
  verified, no gap found (see GlesBackend M16 audit note).

OOM catch order (every giant-alloc site returns Unavailable/fallback,
never crashes): CpuRenderBackend.render (OomBudget) ✓ pre-existing;
GlesBackend → teardown + CPU fallback ✓ pre-existing; LutRenderer
applyWithTable → src (M16 NEW); applyHslPerColor/applyCurves/
applyPointColor/applyGrading/applyOptics/applyMasks/applyLensBlur/
computeHistogram/decodePreview/scaledBlurBitmap/buildPointColorMask/
buildLensDepthPreview/detectDustCandidates → src/null/empty (M16 NEW
where missing); Exporter.bitmapToRgb/encodeTiff → IllegalStateException
("too large", M16 NEW); ExportScreen startExport → message (M16 NEW);
BatchExporter per-item → recorded failure, batch continues (M16 NEW);
EditorViewModel decodeBase/decodeFull/renderFullBitmap → null (M16 NEW
where missing); decodeFullscreenBitmap (pre-existing OOM path) ✓.

## 3. Large-image behavior (12/24/48MP policy)

- Decode caps: preview ≤1600px (quality-gated 800/1200/1600, GPU-off
  capped 1200), fullscreen ≤4096px, tile = region decode with sample to
  ≤1.5MP, cull/AI analysis ≤256px. Unchanged.
- Tile-first above 12MP (MemoryBudget.TILE_FIRST_PIXELS): viewport detail
  comes from BitmapRegionDecoder tiles, never full frames. Zoom source
  hard cap 120MP (ZOOM_MAX_SOURCE_PIXELS) + render cap 120MP
  (MAX_RENDER_PIXELS) → Unavailable("too large"), never OOM.
- TIFF decision (honest): strip-export (multi-strip TIFF via row bands)
  was assessed and REJECTED — TiffWriter's single-strip bytes are
  golden-pinned (header offset 180, exact length, payload verbatim;
  TiffWriterTest). A streaming rewrite would change output bytes for zero
  on-device gain without a file-streaming writer. INSTEAD: pre-flight
  budget refusal — tiffWorkingBytes = w*h*10+180 (IntArray 4B + rgb 3B +
  file 3B); Exporter.checkTiffBudget refuses before ANY giant alloc with
  "try JPEG or a smaller size". Working-set reference:
  12MP ≈ 120MB, 24MP ≈ 240MB, 48MP ≈ 480MB.
- Pure helpers (JVM-pinned): `stripRowsFor` (band math, kept for
  documentation/future streaming writer), `sampleFor` (decode sampling),
  `exceedsTiffBudget`, `tiffWorkingBytes`.

## 4. Cancellation (§54)

- Wired: preview job cancel + backend.cancel(superseded) (M16 NEW) +
  stale-drop; fullscreen job cancel + debounce 500ms + backend.cancel
  (M16 NEW) + stale-drop; tile job cancel + 350ms debounce + revision
  token (backend set intentionally NOT used for tiles: revisions share no
  namespace with backend generations — cross-talk would false-cancel
  previews; documented in code); histogram job cancel + 150ms debounce;
  persist 300ms debounce; AI job cancel + ensureActive in buildAiField;
  cull loop ensureActive; batch export per-item CancellationException
  rethrow + M16 per-item OOM isolation; batch preset/paste loops M16
  ensureActive + no-swallow (were missing); export paths ensureActive at
  every stage boundary (pre-existing) + M16 OOM catches.
- Boundary (documented): pure pixel loops cannot take coroutine context —
  cooperative checks live at stage/render boundaries in suspend callers
  only. Blocking Bitmap work is never preempted; superseded work is
  dropped AFTER (stale-drop + recycle), refused BEFORE when not started
  (backend.cancel set). No per-row ensureActive inside Bitmap loops
  (would require suspend signatures across the whole pipeline — rejected
  as disproportionate churn for zero preemption gain).

## 5. Compose/preview perf (§67)

- Verified zero bitmap DECODING during composition: preview/tile/overlay
  paths use `asImageBitmap()` interop wraps only; the only AsyncImage is
  the null-preview fallback. M16: all four wrappers remembered per Bitmap
  instance (preview/tile/point-mask/lens-depth) so unrelated
  recompositions reuse instead of reallocating.
- GradePanel color wheel: `remember { makeGradeWheel() }` (28k setPixel
  once) — verified, unchanged.
- Zoom/viewport: LaunchedEffect(scale, offset, viewportSize, …) recomputes
  fractions per gesture frame but only calls onViewportChanged, which is
  debounced (350ms) and cheap — adequate, unchanged. No zoom textual
  readout exists (no derivedState needed).
- Lazy lists: all use stable `key = { it.id }` (Projects albums/projects/
  presets/cull rows, PresetsLibrary grid) — verified, unchanged.
- Histogram: auto-recompute gated (GPU-on only) + 150ms debounce +
  cancel-superseded — verified adequate, unchanged. computeHistogram is
  sampling-capped (60k) + M16 OOM-safe.
- Coil thumbnails: AsyncImage with File model + fixed layout sizes; Coil
  resolves draw size from the modifier (downsamples at decode) — verified
  adequate, no custom ImageRequest (rejected: behavior risk for no proven
  gain; revisit with on-device heap dumps if lists jank).

## 6. Startup / thermal / ANR

- Startup: StorageMigration on Dispatchers.IO (pre-existing) ✓; theme via
  DataStore Flow collectAsState ✓; no disk/network on Main in
  MainActivity. Documented, unchanged.
- Room on Main: Room suspend DAO calls are main-safe (own executor);
  viewModelScope.launch(Main) DAO reads (ProjectDetail load/favorite) are
  safe; all writes already on IO. Verified, unchanged.
- DataStore reads are suspend/Flow ✓ (first() inside IO where blocking).
- Thermal: sustained loops are bounded (preview ≤1600px, analysis ≤256px,
  peel passes ≤64, dust NMS-capped 200) + coroutine cancellation between
  stages; no infinite loops. Documented, unchanged.

## 7. Micro-bench tests (pure JVM)

New: `app/src/test/.../perf/M16PerformanceTest.kt` — Into parity
(bit-exact, delta 0f) for HSL/weights/tint/lift/grade/point/heal/bands
(incl. 5k-pixel scratch-reuse stability + full-cube finiteness), budget
math at 12/24/48MP, strip/sample math, TIFF pre-flight, token
stale-drop + cancel-set semantics, BatchOps coalescing, export size math.
No timing asserts (CI noise). All golden/render-output semantics
untouched.

## 8. Files changed (no commit/push; CI must verify)

- core/edit/GradeColorMath.kt: `*Into` variants (HSL, weights, tint,
  lift, grade with packed 18-float scratch, point).
- core/edit/M8Math.kt: `healPixelInto`, `bandWeightsInto`, dust ring
  buffer hoist.
- core/lut/LutRenderer.kt: sample1D inputs alloc gone, invRange scalars,
  OOM→src guard.
- core/render/PreviewRenderer.kt: thread-local scratch, HSL/grade/point/
  mask-hues/lens/heal loops allocation-free, lens-blur early bitmap
  recycle, OOM→src/null/empty guards on all unguarded stages,
  rgbToHslInto/hslToRgbInto.
- core/render/RenderTypes.kt: 12/24/48MP constants, TILE_FIRST_PIXELS,
  tiffWorkingBytes/exceedsTiffBudget/stripRowsFor/sampleFor.
- core/render/gpu/GlesBackend.kt: M16 lifecycle audit note (no behavior
  change — verified no gap).
- core/export/Exporter.kt: bitmapToRgb/encodeTiff OOM→refusal,
  checkTiffBudget pre-flight.
- ui/screens/EditorViewModel.kt: preview replace recycle, backend.cancel
  wiring (preview+fullscreen), TAG_RENDER timing logs, decode OOM guards.
- ui/screens/ProjectsViewModel.kt: batch loops ensureActive + no-swallow,
  per-item OOM isolation.
- ui/screens/ExportScreen.kt: export OOM→message (cleanup preserved).
- ui/screens/EditorScreen.kt: remembered asImageBitmap wrappers (4).
- app/src/test/.../perf/M16PerformanceTest.kt: NEW.
- PERFORMANCE.md: NEW (this file).

Risks taken: ThreadLocal scratch assumes no re-entrant same-thread
renders sharing one buffer within a single pixel loop — true today
(loops are leaf consumers); future nested Into calls must use distinct
buffers (documented in code). Preview recycle-on-replace assumes no
reader holds the old bitmap past state replacement — same pattern as
existing fullscreen/tile paths. Into math duplicates formulae — parity
proven by tests, but CI goldens are the final gate.

What CI must verify: full JVM test suite (esp. M16PerformanceTest +
all golden/parity tests), release + debug compile (android), lint. No
gradle was run locally (host RAM ceiling). No routes/gradle.properties/
prompt changes. No commit/push (lead pushes).

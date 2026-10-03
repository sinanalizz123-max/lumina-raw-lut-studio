# Lumina RAW & LUT Studio

Android photo editor focused on RAW development and LUT-based color grading.
Built with Kotlin + Jetpack Compose (single `:app` module).

Current version: `0.1.0-phase0` (pre-production hardening — see
[PERFORMANCE.md](PERFORMANCE.md) and the release checklist below).

## Features

- RAW / DNG develop path (`core/raw`: `DngParser`, `DngDevelop`,
  `DngCapabilities`, `LosslessJpeg`) with 12/24/48MP memory policy,
  tile-first zoom above 12MP, and OOM-safe fallbacks (never a crash)
- 1D + 3D LUT engine (`core/lut`: `.cube` parsing, trilinear sampling,
  effective-table caching, built-in presets, import/export round-trip)
- Color: grading, HSL per-color, point color, curves, color pipeline
  (`core/edit`, `core/render/ColorPipeline`)
- Masks (radial/linear/stroke), lens/optics effects, retouch/heal,
  dust detection
- HDR merge + panorama stitching (`core/merge`)
- AI-assisted masks and culling (`core/ai`, `core/library/CullEngine`)
- Batch export incl. multi-item isolation (`core/export/BatchExporter`,
  `Exporter`, JPEG + single-strip TIFF with pre-flight budget refusal)
- Projects, presets (+ share format), edit-history log, settings —
  Room + DataStore (`core/data`)
- Receives images from Android Share / Open-with
  (`VIEW`, `SEND`, `SEND_MULTIPLE` for `image/*` in `AndroidManifest.xml`)
- 41 JVM unit-test files incl. golden/parity coverage
  (`app/src/test`, e.g. `perf/M16PerformanceTest`)

## Requirements

- JDK 21 (CI uses Temurin 21)
- Android SDK: `platforms;android-35`, `build-tools;36.0.0`
- Android 8.0+ (minSdk 26) device or emulator to run

Key versions (`gradle/libs.versions.toml`): AGP 9.4.1, Kotlin 2.2.20,
Compose BOM 2025.10.01, Navigation 2.9.3, Room 2.7.2, DataStore 1.1.1,
Coil 2.6.0, Robolectric 4.17.

## Build / test

```bash
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # unsigned release APK (CI gate)
./gradlew :app:testDebugUnitTest        # JVM unit tests (goldens + parity)
./gradlew :app:lintDebug                # lint (abortOnError = true)
```

Artifacts: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/release/app-release-unsigned.apk`.
APK/AAB binaries are git-ignored (see `.gitignore`).

## Run

Open in Android Studio, let Gradle sync, run the `app` configuration on
an Android 8.0+ device. Or `./gradlew :app:installDebug`.
To try editing: share any image from the gallery → Lumina, or open Lumina
and pick a project/image from the library.

Debug telemetry: Settings → Debug shows render backend, decoder, last
render ms/dims/revision. Logcat tags:
`adb logcat -s LuminaRender EditorZoomTile` (format documented in
[PERFORMANCE.md](PERFORMANCE.md)).

## Architecture

```
app/src/main/java/com/lumina/studio/
├── MainActivity.kt            # single-activity host, intent entry
├── navigation/                # Nav graph / routes
├── ui/                        # Compose screens (editor, presets, screens)
│                              # + EditorViewModel, ProjectsViewModel, ExportScreen
└── core/
    ├── raw/                   # DNG parse/develop, sampling policy
    ├── lut/                   # .cube parse, LutCube, LutRenderer, presets
    ├── edit/                  # GradeColorMath, M7/M8 math, EditStack
    ├── render/                # PreviewRenderer, CpuRenderBackend,
    │                          # gpu/GlesBackend, ColorPipeline, MaskEngine
    ├── export/                # Exporter, BatchExporter, TIFF, sidecars
    ├── merge/                 # HDR merge, panorama
    ├── ai/                    # mask/cull heuristics + caches
    ├── library/               # albums, culling, filters
    ├── presets/               # preset model + share format
    ├── batch/                 # batch ops/coalescing
    ├── data/                  # Room (entities/dao/migrations),
    │                          # DataStore settings, project store, cache files
    ├── design/                # theme + design-system components
    └── util/
```

Render rule: output semantics are golden-pinned — `*Into` allocation-free
variants must stay bit-identical to the allocating originals
(`M16PerformanceTest`). Room schemas export to `app/schemas`
(`room.schemaLocation`); commit schema JSON on every entity change.

## Release checklist (to 1.0)

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Flip `release.isMinifyEnabled = true`, add targeted rules to
   `app/proguard-rules.pro` from R8 warnings, and verify a signed
   release build + smoke test (install, open/share an image, export
   JPEG + TIFF, batch export).
3. Commit exported Room schemas under `app/schemas/`.
4. Full CI green: debug + release assemble, unit tests, lint.
5. RAW fixture matrix (DNG/CR2/CR3/NEF/ARW/RAF/ORF/RW2): metadata,
   thumbnail, preview, full decode, orientation, WB, no OOM.
6. Add release notes + app-listing assets; confirm backup rules
   (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`) exclude
   caches/derived bitmaps.
7. No LICENSE file is present in the repo — add one before publishing
   outside GitHub.

## CI

`.github/workflows/ci.yml` (push to `main`/`fix/**`, PRs, manual):
JDK 21 setup → SDK install → `assembleDebug` → `testDebugUnitTest` →
`lintDebug` → `assembleRelease` → APK artifacts.

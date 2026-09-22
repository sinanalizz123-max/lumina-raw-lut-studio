package com.lumina.studio.core.merge

/**
 * M17 open-source research verdict (HDR merge + panorama, prompt-4 vetting).
 *
 * Goal: OFFLINE, ARM64, minSdk 26, CI-buildable merge/stitch with no
 * enormous or abandoned dependency. Researched Sep 2026; see milestone
 * report for the full write-up.
 *
 * Verdict table:
 *
 * | Candidate | Android | ARM64 | minSdk<=26 | License | Maintained | Size | Offline | CI | Verdict |
 * |---|---|---|---|---|---|---|---|---|---|
 * | OpenCV Android SDK (photo: AlignMTB + MergeMertens; stitching module) | yes | yes | yes | Apache 2.0 | yes | ENORMOUS: 90-180MB SDK, tens of MB native (.so per ABI) into the APK even stripped | yes | painful (manual JNI packaging, NDK pinning) | REJECT: textbook "enormous dependency to check the feature box"; stitching_detailed is also fragile on hand-held phone captures |
 * | Community OpenCV Maven (com.quickbirdstudios:opencv) | yes | yes | yes | Apache 2.0 | NO — unpublished for years, tracks an old OpenCV | same native bloat as above | yes | yes | REJECT: abandoned AND enormous |
 * | GPUImage / gpuimage-plus filter libs | yes | yes | yes | MIT/BSD | mixed | small | yes | yes | REJECT: single-image filters only — no multi-frame align, no Mertens, no homography; would still need the whole pipeline written around them |
 * | Senses/dermandar-style panorama SDKs, BabelColor, commercial stitchers | n/a | n/a | n/a | proprietary/commercial | n/a | n/a | no | no | REJECT: license + offline + size all fail |
 * | Native implementation (this milestone: NCC translation align, Mertens fusion w/ 2-level Laplacian, cylindrical + feather stitch) | yes (pure Kotlin, minSdk 26, zero JNI) | yes (no native code at all) | yes | n/a (first-party) | n/a | +0 bytes of dependencies | yes | yes | SELECTED |
 *
 * Decision: NO new dependency in M17. Ship [AndroidHdrMerger] behind
 * [HdrMerger] and [AndroidPanoramaStitcher] behind [PanoramaStitcher],
 * both delegating to the pure math in [AlignMath]/[HdrMath]/[ExposureEv]/
 * [PanoramaMath] pinned by the M17 JVM suite. APK impact of M17: 0 bytes
 * of new dependencies, 0 MB of native/model files.
 *
 * What CI must verify: dependency graph unchanged (no new artifacts), plus
 * the pure-JVM M17 test suite. What CI CANNOT verify (needs a device):
 * on-device fusion/stitch on real brackets, timing/memory on 12MP
 * captures, orientation-normalized merges — see the milestone report's
 * on-device validation list.
 */
object MergeResearch {
    /** Backends selected by the verdict above. Pinned by JVM tests. */
    const val HDR_BACKEND = "native-mertens"
    const val PANORAMA_BACKEND = "native-feather"

    const val HDR_BACKEND_NAME = "Native Mertens fusion (translation align, 2-level Laplacian)"
    const val PANORAMA_BACKEND_NAME = "Native stitch (cylindrical warp, feather blend, auto-crop)"
    const val DEPENDENCY_NOTE = "0 new dependencies — pure Kotlin, no JNI, minSdk 26"
    const val OFFLINE_NOTE = "Runs fully offline on this device."
}

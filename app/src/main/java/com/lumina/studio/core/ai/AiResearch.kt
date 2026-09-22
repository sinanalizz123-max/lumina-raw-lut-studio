package com.lumina.studio.core.ai

/**
 * M12 on-device AI-selection research verdict (§§24-25, prompt-3 C6 vetting).
 *
 * Goal: OFFLINE on-device subject/sky segmentation on ARM64, minSdk 26,
 * CI-buildable, with an honest fallback when nothing qualifies.
 *
 * Verdict table (researched Sep 2026; see milestone report for sources):
 *
 * | Candidate | Android compat | ARM64 | minSdk<=26 | License | Maintained | AAR+model size | Offline | CI-buildable | Verdict |
 * |---|---|---|---|---|---|---|---|---|---|
 * | (a) TFLite org.tensorflow:tensorflow-lite + SelfieSeg/DeepLabV3 | yes | yes | yes (API 19+) | Apache 2.0 | NO — maintenance mode, security fixes only; active dev moved to LiteRT (com.google.ai.edge.litert) | runtime ~1MB + selfie ~1MB (person-only, NO sky) / DeepLabV3 ~2-10MB | yes if bundled | yes | REJECT as specified: pinned artifact is deprecated; selfie model cannot do sky; DeepLabV3 exceeds the <5MB bundle rule |
 * | (a') LiteRT successor (com.google.ai.edge.litert) | yes | yes | yes | Apache 2.0 | yes (active) | runtime + still needs a model; no vetted sky/subject model <5MB with a compatible license found | yes if bundled | yes | REJECT for M12: re-vetting a new artifact + model licensing/size work does not fit this milestone; heuristic ships instead, LiteRT stays a documented future option |
 * | (b) MediaPipe Tasks Vision ImageSegmenter (com.google.mediapipe:tasks-vision) | yes | yes | yes (needs SDK 24+, minSdk 26 OK) | Apache 2.0 | yes (active) | AAR tens of MB native + deeplab_v3 model ~10MB+, first-run download | NO — offline only AFTER fetch | yes | REJECT: model download violates the offline requirement; AAR bloat; no sky/subject model <5MB |
 * | (c) PyTorch Mobile (org.pytorch:pytorch_android) | yes | yes | yes | BSD-style | NO — "no longer actively supported", demo apps archived, successor is ExecuTorch | tens of MB | yes | risky | REJECT: heavy + unmaintained (spec's expected rejection) |
 * | ML Kit face detection (for a subject heuristic) | yes | yes | yes | proprietary ToS | yes | unbundled via Play Services | NO — needs Play Services | yes | REJECT: Play Services dependency violates offline (spec's expected rejection) |
 * | ML Kit selfie segmentation (bundled, +4.5MB, beta, person-only) | yes | yes | API 23+ | proprietary ToS | beta, no SLA | +4.5MB, person-only (NO sky) | yes (bundled) | yes | REJECT: beta/no-SLA, person-only so sky stays unsolved, +4.5MB for half the feature |
 * | ML Kit subject segmentation (unbundled) | yes | yes | API 24+ | proprietary ToS | beta, no SLA | ~200KB shim, model via Play Services download | NO — Play Services download before first use | yes | REJECT: Play Services + download violates offline; beta/no-SLA |
 *
 * Decision: NO new dependency in M12. Ship [HeuristicAiProcessor] behind the
 * [AiProcessor] interface, with results cached per project+source-hash
 * ([AiMaskCache]) and UI copy that says "heuristic", never "AI".
 * APK impact of M12: 0 bytes of new dependencies, 0 MB of model files.
 *
 * What CI must verify: dependency resolution unchanged (no new artifacts),
 * APK size delta ~= 0, plus the pure-JVM M12 test suite.
 */
object AiResearch {
    /** Backend selected by the verdict above. Pinned by JVM tests. */
    const val SELECTED_BACKEND = "heuristic"

    const val BACKEND_NAME = "Heuristic selector"
    const val BACKEND_STATUS = "heuristic — no ML model on device"
    const val MODEL_SIZE_NOTE = "0 MB — no model file, nothing to download"
    const val OFFLINE_NOTE =
        "Runs fully offline on this device. Selections are saliency/color " +
            "approximations, not AI segmentation — refine with Feather or a manual mask."
}

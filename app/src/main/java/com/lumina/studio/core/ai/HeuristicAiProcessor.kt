package com.lumina.studio.core.ai

/**
 * M12 shipped [AiProcessor]: offline heuristic selector, 0 MB model.
 * Always available for valid inputs (null only on empty/mismatched input);
 * UI copy must call it "heuristic", never "AI" (see [AiResearch]).
 */
object HeuristicAiProcessor : AiProcessor {
    override val name: String = AiResearch.BACKEND_NAME
    override val status: String = AiResearch.BACKEND_STATUS
    override val diagnostics: String =
        "subject=center-weighted saliency approx; sky=blue-luma band; " +
            "analysis cap ${AiHeuristics.ANALYSIS_MAX_DIM}px; 0 MB model"

    override fun subjectMask(argb: IntArray, width: Int, height: Int): FloatArray? =
        AiHeuristics.computeSubjectMask(argb, width, height)

    override fun skyMask(argb: IntArray, width: Int, height: Int): FloatArray? =
        AiHeuristics.computeSkyMask(argb, width, height)
}

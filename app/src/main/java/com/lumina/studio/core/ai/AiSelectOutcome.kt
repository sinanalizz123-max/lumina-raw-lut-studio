package com.lumina.studio.core.ai

/**
 * M12 honest-outcome mapping (pure JVM).
 *
 * Every selection attempt resolves to one of these; [userMessage] is the
 * exact UI copy. Messages say "heuristic" or "manual mask" — never "AI".
 * JVM tests pin the null -> Unavailable path and the no-"AI" honesty guard.
 */
sealed interface AiSelectOutcome {
    data class Ready(val cacheKey: String) : AiSelectOutcome
    data class Unavailable(val reason: String) : AiSelectOutcome
    data class Failed(val reason: String) : AiSelectOutcome

    fun userMessage(): String = when (this) {
        is Ready ->
            "Heuristic selection ready — refine with Feather or a Brush mask."
        is Unavailable ->
            "Heuristic selection unavailable ($reason). " +
                "Try a manual mask instead: Brush, Color range or Luma range."
        is Failed ->
            "Heuristic selection failed ($reason). " +
                "Try a manual mask instead: Brush, Color range or Luma range."
    }
}

object AiSelectMessages {
    fun fromProcessorResult(mask: FloatArray?, cacheKey: String, kindLabel: String): AiSelectOutcome =
        if (mask != null) AiSelectOutcome.Ready(cacheKey)
        else AiSelectOutcome.Unavailable(kindLabel + " heuristic returned no field")
}

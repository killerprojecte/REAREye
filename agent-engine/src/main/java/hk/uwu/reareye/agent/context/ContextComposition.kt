package hk.uwu.reareye.agent.context

/** Estimate categories, not provider billing counters or a model-specific tokenizer result. */
enum class ContextCategory {
    SYSTEM,
    TOOL_DEFINITIONS,
    USER,
    ASSISTANT,
    TOOL_CALLS,
    TOOL_RESULTS,
    /** Authoritative native messages / opaque protocol blocks cannot honestly be split further. */
    NATIVE_PROTOCOL,
    /** The same fixed envelopes and identifiers already counted by the budget estimator. */
    FORMATTING,
}

/** No prompt text, arguments, identifiers or credentials are exposed in a composition snapshot. */
data class ContextSegment(
    val category: ContextCategory,
    val tokens: Int,
    val messageIndex: Int? = null,
) {
    init {
        require(tokens >= 0)
        require(messageIndex == null || messageIndex >= 0)
    }
}

data class ContextCategoryUsage(val category: ContextCategory, val tokens: Int)

/**
 * Read-only estimate of the assembled payload, in estimator traversal order (not guaranteed wire
 * order). All slices add up exactly to [totalTokens], including when the Int counter saturates.
 * Native payloads are counted once as whole native blocks; their display projection is not added.
 */
class ContextComposition private constructor(
    segments: List<ContextSegment>,
    val totalTokens: Int,
    val saturated: Boolean,
) {
    val segments: List<ContextSegment> = java.util.Collections.unmodifiableList(segments.toList())
    val categories: List<ContextCategoryUsage> = java.util.Collections.unmodifiableList(
        ContextCategory.entries.mapNotNull { category ->
            val count = this.segments.filter { it.category == category }.sumOf { it.tokens.toLong() }
            count.takeIf { it > 0 }?.let { ContextCategoryUsage(category, it.toInt()) }
        }
    )

    override fun equals(other: Any?): Boolean = other is ContextComposition &&
        totalTokens == other.totalTokens && saturated == other.saturated && segments == other.segments
    override fun hashCode(): Int = 31 * segments.hashCode() + saturated.hashCode()

    companion object {
        /** Deterministic clipping preserves the estimator's existing saturated-Int total. */
        fun fromSegments(segments: List<ContextSegment>): ContextComposition {
            val bounded = ArrayList<ContextSegment>()
            var total = 0L
            var clipped = false
            segments.forEach { segment ->
                val remaining = Int.MAX_VALUE.toLong() - total
                val kept = minOf(segment.tokens.toLong(), remaining).toInt()
                if (kept > 0) bounded += segment.copy(tokens = kept)
                if (kept != segment.tokens) clipped = true
                total += kept
            }
            return ContextComposition(bounded, total.toInt(), clipped)
        }
    }
}

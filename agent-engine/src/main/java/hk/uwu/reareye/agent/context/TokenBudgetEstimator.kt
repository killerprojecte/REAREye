package hk.uwu.reareye.agent.context

/** Conservative deterministic estimate shared by native context budgeting and tool output limits. */
object TokenBudgetEstimator {
    fun estimateTokens(text: String): Int {
        var ascii = 0L
        var nonAscii = 0L
        for (char in text) {
            if (char.code <= 0x7F) ascii++ else nonAscii++
        }
        return (nonAscii + (ascii + 2L) / 3L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

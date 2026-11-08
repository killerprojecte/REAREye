// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import kotlin.math.roundToInt

/** 平滑衔接首末行及多行/单行的留白；每一行消失时，其占用的边距也必须连续归零。 */
internal fun listCardEdgeInsets(edges: List<Int>, scales: List<Float>, bottomEdges: List<Int> = edges): Pair<Int, Int> {
    val n = edges.size
    val absentBefore = FloatArray(n + 1) { 1f }
    val absentAfter = FloatArray(n + 1) { 1f }
    for (i in 0 until n) absentBefore[i + 1] = absentBefore[i] * (1f - scales[i])
    for (i in n - 1 downTo 0) absentAfter[i] = absentAfter[i + 1] * (1f - scales[i])
    var top = 0f
    var bottom = 0f
    for (i in 0 until n) {
        top += edges[i] * scales[i] * absentBefore[i]
        bottom += bottomEdges[i] * scales[i] * absentAfter[i + 1]
    }
    return top.roundToInt() to bottom.roundToInt()
}

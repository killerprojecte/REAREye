// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

/** 月历的几何：一个月按周排成若干行，每行 7 格，第一列是 [firstDayOfWeek]（1 = 周一 … 7 = 周日） */
internal object CalendarGeometry {
    /** 1 号前面空几格 */
    fun leadingBlanks(year: Int, month: Int, firstDayOfWeek: Int): Int = (MochiDate(year, month, 1).dayOfWeek - firstDayOfWeek).mod(7)

    /** 这个月要几行（4..6） */
    fun rows(year: Int, month: Int, firstDayOfWeek: Int): Int {
        val cells = leadingBlanks(year, month, firstDayOfWeek) + MochiDate.daysInMonth(year, month)
        return (cells + 6) / 7
    }

    /** 某天在第几行、第几列 */
    fun cellOf(date: MochiDate, firstDayOfWeek: Int): Pair<Int, Int> {
        val i = leadingBlanks(date.year, date.month, firstDayOfWeek) + date.day - 1
        return i / 7 to i % 7
    }

    /** 第 row 行第 col 列是几号；落在空格里返回 null */
    fun dayAt(year: Int, month: Int, firstDayOfWeek: Int, row: Int, col: Int): Int? {
        val d = row * 7 + col - leadingBlanks(year, month, firstDayOfWeek) + 1
        return if (d in 1..MochiDate.daysInMonth(year, month)) d else null
    }

    /** 月份序号：从 [base] 那个月起第几个月 */
    fun monthIndex(year: Int, month: Int, baseYear: Int, baseMonth: Int): Int = (year - baseYear) * 12 + (month - baseMonth)

    /** 序号换回年月 */
    fun monthAt(index: Int, baseYear: Int, baseMonth: Int): Pair<Int, Int> {
        val total = baseYear * 12 + (baseMonth - 1) + index
        return total.floorDiv(12) to total.mod(12) + 1
    }

    /**
     * 选中范围在这个月里按周拆成的胶囊段。每段落在一行里，从 [startCol] 到 [endCol]；
     * [roundStart] / [roundEnd] = 这一端是范围真正的起止（画成圆头），否则是换行处（贴到行边）。
     */
    fun rangeSegments(start: MochiDate, end: MochiDate, year: Int, month: Int, firstDayOfWeek: Int): List<RangeSegment> {
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val first = MochiDate(year, month, 1)
        val last = MochiDate(year, month, MochiDate.daysInMonth(year, month))
        if (hi < first || lo > last) return emptyList()
        val from = if (lo < first) first else lo
        val to = if (hi > last) last else hi
        val out = ArrayList<RangeSegment>()
        var day = from.day
        while (day <= to.day) {
            val (row, col) = cellOf(MochiDate(year, month, day), firstDayOfWeek)
            val endDay = minOf(to.day, day + (6 - col))
            val endCol = col + (endDay - day)
            out += RangeSegment(
                row = row,
                startCol = col,
                endCol = endCol,
                roundStart = MochiDate(year, month, day) == lo,
                roundEnd = MochiDate(year, month, endDay) == hi,
            )
            day = endDay + 1
        }
        return out
    }
}

internal data class RangeSegment(val row: Int, val startCol: Int, val endCol: Int, val roundStart: Boolean, val roundEnd: Boolean)

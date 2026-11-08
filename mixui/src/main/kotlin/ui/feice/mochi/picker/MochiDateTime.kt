// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import java.util.Calendar

/**
 * 日期（公历）。库的 minSdk 是 23，用不了 java.time，所以自带这个小类型；
 * API 26+ 可以用 [toLocalDate] / [fromLocalDate] 和 java.time 互转。
 *
 * @param month 1..12
 * @param day 1..[daysInMonth]
 */
@Immutable
public data class MochiDate(val year: Int, val month: Int, val day: Int) : Comparable<MochiDate> {
    init {
        require(month in 1..12) { "month must be 1..12, was $month" }
        require(day in 1..daysInMonth(year, month)) { "day $day out of range for $year-$month" }
    }

    override fun compareTo(other: MochiDate): Int = compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    /** 自 1970-01-01 起的天数 */
    public val epochDay: Long get() = toEpochDay(year, month, day)

    /** 星期几：1 = 周一 … 7 = 周日（ISO） */
    public val dayOfWeek: Int get() = ((epochDay + 3).mod(7L) + 1).toInt()

    /** 这个月有几天 */
    public val lengthOfMonth: Int get() = daysInMonth(year, month)

    /** 加减天数 */
    public fun plusDays(days: Long): MochiDate = ofEpochDay(epochDay + days)

    /** 加减月数；日超出目标月的天数时取月末（1 月 31 日 + 1 个月 = 2 月 28 / 29 日） */
    public fun plusMonths(months: Int): MochiDate {
        val total = year * 12L + (month - 1) + months
        val y = (total).floorDiv(12L).toInt()
        val m = (total).mod(12L).toInt() + 1
        return MochiDate(y, m, day.coerceAtMost(daysInMonth(y, m)))
    }

    /** 这个月的 1 号 */
    public fun firstOfMonth(): MochiDate = MochiDate(year, month, 1)

    override fun toString(): String = "%04d-%02d-%02d".format(year, month, day)

    public companion object {
        /** 今天（设备时区） */
        public fun today(): MochiDate {
            val c = Calendar.getInstance()
            return MochiDate(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }

        /** 闰年 */
        public fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        /** 某年某月的天数 */
        public fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        /** 从 1970-01-01 起的天数换回日期 */
        public fun ofEpochDay(epochDay: Long): MochiDate {
            // Howard Hinnant 的 civil_from_days
            val z = epochDay + 719468
            val era = (z).floorDiv(146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return MochiDate((if (m <= 2) y + 1 else y).toInt(), m, d)
        }

        private fun toEpochDay(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (y).floorDiv(400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

        /** 从 java.time 转过来 */
        @RequiresApi(26)
        public fun fromLocalDate(date: java.time.LocalDate): MochiDate = MochiDate(date.year, date.monthValue, date.dayOfMonth)
    }
}

/** 转成 java.time */
@RequiresApi(26)
public fun MochiDate.toLocalDate(): java.time.LocalDate = java.time.LocalDate.of(year, month, day)

/**
 * 时刻（一天内）。
 *
 * @param hour 0..23
 * @param minute 0..59
 * @param second 0..59
 */
@Immutable
public data class MochiTime(val hour: Int, val minute: Int, val second: Int = 0) : Comparable<MochiTime> {
    init {
        require(hour in 0..23 && minute in 0..59 && second in 0..59) { "invalid time $hour:$minute:$second" }
    }

    override fun compareTo(other: MochiTime): Int = compareValuesBy(this, other, { it.hour }, { it.minute }, { it.second })

    /** 12 小时制的钟点：1..12 */
    public val hour12: Int get() = ((hour + 11) % 12) + 1

    /** 下午 */
    public val isPm: Boolean get() = hour >= 12

    override fun toString(): String = "%02d:%02d".format(hour, minute)

    public companion object {
        /** 现在（设备时区） */
        public fun now(): MochiTime {
            val c = Calendar.getInstance()
            return MochiTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
        }

        /** 从 12 小时制换回来 */
        public fun of12(hour12: Int, minute: Int, pm: Boolean): MochiTime = MochiTime((hour12 % 12) + if (pm) 12 else 0, minute)

        /** 从 java.time 转过来 */
        @RequiresApi(26)
        public fun fromLocalTime(time: java.time.LocalTime): MochiTime = MochiTime(time.hour, time.minute, time.second)
    }
}

/** 转成 java.time */
@RequiresApi(26)
public fun MochiTime.toLocalTime(): java.time.LocalTime = java.time.LocalTime.of(hour, minute, second)

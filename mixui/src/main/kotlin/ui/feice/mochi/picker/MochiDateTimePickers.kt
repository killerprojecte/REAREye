// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.ConfigurationCompat
import java.text.DateFormatSymbols
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** 中日韩的习惯：年月日、上午/下午在钟点前面 */
private fun Locale.isCjk(): Boolean = language in setOf("zh", "ja", "ko")

@Composable
private fun currentLocale(): Locale {
    val config = LocalConfiguration.current
    return ConfigurationCompat.getLocales(config)[0] ?: Locale.getDefault()
}

/**
 * 时间滚筒（iOS UIDatePicker 的 .time + .wheels）：时、分（循环），12 小时制多一列上午 / 下午。
 * 中文等语言上午 / 下午在前（「下午 3:30」），其他语言在后。
 *
 * @param is24Hour 默认跟随系统设置
 * @param minuteInterval 分钟的步长（比如 5 = 00、05、10…），时间不在步长上时显示最近的一格
 */
@Composable
public fun MochiTimePicker(
    time: MochiTime,
    onTimeChange: (MochiTime) -> Unit,
    modifier: Modifier = Modifier,
    is24Hour: Boolean = DateFormat.is24HourFormat(LocalContext.current),
    minuteInterval: Int = 1,
    colors: MochiWheelPickerColors = MochiWheelPickerDefaults.colors(),
) {
    val locale = currentLocale()
    val step = minuteInterval.coerceIn(1, 30)
    val hours = remember(is24Hour) {
        (if (is24Hour) (0..23).map { "%02d".format(it) } else (1..12).map { it.toString() }).toImmutableList()
    }
    val minutes = remember(step) { (0 until 60 step step).map { "%02d".format(it) }.toImmutableList() }
    val amPm = remember(locale) { DateFormatSymbols.getInstance(locale).amPmStrings.toList().toImmutableList() }
    val current by rememberUpdatedState(time)
    val change by rememberUpdatedState(onTimeChange)
    val hourIndex = if (is24Hour) time.hour else time.hour12 - 1
    val minuteIndex = ((time.minute + step / 2) / step).coerceAtMost(minutes.size - 1)

    MochiWheelPickerGroup(modifier, colors = colors) {
        val amPmColumn: @Composable () -> Unit = {
            if (!is24Hour) {
                MochiWheelPicker(
                    amPm,
                    if (time.isPm) 1 else 0,
                    { i -> change(MochiTime.of12(current.hour12, current.minute, pm = i == 1)) },
                    Modifier.weight(1f),
                    colors = colors,
                )
            }
        }
        if (locale.isCjk()) amPmColumn()
        MochiWheelPicker(
            hours,
            hourIndex,
            { i ->
                val h = if (is24Hour) i else MochiTime.of12(i + 1, 0, current.isPm).hour
                change(MochiTime(h, current.minute))
            },
            Modifier.weight(1f),
            cyclic = true,
            alignment = Alignment.End,
            colors = colors,
        )
        MochiWheelPicker(
            minutes,
            minuteIndex,
            { i -> change(MochiTime(current.hour, i * step)) },
            Modifier.weight(1f),
            cyclic = true,
            alignment = Alignment.Start,
            colors = colors,
        )
        if (!locale.isCjk()) amPmColumn()
    }
}

/**
 * 日期滚筒（iOS UIDatePicker 的 .date + .wheels）：年、月、日。中文等语言按年月日排，其他语言月日年。
 * 月和日循环；滚到这个月没有的日子（2 月 30 日）时那几天是灰的，停稳后自动滚回月末，和 iOS 一样。
 * 超出 [min] / [max] 的日期停稳后滚回边界。
 */
@Composable
public fun MochiDatePicker(
    date: MochiDate,
    onDateChange: (MochiDate) -> Unit,
    modifier: Modifier = Modifier,
    min: MochiDate = MochiDate(1900, 1, 1),
    max: MochiDate = MochiDate(2100, 12, 31),
    colors: MochiWheelPickerColors = MochiWheelPickerDefaults.colors(),
) {
    val locale = currentLocale()
    val cjk = locale.isCjk()
    val years = remember(min.year, max.year, cjk) {
        (min.year..max.year).map { if (cjk) "${it}年" else it.toString() }.toImmutableList()
    }
    val months = remember(locale) { monthLabels(locale) }
    val days = remember(cjk) { (1..31).map { if (cjk) "${it}日" else it.toString() }.toImmutableList() }
    val current by rememberUpdatedState(date)
    val change by rememberUpdatedState(onDateChange)

    fun commit(year: Int, month: Int, day: Int) {
        val d = MochiDate(year, month, day.coerceAtMost(MochiDate.daysInMonth(year, month)))
        change(d.coerceIn(min, max))
    }

    val dim = date.lengthOfMonth
    MochiWheelPickerGroup(modifier, colors = colors) {
        val yearColumn: @Composable () -> Unit = {
            MochiWheelPicker(
                years,
                date.year - min.year,
                { i -> commit(min.year + i, current.month, current.day) },
                Modifier.weight(if (cjk) 1.2f else 1f),
                colors = colors,
            )
        }
        val monthColumn: @Composable () -> Unit = {
            MochiWheelPicker(
                months,
                date.month - 1,
                { i -> commit(current.year, i + 1, current.day) },
                Modifier.weight(if (cjk) 1f else 1.4f),
                cyclic = true,
                colors = colors,
            )
        }
        val dayColumn: @Composable () -> Unit = {
            MochiWheelPicker(
                days,
                date.day - 1,
                { i -> commit(current.year, current.month, i + 1) },
                Modifier.weight(1f),
                cyclic = true,
                disabled = { it + 1 > dim },
                colors = colors,
            )
        }
        if (cjk) {
            yearColumn()
            monthColumn()
            dayColumn()
        } else {
            monthColumn()
            dayColumn()
            yearColumn()
        }
    }
}

private fun monthLabels(locale: Locale): ImmutableList<String> = if (locale.isCjk()) {
    (1..12).map { "${it}月" }.toImmutableList()
} else {
    DateFormatSymbols.getInstance(locale).months.take(12).toImmutableList()
}

/**
 * 时长滚筒（iOS 的 .countDownTimer）：时、分、秒，每列右边固定一个单位，不跟着滚。
 *
 * @param seconds 总秒数
 * @param maxHours 小时列的最大值
 * @param showSeconds false 时只有时、分两列
 */
@Composable
public fun MochiDurationPicker(
    seconds: Long,
    onSecondsChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    maxHours: Int = 23,
    showSeconds: Boolean = true,
    hourLabel: String = "小时",
    minuteLabel: String = "分钟",
    secondLabel: String = "秒",
    colors: MochiWheelPickerColors = MochiWheelPickerDefaults.colors(),
) {
    val total = seconds.coerceIn(0L, maxHours * 3600L + 3599L)
    val h = (total / 3600).toInt()
    val m = ((total % 3600) / 60).toInt()
    val s = (total % 60).toInt()
    val hours = remember(maxHours) { (0..maxHours).map { it.toString() }.toImmutableList() }
    val sixty = remember { (0 until 60).map { it.toString() }.toImmutableList() }
    val current by rememberUpdatedState(total)
    val change by rememberUpdatedState(onSecondsChange)
    fun parts(t: Long) = Triple(t / 3600, (t % 3600) / 60, t % 60)

    MochiWheelPickerGroup(modifier, colors = colors) {
        MochiWheelPicker(
            hours,
            h,
            { i ->
                val (_, mm, ss) = parts(current)
                change(i * 3600L + mm * 60 + ss)
            },
            Modifier.weight(1f),
            label = hourLabel,
            colors = colors,
        )
        MochiWheelPicker(
            sixty,
            m,
            { i ->
                val (hh, _, ss) = parts(current)
                change(hh * 3600 + i * 60L + ss)
            },
            Modifier.weight(1f),
            cyclic = true,
            label = minuteLabel,
            colors = colors,
        )
        if (showSeconds) {
            MochiWheelPicker(
                sixty,
                s,
                { i ->
                    val (hh, mm, _) = parts(current)
                    change(hh * 3600 + mm * 60 + i)
                },
                Modifier.weight(1f),
                cyclic = true,
                label = secondLabel,
                colors = colors,
            )
        }
    }
}

// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiDropdownDefaults
import ui.feice.mochi.basic.MochiPopoverButton
import ui.feice.mochi.basic.opaqueButtonColor
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/** 紧凑日期 / 时间胶囊的颜色 */
@Immutable
public data class MochiDateFieldColors(
    val container: Color,
    val content: Color,
    /** 弹出层打开时胶囊里的文字颜色 */
    val activeContent: Color,
)

/** 紧凑日期 / 时间胶囊的默认值 */
public object MochiDateFieldDefaults {
    @Composable
    public fun colors(
        container: Color = MochiDefaults.fill(),
        content: Color = MochiTheme.colorScheme.onSurface,
        activeContent: Color = MochiTheme.colorScheme.primary,
    ): MochiDateFieldColors = remember(container, content, activeContent) { MochiDateFieldColors(container, content, activeContent) }

    /** 胶囊内边距 */
    public val ContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp)

    /** 日期的默认格式：跟随系统区域的中等长度格式（「2026年10月17日」/「Oct 17, 2026」） */
    @Composable
    public fun dateFormatter(): (MochiDate) -> String {
        val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
        return remember(locale) {
            val f = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
            val cal = Calendar.getInstance(locale)
            return@remember { d: MochiDate ->
                cal.clear()
                cal.set(d.year, d.month - 1, d.day)
                f.format(cal.time)
            }
        }
    }

    /** 时间的默认格式：跟随系统的 12 / 24 小时设置 */
    @Composable
    public fun timeFormatter(): (MochiTime) -> String {
        val context = LocalContext.current
        return remember(context) {
            val f = android.text.format.DateFormat.getTimeFormat(context)
            val cal = Calendar.getInstance()
            return@remember { t: MochiTime ->
                cal.clear()
                cal.set(2000, 0, 1, t.hour, t.minute, 0)
                f.format(cal.time)
            }
        }
    }
}

/**
 * 紧凑日期（iOS UIDatePicker 的 .compact）：列表行尾的灰色胶囊，就是一个 [MochiPopoverButton]——
 * 按下放大发光、胶囊的灰色融进弹出的月历面板，和下拉菜单同一套；选了日期胶囊里的文字模糊替换，
 * 弹出层打开时文字用强调色；点外面关闭。
 */
@Composable
public fun MochiDateField(
    date: MochiDate,
    onDateChange: (MochiDate) -> Unit,
    modifier: Modifier = Modifier,
    min: MochiDate = MochiDate(1900, 1, 1),
    max: MochiDate = MochiDate(2100, 12, 31),
    format: (MochiDate) -> String = MochiDateFieldDefaults.dateFormatter(),
    colors: MochiDateFieldColors = MochiDateFieldDefaults.colors(),
) {
    var open by remember { mutableStateOf(false) }
    FieldButton(format(date), open, {
        open = it
    }, colors, modifier = modifier, width = 344.dp, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        MochiCalendar(
            selection = MochiDateSelection.Single(date),
            onSelectionChange = { s -> (s as? MochiDateSelection.Single)?.date?.let(onDateChange) },
            min = min,
            max = max,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 紧凑时间：同 [MochiDateField]，弹出层里是时间滚筒（[MochiTimePicker]）。
 */
@Composable
public fun MochiTimeField(
    time: MochiTime,
    onTimeChange: (MochiTime) -> Unit,
    modifier: Modifier = Modifier,
    minuteInterval: Int = 1,
    format: (MochiTime) -> String = MochiDateFieldDefaults.timeFormatter(),
    colors: MochiDateFieldColors = MochiDateFieldDefaults.colors(),
) {
    var open by remember { mutableStateOf(false) }
    FieldButton(format(time), open, { open = it }, colors, modifier = modifier, width = 260.dp, contentPadding = PaddingValues(12.dp)) {
        MochiTimePicker(time, onTimeChange, Modifier.fillMaxWidth(), minuteInterval = minuteInterval)
    }
}

@Composable
private fun FieldButton(
    text: String,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    colors: MochiDateFieldColors,
    width: Dp,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    popover: @Composable ColumnScope.() -> Unit,
) {
    val color by animateColorAsState(if (open) colors.activeContent else colors.content, label = "chip")
    // 胶囊在列表卡片里：半透明的灰先合成到卡片底色上，融色引擎拿到的是不透明色（见 opaqueButtonColor）
    val chip = opaqueButtonColor(colors.container, MochiDefaults.groupedContainer())
    MochiPopoverButton(
        expanded = open,
        onExpandedChange = onOpenChange,
        popover = popover,
        modifier = modifier,
        width = width,
        contentPadding = contentPadding,
        colors = MochiDropdownDefaults.dropdownColors(buttonColor = chip, buttonContentColor = colors.content),
        behavior = MochiDropdownDefaults.behavior(align = MochiAlign.End),
        buttonPadding = MochiDateFieldDefaults.ContentPadding,
        buttonMinHeight = 34.dp,
    ) {
        MochiBlurText(text, style = MochiTheme.textStyles.body, color = color, maxLines = 1)
    }
}

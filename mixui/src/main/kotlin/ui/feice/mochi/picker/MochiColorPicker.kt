// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiBarIconButton
import ui.feice.mochi.basic.MochiSegmentedControl
import ui.feice.mochi.basic.MochiSheet
import ui.feice.mochi.basic.MochiSheetContent
import ui.feice.mochi.basic.MochiSheetDetent
import ui.feice.mochi.basic.MochiSheetHeader
import ui.feice.mochi.basic.MochiSheetState
import ui.feice.mochi.basic.MochiSlider
import ui.feice.mochi.basic.MochiSliderDefaults
import ui.feice.mochi.basic.Text
import ui.feice.mochi.basic.parts.MochiGlyphs
import ui.feice.mochi.basic.rememberMochiSheetState
import ui.feice.mochi.effect.content.MochiBlurCrossfade
import ui.feice.mochi.effect.content.MochiCounterText
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.shape.drawG2RoundRect
import ui.feice.mochi.theme.MochiTheme

/** 颜色选择器的默认值 */
public object MochiColorPickerDefaults {
    /** 色块直径 */
    public val WellSize: Dp = 28.dp

    /** 最近使用最多记几个 */
    public const val RecentCount: Int = 8
}

/**
 * 色块（iOS 的 ColorPicker 行尾）：中间是当前颜色，外面一圈彩虹环；点一下弹出 [MochiColorPickerSheet]。
 * 关掉面板时把这次选的颜色记进「最近使用」。
 */
@Composable
public fun MochiColorWell(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "颜色",
    supportsAlpha: Boolean = true,
    size: Dp = MochiColorPickerDefaults.WellSize,
) {
    val sheet = rememberMochiSheetState(listOf(MochiSheetDetent.Medium, MochiSheetDetent.Large))
    val recents = remember { mutableStateListOf<Color>() }
    val current by rememberUpdatedState(color)
    Canvas(
        modifier
            .size(size)
            .pointerInput(Unit) { detectTapGestures { sheet.show() } }
            .semantics {
                role = Role.Button
                contentDescription = title
                onClick {
                    sheet.show()
                    true
                }
            },
    ) {
        val ring = 3.dp.toPx()
        drawCircle(
            Brush.sweepGradient(RainbowStops),
            radius = this.size.minDimension / 2f - ring / 2f,
            style = Stroke(ring),
        )
        val swatch = this.size.minDimension / 2f - ring - 2.dp.toPx()
        // 同一条边只留一层抗锯齿：棋盘格只在半透明时画，并内缩 1dp 藏在色块边缘里面，
        // 否则白底和灰格的半透明边会从色块边缘透出来，形成一圈毛边
        if (color.alpha < 1f) drawCheckerCircle(swatch - 1.dp.toPx())
        drawCircle(color, radius = swatch)
    }
    MochiColorPickerSheet(
        state = sheet,
        color = color,
        onColorChange = onColorChange,
        title = title,
        supportsAlpha = supportsAlpha,
        recents = recents,
        onDismiss = {
            val c = current
            recents.remove(c)
            recents.add(0, c)
            while (recents.size > MochiColorPickerDefaults.RecentCount) recents.removeAt(recents.lastIndex)
        },
    )
}

/**
 * 选色面板（iOS 的 UIColorPickerViewController）：顶上分段切换「网格 / 色谱 / 滑块」，下面是不透明度滑条、
 * 当前颜色和最近使用。基于 [MochiSheet]，半屏 / 全屏两档。
 */
@Composable
public fun MochiColorPickerSheet(
    state: MochiSheetState,
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "颜色",
    supportsAlpha: Boolean = true,
    recents: List<Color> = emptyList(),
    onDismiss: () -> Unit = {},
) {
    var mode by remember { mutableIntStateOf(0) }
    val change by rememberUpdatedState(onColorChange)
    val alpha = color.alpha
    MochiSheet(state, modifier, onDismiss = onDismiss) {
        MochiSheetHeader(
            title,
            actions = {
                MochiBarIconButton(onClick = { state.hide() }, modifier = Modifier.semantics { contentDescription = "关闭" }) {
                    MochiGlyphs.Close(MochiTheme.colorScheme.onSurface, size = 14.dp)
                }
            },
        )
        MochiSheetContent {
            MochiSegmentedControl(mode, { mode = it }, persistentListOf("网格", "色谱", "滑块"), Modifier.fillMaxWidth())
            MochiBlurCrossfade(mode, Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { m ->
                when (m) {
                    0 -> ColorGrid(color.copy(alpha = 1f)) { change(it.copy(alpha = alpha)) }
                    1 -> ColorSpectrum(color.copy(alpha = 1f)) { change(it.copy(alpha = alpha)) }
                    else -> RgbSliders(color) { change(it) }
                }
            }
            if (supportsAlpha) {
                LabeledSlider("不透明度", (alpha * 100).roundToInt().toLong(), "%", alpha, 0f..1f, color.copy(alpha = 1f)) {
                    change(color.copy(alpha = it))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Canvas(Modifier.size(48.dp).clip(MochiRoundedRectangle(MochiRoundedCorner))) {
                    drawCheckerRect(MochiRoundedCorner.toPx())
                    drawRect(color)
                }
                Box(Modifier.width(1.dp).height(32.dp).background(MochiTheme.colorScheme.outlineVariant))
                recents.take(MochiColorPickerDefaults.RecentCount).forEach { c ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(c)
                            .pointerInput(c) { detectTapGestures { change(c) } },
                    )
                }
            }
        }
    }
}

/** 网格：第一行灰阶，下面 12 种色相从深到浅 */
@Composable
private fun ColorGrid(selected: Color, onPick: (Color) -> Unit) {
    val cols = 12
    val rows = 10
    val colors = remember { gridColors(cols, rows) }
    val sel = remember(selected) { colors.indexOfFirst { it.toArgb() == selected.toArgb() } }
    val shape = MochiRoundedRectangle(MochiRoundedCorner)
    val scratch = remember { Path() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(cols / rows.toFloat())
            .clip(shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun pickAt(p: Offset) {
                        val c = (p.x / size.width * cols).toInt().coerceIn(0, cols - 1)
                        val r = (p.y / size.height * rows).toInt().coerceIn(0, rows - 1)
                        onPick(colors[r * cols + c])
                    }
                    pickAt(down.position)
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        pickAt(ch.position)
                        ch.consume()
                    }
                }
            },
    ) {
        val cw = size.width / cols
        val ch = size.height / rows
        for (i in colors.indices) {
            drawRect(colors[i], topLeft = Offset((i % cols) * cw, (i / cols) * ch), size = Size(cw + 0.5f, ch + 0.5f))
        }
        if (sel >= 0) {
            val w = 3.dp.toPx()
            drawG2RoundRect(
                scratch,
                Rect(
                    (sel % cols) * cw + w / 2f,
                    (sel / cols) * ch + w / 2f,
                    (sel % cols + 1) * cw - w / 2f,
                    (sel / cols + 1) * ch - w / 2f,
                ),
                4.dp.toPx(),
                Color.White,
                style = Stroke(w),
            )
        }
    }
}

internal fun gridColors(cols: Int, rows: Int): List<Color> {
    val out = ArrayList<Color>(cols * rows)
    for (c in 0 until cols) {
        val v = 1f - c / (cols - 1f)
        out += Color(v, v, v)
    }
    for (r in 1 until rows) {
        val l = 0.16f + (r - 1) * (0.78f / (rows - 2))
        val s = if (r < rows / 2) 1f else 1f - (r - rows / 2) * 0.06f
        for (c in 0 until cols) out += Color.hsl((200f + c * 30f) % 360f, s, l.coerceIn(0f, 1f))
    }
    return out
}

/**
 * 色谱：先选色彩模型，再在二维平面上点选，下面一条滑条调第三个维度。
 * - HSL：横向色相、竖向亮度（上白下黑），滑条调饱和度
 * - HCT（Material 的色相 / 彩度 / 色调）：横向色相、竖向色调，滑条调彩度
 * - Lab：平面是 a（绿 → 红）× b（蓝 → 黄），滑条调明度
 * - LCH：横向色相、竖向明度，滑条调彩度
 *
 * HCT、Lab、LCH 里很多组合 sRGB 显示不出来：保持明度和色相、把彩度降到刚好能显示（见 [ColorSpaces]），
 * 不按分量截断，平面上不会出现色相跑偏的色带
 */
@Composable
private fun ColorSpectrum(selected: Color, onPick: (Color) -> Unit) {
    var model by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MochiSegmentedControl(model, { model = it }, persistentListOf("HSL", "HCT", "Lab", "LCH"), Modifier.fillMaxWidth())
        MochiBlurCrossfade(model, Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { m ->
            SpectrumPlane(SpectrumModel.entries[m], selected, onPick)
        }
    }
}

/** 色谱的一种色彩模型：平面两个轴 + 滑条一个轴，三个都归一化到 0..1 */
internal enum class SpectrumModel(val sliderLabel: String, val sliderMax: Int, val sliderSuffix: String) {
    HSL("饱和度", 100, "%"),
    HCT("彩度", 150, ""),
    Lab("明度", 100, ""),
    LCH("彩度", 150, ""),
    ;

    /** 平面上 (x, y)、滑条 z 处的颜色（ARGB，已映射到 sRGB 色域内） */
    fun argb(x: Float, y: Float, z: Float, scratch: FloatArray = FloatArray(3)): Int = when (this) {
        HSL -> hslToArgb(x * 360f, z, 1f - y)

        HCT -> ColorSpaces.hctToArgb(x * 360.0, z * 150.0, (1f - y) * 100.0)

        Lab -> {
            ColorSpaces.labToSrgb(z * 100.0, (x * 2f - 1f) * 128.0, (1f - y * 2f) * 128.0, scratch)
            ColorSpaces.argb(scratch[0], scratch[1], scratch[2])
        }

        LCH -> {
            ColorSpaces.lchToSrgb((1f - y) * 100.0, z * 150.0, x * 360.0, scratch)
            ColorSpaces.argb(scratch[0], scratch[1], scratch[2])
        }
    }

    /** 颜色在这个模型里的位置 (x, y, z) */
    fun locate(c: Color): FloatArray = when (this) {
        HSL -> {
            val hsl = argbToHsl(c)
            floatArrayOf(hsl[0] / 360f, 1f - hsl[2], hsl[1])
        }

        HCT -> {
            val h = ColorSpaces.argbToHct(c.toArgb())
            floatArrayOf((h[0] / 360.0).toFloat(), (1.0 - h[2] / 100.0).toFloat(), (h[1] / 150.0).toFloat().coerceAtMost(1f))
        }

        Lab -> {
            val lab = ColorSpaces.srgbToLab(c.red, c.green, c.blue)
            floatArrayOf((lab[1] / 256.0 + 0.5).toFloat(), (0.5 - lab[2] / 256.0).toFloat(), (lab[0] / 100.0).toFloat())
        }

        LCH -> {
            val lch = ColorSpaces.srgbToLch(c.red, c.green, c.blue)
            floatArrayOf((lch[2] / 360.0).toFloat(), (1.0 - lch[0] / 100.0).toFloat(), (lch[1] / 150.0).toFloat().coerceAtMost(1f))
        }
    }

    /** 滑条在 z 时整张平面的样子（小图，画的时候双线性放大） */
    fun render(z: Float, w: Int, h: Int, active: () -> Boolean): IntArray? {
        val out = IntArray(w * h)
        val scratch = FloatArray(3)
        for (j in 0 until h) {
            if (!active()) return null
            val y = (j + 0.5f) / h
            for (i in 0 until w) out[j * w + i] = argb((i + 0.5f) / w, y, z, scratch)
        }
        return out
    }
}

@Composable
private fun SpectrumPlane(model: SpectrumModel, selected: Color, onPick: (Color) -> Unit) {
    // 位置自己记着：显示不出来的点映射回色域后颜色会变，再从颜色反推位置标记就会跳。外面改了颜色才重新定位
    var pos by remember(model) { mutableStateOf(model.locate(selected)) }
    var emitted by remember(model) { mutableIntStateOf(selected.toArgb()) }
    if (selected.toArgb() != emitted) {
        emitted = selected.toArgb()
        pos = model.locate(selected)
    }
    fun pick(x: Float, y: Float, z: Float) {
        pos = floatArrayOf(x, y, z)
        val c = Color(model.argb(x, y, z))
        emitted = c.toArgb()
        onPick(c)
    }
    val z = pos[2]
    // 第三个维度变了就在后台重算整张平面（只有 96×80 个点），算好之前先显示上一张
    val plane by produceState<ImageBitmap?>(null, model, z) {
        val pixels = withContext(Dispatchers.Default) { model.render(z, PlaneW, PlaneH) { isActive } } ?: return@produceState
        value = android.graphics.Bitmap.createBitmap(pixels, PlaneW, PlaneH, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val shape = MochiRoundedRectangle(MochiRoundedCorner)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.2f)
                .clip(shape)
                .pointerInput(model) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun pickAt(p: Offset) = pick((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f), pos[2])
                        pickAt(down.position)
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            pickAt(ch.position)
                            ch.consume()
                        }
                    }
                },
        ) {
            val img = plane
            if (img != null) {
                drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
            } else {
                drawRect(Color.Gray.copy(alpha = 0.2f))
            }
            val c = Offset(pos[0] * size.width, pos[1] * size.height)
            val r = 14.dp.toPx()
            drawCircle(selected, r, c)
            drawCircle(Color.White, r, c, style = Stroke(3.dp.toPx()))
        }
        LabeledSlider(
            model.sliderLabel,
            (z * model.sliderMax).roundToInt().toLong(),
            model.sliderSuffix,
            z,
            0f..1f,
            selected,
        ) { pick(pos[0], pos[1], it) }
    }
}

private const val PlaneW = 96
private const val PlaneH = 80

/** HSL → ARGB（h 角度，s、l 0..1）；s、l 在范围内时结果天然在 sRGB 里 */
internal fun hslToArgb(h: Float, s: Float, l: Float): Int {
    val c = Color.hsl(h.mod(360f), s, l)
    return c.toArgb()
}

/** ARGB → HSL：h 0..360，s、l 0..1 */
internal fun argbToHsl(c: Color): FloatArray {
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d < 1e-6f) return floatArrayOf(0f, 0f, l)
    val s = d / (1f - kotlin.math.abs(2f * l - 1f))
    var h = when (max) {
        r -> 60f * (((g - b) / d).mod(6f))
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    if (h < 0f) h += 360f
    return floatArrayOf(h, s, l)
}

@Composable
private fun RgbSliders(color: Color, onChange: (Color) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledSlider("红", (color.red * 255).roundToInt().toLong(), "", color.red, 0f..1f, Color(0xFFFF3B30)) {
            onChange(color.copy(red = it))
        }
        LabeledSlider("绿", (color.green * 255).roundToInt().toLong(), "", color.green, 0f..1f, Color(0xFF34C759)) {
            onChange(color.copy(green = it))
        }
        LabeledSlider("蓝", (color.blue * 255).roundToInt().toLong(), "", color.blue, 0f..1f, Color(0xFF007AFF)) {
            onChange(color.copy(blue = it))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    shown: Long,
    suffix: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MochiTheme.textStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = MochiTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            MochiCounterText(
                shown,
                style = MochiTheme.textStyles.footnote,
                color = MochiTheme.colorScheme.onSurfaceVariant,
                format = { "$it$suffix" },
            )
        }
        MochiSlider(value, onChange, Modifier.fillMaxWidth(), valueRange = range, colors = MochiSliderDefaults.colors(activeTrack = accent))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerCircle(radius: Float) {
    // 格子裁进圆里：直接按格子中心判断会让方角伸出圆外
    val clip = Path().apply { addOval(Rect(center, radius)) }
    clipPath(clip) {
        drawCircle(Color.White, radius)
        val cell = radius / 2f
        for (i in -2 until 2) {
            for (j in -2 until 2) {
                if ((i + j) % 2 == 0) continue
                drawRect(Color(0xFFCCCCCC), Offset(center.x + i * cell, center.y + j * cell), Size(cell, cell))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerRect(corner: Float) {
    drawRect(Color.White)
    val cell = 8.dp.toPx()
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(Color(0xFFCCCCCC), Offset(x, y), Size(cell.coerceAtMost(size.width - x), cell.coerceAtMost(size.height - y)))
            x += cell * 2
        }
        y += cell
        row++
    }
}

private val MochiRoundedCorner = 12.dp

internal val RainbowStops = listOf(
    Color(0xFFFF0000),
    Color(0xFFFFFF00),
    Color(0xFF00FF00),
    Color(0xFF00FFFF),
    Color(0xFF0000FF),
    Color(0xFFFF00FF),
    Color(0xFFFF0000),
)

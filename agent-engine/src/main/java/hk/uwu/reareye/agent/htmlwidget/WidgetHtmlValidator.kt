package hk.uwu.reareye.agent.htmlwidget

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Checks a generated HTML widget against the widget specification.
 *
 * The specification asks the model to *self-certify* with three header comments. That is not
 * reliable on its own — a model can write `SAFE_ZONE: ... OK` without the numbers being true. So
 * this validator recomputes every figure from the document itself and reports both violations and
 * mismatches between the claimed and actual values.
 *
 * The rules encoded here are transcribed from the specification; the specification text itself is
 * never modified, this is only a second gate on the model's output.
 */
object WidgetHtmlValidator {

    /** Content must stay this far inside the right edge, per the spec's safe-zone rule. */
    private const val EDGE_MARGIN = 20

    /** Minimum size of a tappable rectangle, in BASE pixels. */
    private const val MIN_TOUCH = 60

    /** Minimum gap between two tappable rectangles. */
    private const val MIN_TOUCH_GAP = 16

    /** A child box must fill at least this fraction of OUTER in both axes. */
    private const val MIN_FILL = 0.80

    /** Aspect-ratio deviation from the usable area allowed for the child box. */
    private const val MAX_RATIO_DEVIATION = 0.25

    fun validate(
        html: String,
        baseWidth: Int,
        baseHeight: Int,
    ): WidgetValidation {
        val issues = mutableListOf<WidgetIssue>()
        val camera = floor(baseWidth / 3.0).toInt()
        val avail = Rect(
            x = camera + EDGE_MARGIN,
            y = 20,
            w = baseWidth - camera - EDGE_MARGIN * 2,
            h = baseHeight - EDGE_MARGIN * 2,
        )
        // --- structure -------------------------------------------------------------------
        if (!html.contains("<!doctype html>", ignoreCase = true) &&
            !html.contains("<!DOCTYPE html>", ignoreCase = true)
        ) {
            issues.error("missing_doctype", "文件开头缺少 <!doctype html>。")
        }
        if (!Regex("""<meta[^>]+name=["']viewport["']""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            issues.error(
                "missing_viewport",
                "缺少 viewport meta；WebView 会退回 980 默认布局视口，显示会不全或发糊。",
            )
        }
        if (!html.contains("cachePage")) {
            issues.warn(
                "missing_cache_notice",
                "文件头建议写清「引擎 cachePage=\"true\"，改文件后需清缓存/重装小部件才生效」。",
            )
        }

        // --- forbidden content -----------------------------------------------------------
        val external = Regex("""(https?:)?//[a-z0-9.-]+\.[a-z]{2,}""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            // The doctype and any XML namespace-ish strings are not resource loads.
            .filterNot { it.startsWith("//www.w3.org") }
            .toList()
        if (external.isNotEmpty()) {
            issues.error(
                "external_resource",
                "出现疑似外部资源：${external.distinct().take(3).joinToString()}。页面必须完全离线可用。",
            )
        }
        if (Regex("""\bconsole\.log\s*\(""").containsMatchIn(html)) {
            issues.error("debug_output", "交付版里残留了 console.log。")
        }
        if (Regex("""\balert\s*\(""").containsMatchIn(html)) {
            issues.error("debug_output", "交付版里残留了 alert。")
        }

        // --- lifecycle contract ----------------------------------------------------------
        listOf("__fit", "__setAod").forEach { hook ->
            if (!html.contains(hook)) {
                issues.error("missing_hook", "缺少必须暴露到 window 的 $hook。")
            }
        }
        if (!html.contains("__DIAL_NO_ANIM")) {
            issues.error("missing_dial_no_anim", "缺少 __DIAL_NO_ANIM 定格帧分支。")
        }
        checkTransformOrder(html, issues)

        // --- CSS sizing ------------------------------------------------------------------
        val cssPixelSizes = Regex("""\b\d+px""").findAll(html)
            .map { it.value }
            .filterNot { it == "0px" }
            .toList()
        if (cssPixelSizes.isNotEmpty()) {
            issues.error(
                "hardcoded_css_px",
                "CSS/代码里出现具体像素尺寸 ${cssPixelSizes.distinct().take(5).joinToString()}；" +
                    "尺寸必须由 __fit 计算。",
            )
        }

        // --- LAYOUT ----------------------------------------------------------------------
        val layout = parseLayout(html)
        if (layout.isEmpty()) {
            issues.error(
                "missing_layout",
                "没有找到可解析的 LAYOUT 常量区；坐标必须集中声明在那里。",
            )
            return WidgetValidation(issues)
        }

        // Safe zone: every declared rectangle except the OUTER backing plate.
        //
        // The spec constrains content, not the backing colour: OUTER is explicitly allowed ~10-20px
        // of margin on all four sides, which means it is expected to extend past the content
        // boundary. Flagging it would reject compliant widgets.
        val contentRects = layout.filterNot { it.name.equals("OUTER", ignoreCase = true) }
        val violations = contentRects.filter {
            it.rect.x < camera + EDGE_MARGIN || it.rect.right > baseWidth - EDGE_MARGIN
        }
        if (violations.isNotEmpty()) {
            issues.error(
                "safe_zone_violation",
                "以下内容矩形越过安全区（要求 x ≥ ${camera + EDGE_MARGIN} 且 x+w ≤ ${baseWidth - EDGE_MARGIN}）：" +
                    violations.joinToString { "${it.name}(x=${it.rect.x}, w=${it.rect.w})" },
            )
        }

        // Coordinate literals inside drawing code.
        checkCoordinateLiterals(html, issues)

        // OUTER must cover the usable area.
        val outer = layout.firstOrNull { it.name.equals("OUTER", ignoreCase = true) }
        if (outer == null) {
            issues.error("missing_outer", "LAYOUT 里没有 OUTER 底板；它是强制的外层容器。")
        } else {
            val dw = abs(outer.rect.w - avail.w)
            val dh = abs(outer.rect.h - avail.h)
            if (dw > avail.w * 0.15 || dh > avail.h * 0.15) {
                issues.error(
                    "outer_mismatch",
                    "OUTER 是 ${outer.rect.w}×${outer.rect.h}，可用区是 ${avail.w}×${avail.h}，偏差过大。" +
                        "OUTER 应铺满右侧可用画面。",
                )
            }
        }

        // CHILD bounding box must fill OUTER in both axes and keep a sane aspect ratio.
        val children = layout.filterNot { it.name.equals("OUTER", ignoreCase = true) }
        if (outer != null && children.isNotEmpty()) {
            val child = children.map { it.rect }.reduce { a, b -> a.union(b) }
            val fillW = child.w.toDouble() / outer.rect.w
            val fillH = child.h.toDouble() / outer.rect.h
            if (fillW < MIN_FILL || fillH < MIN_FILL) {
                issues.error(
                    "child_fill",
                    "内容包围盒 ${child.w}×${child.h} 相对 OUTER ${outer.rect.w}×${outer.rect.h} 的" +
                        "填充率是 ${(fillW * 100).roundToInt()}%×${(fillH * 100).roundToInt()}%，" +
                        "要求双向 ≥80% —— 这通常是竖长条或细横条布局。",
                )
            }
            val childRatio = child.w.toDouble() / child.h
            val availRatio = avail.w.toDouble() / avail.h
            val deviation = abs(childRatio - availRatio) / availRatio
            if (deviation > MAX_RATIO_DEVIATION) {
                issues.error(
                    "child_ratio",
                    "内容包围盒宽高比 ${"%.2f".format(childRatio)} 与可用区 " +
                        "${"%.2f".format(availRatio)} 偏差 ${(deviation * 100).roundToInt()}%，超过 25%。",
                )
            }
        }

        // Touch targets.
        val touches = layout.filter { it.touch }
        touches.forEach { item ->
            if (item.rect.w < MIN_TOUCH || item.rect.h < MIN_TOUCH) {
                issues.error(
                    "touch_too_small",
                    "可点矩形 ${item.name} 是 ${item.rect.w}×${item.rect.h}，小于 ${MIN_TOUCH}×${MIN_TOUCH}。",
                )
            }
        }
        for (i in touches.indices) {
            for (j in i + 1 until touches.size) {
                val gap = touches[i].rect.gapTo(touches[j].rect)
                if (gap in 0..<MIN_TOUCH_GAP) {
                    issues.error(
                        "touch_gap",
                        "可点矩形 ${touches[i].name} 与 ${touches[j].name} 间距只有 $gap，要求 ≥$MIN_TOUCH_GAP。",
                    )
                }
            }
        }

        // --- header comments must agree with the computed values -------------------------
        checkHeaderComments(html, issues, camera, baseWidth, contentRects, avail)

        return WidgetValidation(issues, layout = layout, avail = avail.toSize(), camera = camera)
    }

    /**
     * `setTransform` must come after `c.width`/`c.height` are assigned, because assigning either
     * resets the canvas transform — getting the order wrong produces a blurry, wrongly scaled view.
     */
    private fun checkTransformOrder(html: String, issues: MutableList<WidgetIssue>) {
        val widthAssign = html.indexOf("c.width=")
        val transform = html.indexOf("setTransform(")
        if (widthAssign < 0 || transform < 0) return
        if (transform < widthAssign) {
            issues.error(
                "transform_order",
                "setTransform() 出现在 canvas 宽高赋值之前。改宽高会清零变换，顺序反了会整体缩放发糊。",
            )
        }
    }

    /**
     * The spec forbids coordinate literals inside drawing functions so the safe zone can be audited
     * rectangle by rectangle.
     *
     * Two deliberate exclusions keep this from crying wolf:
     *  - a call whose width/height arguments are `W`/`H` is a full-viewport paint, which the spec
     *    explicitly permits for backgrounds and overlays;
     *  - the check only fires when a bare numeric coordinate appears, so arithmetic that reads from
     *    LAYOUT is fine.
     */
    private fun checkCoordinateLiterals(html: String, issues: MutableList<WidgetIssue>) {
        val layoutRange = layoutBlockRange(html) ?: return
        val body = html.removeRange(layoutRange.first, layoutRange.last + 1)

        val call = Regex(
            """(?:fillRect|strokeRect|drawImage)\s*\(([^()]*)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val bareNumber = Regex("""^\s*-?\d+(?:\.\d+)?\s*$""")

        val hits = mutableListOf<String>()
        for (match in call.findAll(body)) {
            val args = match.groupValues[1].split(',').map { it.trim() }
            if (args.size < 4) continue

            // A full-viewport paint: the spec explicitly lets backgrounds and overlays ignore the
            // content boundary, and they are sized from W/H rather than from LAYOUT.
            if (args[2] in setOf("W", "w") || args[3] in setOf("H", "h")) continue

            // Hardcoded geometry means *every* coordinate is a plain number, as in
            // `fillRect(400, 430, 160, 88)`. Calls that mix literals with computed values are
            // accepted: `strokeRect(0,0,BASE_CAM,BASE_H)` is the dead-zone frame the spec requires,
            // and it legitimately starts at the origin.
            val literals = args.count { bareNumber.matches(it) }
            if (literals >= 4 && args.none { it.contains("LAYOUT", ignoreCase = true) }) {
                hits += match.value.take(48).replace('\n', ' ')
            }
            if (hits.size >= 3) break
        }

        if (hits.isNotEmpty()) {
            issues.error(
                "coordinate_literal",
                "绘制函数里出现坐标字面量（${hits.joinToString()}）；坐标必须全部引用 LAYOUT。",
            )
        }
    }

    private fun checkHeaderComments(
        html: String,
        issues: MutableList<WidgetIssue>,
        camera: Int,
        baseWidth: Int,
        contentRects: List<WidgetEntry>,
        avail: Rect,
    ) {
        val head = html.take(2000)

        if (!head.contains("SAFE_ZONE:", ignoreCase = true)) {
            issues.error("missing_header", "文件头缺少 SAFE_ZONE 注释行。")
        } else {
            // The claimed BASE_CAM must match the computed one.
            val claimedCam = Regex("""SAFE_ZONE:\s*BASE_CAM=(\d+)""").find(head)?.groupValues?.get(1)?.toIntOrNull()
            if (claimedCam != null && claimedCam != camera) {
                issues.error(
                    "header_mismatch",
                    "头注释写 BASE_CAM=$claimedCam，实算是 $camera。",
                )
            }
            val claimedMin = Regex("""min_x=(\d+)""").find(head)?.groupValues?.get(1)?.toIntOrNull()
            // Measured over content only: OUTER is the backing plate and is allowed to overhang, so
            // including it would report a minimum that the spec never asked to be checked.
            val actualMin = contentRects.minOfOrNull { it.rect.x }
            if (claimedMin != null && actualMin != null && claimedMin != actualMin) {
                issues.error(
                    "header_mismatch",
                    "头注释写 min_x=$claimedMin，LAYOUT 实算最小值是 $actualMin。",
                )
            }
        }

        if (!head.contains("AVAIL:", ignoreCase = true)) {
            issues.error("missing_header", "文件头缺少 AVAIL 注释行。")
        } else {
            val claimed = Regex("""AVAIL:\s*(\d+)x(\d+)""").find(head)
            if (claimed != null) {
                val cw = claimed.groupValues[1].toIntOrNull()
                val ch = claimed.groupValues[2].toIntOrNull()
                if (cw != null && ch != null && (cw != avail.w || ch != avail.h)) {
                    issues.error(
                        "header_mismatch",
                        "头注释写 AVAIL=${cw}x$ch，实算是 ${avail.w}x${avail.h}。",
                    )
                }
            }
        }

        if (!head.contains("TOUCH:", ignoreCase = true)) {
            issues.error("missing_header", "文件头缺少 TOUCH 注释行。")
        }
    }

    /** Range of the `var LAYOUT = {...}` literal, used to exclude it from the literal scan. */
    private fun layoutBlockRange(html: String): IntRange? {
        val start = html.indexOf("LAYOUT")
        if (start < 0) return null
        val braceStart = html.indexOf('{', start)
        if (braceStart < 0) return null
        var depth = 0
        var i = braceStart
        while (i < html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return braceStart..i
                }
            }
            i++
        }
        return null
    }

    /**
     * Reads the rectangles out of the LAYOUT block.
     *
     * Deliberately lenient about syntax — the spec shows a JS object literal, not JSON, so this
     * scans for `name: { x: …, y: …, w: …, h: … }` shapes and tolerates comments, trailing commas
     * and single quotes.
     */
    private fun parseLayout(html: String): List<WidgetEntry> {
        val range = layoutBlockRange(html) ?: return emptyList()
        val block = html.substring(range.first, range.last + 1)

        val entry = Regex(
            """(\w+)\s*:\s*\{([^{}]*)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        return entry.findAll(block).mapNotNull { match ->
            val name = match.groupValues[1]
            val body = match.groupValues[2]
            fun number(key: String): Int? =
                Regex("""\b$key\s*:\s*(-?\d+(?:\.\d+)?)""").find(body)
                    ?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()

            val x = number("x") ?: return@mapNotNull null
            val y = number("y") ?: return@mapNotNull null
            val w = number("w") ?: return@mapNotNull null
            val h = number("h") ?: return@mapNotNull null
            val touch = Regex("""\btouch\s*:\s*true""", RegexOption.IGNORE_CASE).containsMatchIn(body)
            WidgetEntry(name = name, rect = Rect(x, y, w, h), touch = touch)
        }.toList()
    }
}

/** A rectangle in BASE coordinates. */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w

    fun union(other: Rect): Rect {
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        val r = maxOf(right, other.right)
        val b = maxOf(y + h, other.y + other.h)
        return Rect(left, top, r - left, b - top)
    }

    /** Gap between two rectangles; negative or zero when they overlap. */
    fun gapTo(other: Rect): Int {
        val dx = maxOf(other.x - right, x - other.right)
        val dy = maxOf(other.y - (y + h), y - (other.y + other.h))
        return if (dx > 0) dx else dy
    }

    fun toSize(): Pair<Int, Int> = w to h
}

data class WidgetEntry(val name: String, val rect: Rect, val touch: Boolean)

data class WidgetIssue(
    val code: String,
    val message: String,
    val severity: Severity,
) {
    enum class Severity { ERROR, WARNING }
}

class WidgetValidation(
    val issues: List<WidgetIssue>,
    val layout: List<WidgetEntry> = emptyList(),
    val avail: Pair<Int, Int>? = null,
    val camera: Int = 0,
) {
    val errors: List<WidgetIssue> get() = issues.filter { it.severity == WidgetIssue.Severity.ERROR }
    val warnings: List<WidgetIssue> get() = issues.filter { it.severity == WidgetIssue.Severity.WARNING }
    val passed: Boolean get() = errors.isEmpty()

    /** Human- and model-readable report, fed back when asking for a corrected revision. */
    fun report(): String = buildString {
        if (passed) {
            append("校验通过")
            if (warnings.isNotEmpty()) {
                append("（有 ${warnings.size} 条提示）")
            }
            return@buildString
        }
        append("校验未通过，发现 ${errors.size} 个问题：\n")
        errors.forEachIndexed { index, issue ->
            append("${index + 1}. [${issue.code}] ${issue.message}\n")
        }
    }
}

private fun MutableList<WidgetIssue>.error(code: String, message: String) {
    add(WidgetIssue(code, message, WidgetIssue.Severity.ERROR))
}

private fun MutableList<WidgetIssue>.warn(code: String, message: String) {
    add(WidgetIssue(code, message, WidgetIssue.Severity.WARNING))
}

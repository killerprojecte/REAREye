package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.view.Gravity
import kotlin.math.roundToInt
import android.net.Uri
import org.w3c.dom.Element

/** Places the SurfaceView below MAML painting so its hole does not erase foreground text. */
internal fun prepareAppEmbedXml(element: Element): Element =
    (element.cloneNode(true) as Element).apply {
        if (getAttribute("layerType").isBlank()) setAttribute("layerType", "bottom")
        // Keep native MAML anchor semantics and expressions; reject silent alignment typos.
        for ((attribute, values) in listOf(
            "align" to setOf("left", "center", "right"),
            "alignH" to setOf("left", "center", "right"),
            "alignV" to setOf("top", "center", "bottom"),
        )) {
            val value = getAttribute(attribute).trim().lowercase()
            require(value.isEmpty() || value in values) { "Invalid AppEmbed $attribute: $value" }
            if (hasAttribute(attribute)) setAttribute(attribute, value)
        }
    }

/**
 * 一个 `<AppEmbed>` 元素的已校验配置。
 *
 * 配置在创建任何远端任务前一次性完成严格校验，避免把格式错误推迟到 SystemUI 或目标
 * Activity 启动阶段。同屏 TaskView 必须使用 AppEmbed View 的实际物理像素尺寸；旧
 * VirtualDisplay 尺寸属性 `dw`、`dh` 会明确拒绝，`dpi`/`density` 可覆盖任务配置密度。
 */
data class AppEmbedSpec(
    /** 目标 Activity 所属包；用于构造显式 ComponentName 和服务端身份核验。 */
    val packageName: String,
    /** 目标 Activity 完整类名；相对类名在解析时已展开。 */
    val className: String,
    /** 可选 Intent action；为空时只用显式 component 启动。 */
    val action: String?,
    /** 可选 Intent data URI。 */
    val data: Uri?,
    /** 原样附加到启动 Intent 的 flags。 */
    val flags: Int,
    /** Always false: all input belongs to MAML, including XMLs that previously requested touch. */
    val touchable: Boolean,
    /** 保留解析结果；同屏后端要求始终为 null。 */
    val contentWidthPx: Int?,
    /** 保留解析结果；同屏后端要求始终为 null。 */
    val contentHeightPx: Int?,
    /** 可选任务配置密度；为空时继承任务所在物理显示的密度。 */
    val densityDpi: Int?,
    /** 可选任务绝对屏幕 bounds；为空时始终跟随 AppEmbed View 的屏幕位置。 */
    val taskBoundsOverride: Rect?,
    val aspectRatio: Float = 0f,
    val gravity: Int = Gravity.CENTER,
    val scale: Float = 1f,
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
) {
    /** 为 SystemUI broker 构造必须带显式 component 的启动 Intent。 */
    fun buildLaunchIntent(): Intent = Intent().apply {
        component = ComponentName(packageName, className)
        this@AppEmbedSpec.action?.let { action = it }
        this@AppEmbedSpec.data?.let { data = it }
        if (this@AppEmbedSpec.flags != 0) addFlags(this@AppEmbedSpec.flags)
    }

    /** 返回与当前 SurfaceView 完全一致的同屏 TaskView 尺寸。 */
    fun resolveContentSize(viewWidthPx: Int, viewHeightPx: Int): AppEmbedContentSize {
        require(viewWidthPx > 0 && viewHeightPx > 0) {
            "AppEmbed view size must be positive: ${viewWidthPx}x$viewHeightPx"
        }
        return AppEmbedContentSize(
            widthPx = contentWidthPx ?: viewWidthPx,
            heightPx = contentHeightPx ?: viewHeightPx,
        )
    }

    /** 根据实时 View 屏幕位置解析任务 bounds，显式 XML bounds 的优先级更高。 */
    fun resolveTaskBounds(viewBoundsOnScreen: Rect): Rect {
        require(!viewBoundsOnScreen.isEmpty) {
            "AppEmbed view bounds must be non-empty: $viewBoundsOnScreen"
        }
        taskBoundsOverride?.let { return Rect(it) }
        val insetBounds = Rect(
            viewBoundsOnScreen.left + insetLeft,
            viewBoundsOnScreen.top + insetTop,
            (viewBoundsOnScreen.right - insetRight).coerceAtLeast(viewBoundsOnScreen.left + insetLeft + 1),
            (viewBoundsOnScreen.bottom - insetBottom).coerceAtLeast(viewBoundsOnScreen.top + insetTop + 1),
        )
        if (aspectRatio <= 0f) return insetBounds
        val width = insetBounds.width()
        val height = insetBounds.height()
        val containerRatio = width.toFloat() / height
        val rawWidth: Int
        val rawHeight: Int
        if (aspectRatio >= containerRatio) {
            rawWidth = width
            rawHeight = (width / aspectRatio).roundToInt().coerceAtLeast(1)
        } else {
            rawHeight = height
            rawWidth = (height * aspectRatio).roundToInt().coerceAtLeast(1)
        }
        val resultWidth = (rawWidth * scale).roundToInt().coerceIn(1, width)
        val resultHeight = (rawHeight * scale).roundToInt().coerceIn(1, height)
        val left = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.LEFT -> 0
            Gravity.RIGHT -> width - resultWidth
            else -> (width - resultWidth) / 2
        }
        val top = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.TOP -> 0
            Gravity.BOTTOM -> height - resultHeight
            else -> (height - resultHeight) / 2
        }
        return Rect(
            insetBounds.left + left,
            insetBounds.top + top,
            insetBounds.left + left + resultWidth,
            insetBounds.top + top + resultHeight,
        )
    }

    companion object {
        private val PACKAGE_SEGMENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val CLASS_SEGMENT = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** 从 MAML DOM 元素读取并严格校验全部 AppEmbed 属性。 */
        fun parse(xml: Element): AppEmbedSpec {
            val packageName = firstNonBlank(xml, "package", "packageName")
                ?: throw IllegalArgumentException("AppEmbed requires package")
            requireValidPackageName(packageName)

            val rawClassName = firstNonBlank(xml, "class", "className", "activity")
                ?: throw IllegalArgumentException("AppEmbed requires class")
            val className = qualifyClassName(packageName, rawClassName)

            val width = parseOptionalPositiveInt(xml, "dw", "displayWidth")
            val height = parseOptionalPositiveInt(xml, "dh", "displayHeight")
            require((width == null) == (height == null)) {
                "AppEmbed dw/displayWidth and dh/displayHeight must be specified together"
            }
            require(width == null && height == null) {
                "AppEmbed dw/dh are not supported by the same-display TaskView backend"
            }
            val density = parseOptionalPositiveInt(xml, "dpi", "density")

            return AppEmbedSpec(
                packageName = packageName,
                className = className,
                action = xml.getAttribute("action").trim().takeIf(String::isNotEmpty),
                data = firstNonBlank(xml, "data", "uri")?.let(Uri::parse),
                flags = parseFlags(xml.getAttribute("flags").trim()),
                touchable = parseTouchable(xml.getAttribute("touchable").trim()),
                contentWidthPx = width,
                contentHeightPx = height,
                densityDpi = density,
                taskBoundsOverride = parseBounds(xml.getAttribute("bounds").trim()),
                aspectRatio = parseOptionalPositiveFloat(xml, "aspectRatio", "ratio") ?: 0f,
                gravity = parseGravity(xml.getAttribute("gravity").trim()),
                scale = parseOptionalPositiveFloat(xml, "scale") ?: 1f,
                insetLeft = parseOptionalNonNegativeInt(xml, "insetLeft") ?: 0,
                insetTop = parseOptionalNonNegativeInt(xml, "insetTop") ?: 0,
                insetRight = parseOptionalNonNegativeInt(xml, "insetRight") ?: 0,
                insetBottom = parseOptionalNonNegativeInt(xml, "insetBottom") ?: 0,
            )
        }

        /** 读取第一个非空属性，属性别名属于既有 MAML XML 接口。 */
        private fun firstNonBlank(xml: Element, vararg names: String): String? {
            for (name in names) {
                val value = xml.getAttribute(name).trim()
                if (value.isNotEmpty()) return value
            }
            return null
        }

        /** 校验 Android 包名，拒绝空段及不能出现在 Java 标识符中的字符。 */
        private fun requireValidPackageName(packageName: String) {
            val segments = packageName.split('.')
            require(segments.size >= 2 && segments.all(PACKAGE_SEGMENT::matches)) {
                "Invalid AppEmbed package: $packageName"
            }
        }

        /** 把 `.Activity`、短类名和完整类名统一为完整 Activity 类名。 */
        private fun qualifyClassName(packageName: String, rawClassName: String): String {
            val result = when {
                rawClassName.startsWith('.') -> packageName + rawClassName
                '.' in rawClassName -> rawClassName
                else -> "$packageName.$rawClassName"
            }
            require(result.split('.').all(CLASS_SEGMENT::matches)) {
                "Invalid AppEmbed class: $rawClassName"
            }
            return result
        }

        /** 解析十进制或 `0x` 十六进制 Intent flags，溢出或脏字符立即失败。 */
        private fun parseFlags(raw: String): Int {
            if (raw.isEmpty()) return 0
            return try {
                if (raw.startsWith("0x", ignoreCase = true)) {
                    raw.substring(2).toUInt(16).toInt()
                } else {
                    raw.toInt()
                }
            } catch (error: NumberFormatException) {
                throw IllegalArgumentException("Invalid AppEmbed flags: $raw", error)
            }
        }

        /** Validate existing XML syntax while enforcing display-only embedding. */
        private fun parseTouchable(raw: String): Boolean = when (raw.lowercase()) {
            "", "true", "1", "false", "0" -> false
            else -> throw IllegalArgumentException("Invalid AppEmbed touchable: $raw")
        }

        /** 解析一个可选正整数属性及其既有别名。 */
        private fun parseOptionalPositiveInt(xml: Element, vararg names: String): Int? {
            val raw = firstNonBlank(xml, *names) ?: return null
            val value = raw.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid AppEmbed ${names.first()}: $raw")
            require(value > 0) { "AppEmbed ${names.first()} must be positive: $value" }
            return value
        }

        private fun parseOptionalPositiveFloat(xml: Element, vararg names: String): Float? {
            val raw = firstNonBlank(xml, *names) ?: return null
            val value = raw.toFloatOrNull() ?: throw IllegalArgumentException("Invalid AppEmbed ${names.first()}: $raw")
            require(value > 0f) { "AppEmbed ${names.first()} must be positive: $value" }
            return value
        }

        private fun parseOptionalNonNegativeInt(xml: Element, name: String): Int? {
            val raw = xml.getAttribute(name).trim()
            if (raw.isEmpty()) return null
            val value = raw.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid AppEmbed $name: $raw")
            require(value >= 0) { "AppEmbed $name must be non-negative: $value" }
            return value
        }

        private fun parseGravity(raw: String): Int = when (raw.lowercase()) {
            "", "center" -> Gravity.CENTER
            "left", "top_left" -> Gravity.LEFT or Gravity.TOP
            "right", "top_right" -> Gravity.RIGHT or Gravity.TOP
            "bottom_left" -> Gravity.LEFT or Gravity.BOTTOM
            "bottom_right" -> Gravity.RIGHT or Gravity.BOTTOM
            else -> throw IllegalArgumentException("Invalid AppEmbed gravity: $raw")
        }

        /** 解析 `left,top,right,bottom` 绝对屏幕 bounds。 */
        private fun parseBounds(raw: String): Rect? {
            if (raw.isEmpty()) return null
            val parts = raw.split(',', ' ', ';').filter(String::isNotBlank)
            require(parts.size == 4) {
                "AppEmbed bounds must contain exactly four integers: $raw"
            }
            val values = parts.map { part ->
                part.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid AppEmbed bounds: $raw")
            }
            return Rect(values[0], values[1], values[2], values[3]).also { bounds ->
                require(!bounds.isEmpty) { "AppEmbed bounds must be non-empty: $raw" }
            }
        }
    }
}

/** 远端 SurfaceControlViewHost/TaskView 使用的确定内容尺寸。 */
data class AppEmbedContentSize(
    /** 远端内容宽度，单位为物理像素。 */
    val widthPx: Int,
    /** 远端内容高度，单位为物理像素。 */
    val heightPx: Int,
)

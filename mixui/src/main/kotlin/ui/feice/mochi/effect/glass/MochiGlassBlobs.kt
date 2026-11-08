// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.effect.glass.engine.GlassMerge
import ui.feice.mochi.effect.glass.engine.GlassMergeMax
import ui.feice.mochi.effect.glass.engine.GlassMergePartner
import ui.feice.mochi.effect.metaball.MochiBlob
import ui.feice.mochi.effect.metaball.MochiMetaballColors
import ui.feice.mochi.effect.metaball.MochiMetaballFrame
import ui.feice.mochi.effect.metaball.drawMochiMetaball
import ui.feice.mochi.effect.metaball.proximityFuse
import ui.feice.mochi.effect.metaball.rememberMochiMetaballRenderer
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.theme.MochiGlass
import ui.feice.mochi.theme.MochiTheme

/**
 * 几块圆角矩形画成一块会融在一起的表面，用在形变转场里（栏收成小胶囊、按钮被吸进胶囊、从胶囊里长出来）。
 * 按主题材质画，和 [MochiGlassSurface] 同一种：
 * - 玻璃（液态玻璃、只模糊的毛玻璃）：Android 13+ 是一块玻璃，几块做光滑并集（主形状加最多 6 块）；更低的系统每块单独一块玻璃，不连
 * - 没有玻璃：栏按钮的纯色（[MochiGlass.fallback]），挨近的用融球颈连起来，半透明的颜色整体只叠一次
 *
 * [blobs] 每帧读（这个组件里的 px），第一块是主形状，其余按顺序并进主形状；宽或高不到 1px 的跳过。
 * 某一块离主形状越近颈越粗：间距 ≥ [fuse] 时不连，贴上时光滑并集半径是 [fuse]（px，每帧读；一块被吸收到另一块里面后要把它收到 0，
 * 不然里面那块离边不到 [fuse] 时还会把外轮廓顶出鼓包）。
 * 整体淡入淡出用 [alpha]（外阴影跟着淡，不会被切成直角）。这一层只画底，图标、文字放在它上面
 */
@Composable
public fun MochiGlassBlobs(
    blobs: () -> List<MochiBlob>,
    modifier: Modifier = Modifier,
    fuse: () -> Float = { 0f },
    alpha: () -> Float = { 1f },
    material: MochiGlass? = MochiTheme.materials.controlGlass,
    backdrop: MochiBackdrop? = LocalMochiBackdrop.current,
) {
    val fill = rememberGlassFill(material, backdrop?.source)
    if (fill == null) {
        SolidBlobs(blobs, fuse, alpha, material, modifier)
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // 没有着色器，玻璃节点不会按并集裁形状：每块单独一块
        Box(modifier) {
            repeat(GlassMergeMax + 1) { i ->
                GlassSurface(
                    fill,
                    null,
                    null,
                    Modifier.fillMaxSize().placeAt { blobs().visible().getOrNull(i)?.rect },
                    alpha = { if (blobs().visible().getOrNull(i) == null) 0f else alpha() },
                    cornerRadiusPx = { blobs().visible().getOrNull(i)?.cornerRadius ?: 0f },
                )
            }
        }
        return
    }
    Box(modifier) {
        GlassSurface(
            fill,
            null,
            null,
            Modifier.fillMaxSize().placeAt { bounds(blobs().visible(), fuse()) },
            alpha = { if (blobs().visible().isEmpty()) 0f else alpha() },
            cornerRadiusPx = { blobs().visible().firstOrNull()?.cornerRadius ?: 0f },
            merge = {
                val list = blobs().visible()
                val main = list.firstOrNull()
                if (main == null) {
                    null
                } else {
                    val k = fuse()
                    val o = bounds(list, k)?.topLeft ?: Offset.Zero
                    GlassMerge(
                        main.rect.translate(-o),
                        list.drop(1).take(GlassMergeMax).map {
                            val neck = if (k <= 0f) 0f else k * proximityFuse(it.rect, main.rect, k)
                            GlassMergePartner(it.rect.translate(-o), it.cornerRadius, neck)
                        },
                    )
                }
            },
        )
    }
}

/** 没有玻璃：纯色，和主形状挨近的块用融球颈连上。整层先按不透明画完再一次性乘上颜色的透明度，重叠处不会叠深 */
@Composable
private fun SolidBlobs(
    blobs: () -> List<MochiBlob>,
    fuse: () -> Float,
    alpha: () -> Float,
    material: MochiGlass?,
    modifier: Modifier = Modifier,
) {
    val color = material?.fallback ?: MochiButtonDefaults.barColors().container
    val renderer = rememberMochiMetaballRenderer()
    val path = androidx.compose.runtime.remember { G2PathCache() }
    val opaque = color.copy(alpha = 1f)
    Canvas(
        modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            this.alpha = (color.alpha * alpha()).coerceIn(0f, 1f)
        },
    ) {
        val list = blobs().visible()
        val main = list.firstOrNull() ?: return@Canvas
        if (list.size == 1) {
            drawPath(path.get(main.rect, main.cornerRadius, 1f), opaque)
            return@Canvas
        }
        val k = fuse()
        for (b in list.drop(1)) {
            val f = if (k <= 0f) 0f else k * proximityFuse(b.rect, main.rect, k)
            drawMochiMetaball(
                renderer,
                MochiMetaballFrame(main, b, f),
                MochiMetaballColors(opaque, opaque, androidx.compose.ui.graphics.Color.Transparent),
            )
        }
    }
}

private fun List<MochiBlob>.visible(): List<MochiBlob> = filter { it.rect.width >= 1f && it.rect.height >= 1f }

/** 所有块的外包框，四周留出光滑并集鼓出来的余量，取整到像素 */
private fun bounds(list: List<MochiBlob>, fuse: Float): Rect? {
    if (list.isEmpty()) return null
    val m = fuse / 4f + 2f
    return Rect(
        floor(list.minOf { it.rect.left } - m),
        floor(list.minOf { it.rect.top } - m),
        ceil(list.maxOf { it.rect.right } + m),
        ceil(list.maxOf { it.rect.bottom } + m),
    )
}

/** 按 [rect]（每次排版时读）排版、摆放这一层；没有时排成 1px 放在原点（由透明度藏起来） */
private fun Modifier.placeAt(rect: () -> Rect?): Modifier = layout { measurable, constraints ->
    val r = rect()
    val w = r?.width?.roundToInt()?.coerceAtLeast(1) ?: 1
    val h = r?.height?.roundToInt()?.coerceAtLeast(1) ?: 1
    val placeable = measurable.measure(Constraints.fixed(w, h))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(r?.left?.roundToInt() ?: 0, r?.top?.roundToInt() ?: 0) }
}

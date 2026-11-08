// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass

import androidx.compose.runtime.Composable
import ui.feice.mochi.theme.MochiGlass
import ui.feice.mochi.theme.MochiMaterials
import ui.feice.mochi.theme.MochiTheme

/**
 * 库里画玻璃的地方按角色分，组件只问「我这个角色画不画玻璃、取样哪份背景」，不自己读 [MochiMaterials] 的字段。
 * 主题的材质只决定浮在内容上的这些角色；正文里的按钮画不画玻璃由按钮样式决定（见 MochiButtonStyle），不在这里。
 */
internal enum class GlassRole {
    /** 浮在内容上的栏（标签栏） */
    Bar,

    /** 栏里的按钮、搜索框，以及显式写了玻璃样式的正文按钮 */
    Control,

    /** iOS 的 .clear 玻璃（clearGlass / prominentClearGlass 样式的按钮） */
    Clear,

    /** 下拉菜单、上下文菜单、气泡 */
    Menu,

    /** 警告框、操作确认框 */
    Alert,

    /** 面板 */
    Sheet,
}

/** 某个角色用的玻璃材质，按 [MochiMaterials] 的回退顺序取；null = 画纯色 */
internal fun MochiMaterials.glassFor(role: GlassRole): MochiGlass? = when (role) {
    GlassRole.Bar -> bar
    GlassRole.Control -> controlGlass
    GlassRole.Clear -> clearGlass
    GlassRole.Menu -> menuGlass
    GlassRole.Alert -> alertGlass
    GlassRole.Sheet -> sheetGlass
}

/** 当前主题下这个角色画不画玻璃 */
@Composable
internal fun glassOn(role: GlassRole): Boolean = MochiTheme.materials.glassFor(role) != null

/**
 * 这个角色的玻璃：材质跟主题，背景默认取这个角色该取样的那份
 * （栏里的控件取样栏下面实际看到的样子，菜单、警告框、面板取样打开它时底下的内容）；没有材质或没有背景时是 null，画纯色
 */
@Composable
internal fun rememberGlassFill(role: GlassRole, source: GlassSource? = defaultSource(role)): GlassFill? =
    rememberGlassFill(MochiTheme.materials.glassFor(role), source)

@Composable
private fun defaultSource(role: GlassRole): GlassSource? = when (role) {
    GlassRole.Bar, GlassRole.Control, GlassRole.Clear -> barGlassSource()
    GlassRole.Menu, GlassRole.Alert, GlassRole.Sheet -> LocalGlassSources.current.overlay
}

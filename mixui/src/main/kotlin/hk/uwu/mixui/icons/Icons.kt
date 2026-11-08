package hk.uwu.mixui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Tune

/** Shared vector assets; application layouts never depend on a vendor icon namespace. */
object Icons {
    val Add: ImageVector get() = MiuixIcons.Add
    val Create: ImageVector get() = MiuixIcons.Create
    val Delete: ImageVector get() = MiuixIcons.Delete
    val Download: ImageVector get() = MiuixIcons.Download
    val ExpandMore: ImageVector get() = MiuixIcons.ExpandMore
    val Info: ImageVector get() = MiuixIcons.Info
    val Link: ImageVector get() = MiuixIcons.Link
    val More: ImageVector get() = MiuixIcons.More
    val MoreCircle: ImageVector get() = MiuixIcons.MoreCircle
    val Refresh: ImageVector get() = MiuixIcons.Refresh
    val Settings: ImageVector get() = MiuixIcons.Settings
    object Basic {
        val Check: ImageVector get() = MiuixIcons.Basic.Check
        val Search: ImageVector get() = MiuixIcons.Basic.Search
        val SearchCleanup: ImageVector get() = MiuixIcons.Basic.SearchCleanup
    }
    object Regular {
        val Link: ImageVector get() = MiuixIcons.Regular.Link
        val MoreCircle: ImageVector get() = MiuixIcons.Regular.MoreCircle
        val Sort: ImageVector get() = MiuixIcons.Regular.Sort
        val Tune: ImageVector get() = MiuixIcons.Regular.Tune
    }
}

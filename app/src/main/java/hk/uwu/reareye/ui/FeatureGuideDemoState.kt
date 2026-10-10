package hk.uwu.reareye.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.HookPrefsEditor
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperInfo
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearCardConfig
import hk.uwu.reareye.repository.rearwidget.RearCardOrderSetting
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.ui.config.PrefsManager

internal enum class FeatureGuideAction {
    OPEN_CARD, SAVE_CARD, TOGGLE_CARD, OPEN_COMPONENT, SAVE_COMPONENT, APPLY_WALLPAPER,
}

internal data class FeatureGuideDemoContext(
    val state: FeatureGuideDemoState,
    val action: FeatureGuideAction?,
    val onAction: (FeatureGuideAction) -> Unit,
    val onCancelEdit: () -> Unit,
)

internal val LocalFeatureGuideDemo = staticCompositionLocalOf<FeatureGuideDemoContext?> { null }

/** Production models, held only for this guide session; no live storage or runtime. */
internal class FeatureGuideDemoState(initialCardTitle: String = "demo_clock") {
    val prefsManager = PrefsManager(GuideMemoryPrefs())
    var cards by mutableStateOf(
        listOf(
            RearCardConfig(
                id = "guide-card",
                title = initialCardTitle,
                packageName = "hk.uwu.reareye",
                business = "demo_clock",
            )
        )
    )
    var businesses by mutableStateOf(
        listOf(
            RearBusinessConfig(
                id = "guide-component", packageName = "hk.uwu.reareye", business = "demo_clock",
                filePath = "/demo/clock.zip",
            )
        )
    )
    var orderSettings by mutableStateOf(mapOf("guide-card" to RearCardOrderSetting(automatic = false)))
    var hideTimeTip by mutableStateOf(false)
    var currentWallpaperId by mutableStateOf<Int?>(null)
    fun wallpapers(title: String) = listOf(
        RearWallpaperInfo(
            wallpaperId = -1001, title = title, name = title,
            previewAvailable = true, previewSignature = "guide-dusk", cachePath = PREVIEW_KEY,
        )
    )

    companion object {
        const val PREVIEW_KEY = "guide://wallpaper/dusk"
        val Saver = listSaver<FeatureGuideDemoState, Any>(
            save = {
                listOf(
                    RearWidgetConfigCodec.encodeCards(it.cards),
                    RearWidgetConfigCodec.encodeBusinesses(it.businesses), it.hideTimeTip,
                    it.currentWallpaperId ?: 0, it.orderSettings["guide-card"]?.automatic ?: false
                )
            },
            restore = { values ->
                FeatureGuideDemoState().apply {
                    cards = RearWidgetConfigCodec.parseCards(values[0] as String)
                    businesses = RearWidgetConfigCodec.parseBusinesses(values[1] as String)
                    hideTimeTip = values[2] as Boolean
                    currentWallpaperId = (values[3] as Int).takeIf { it != 0 }
                    orderSettings =
                        mapOf("guide-card" to RearCardOrderSetting(automatic = values[4] as Boolean))
                }
            },
        )
    }
}

/** A separate preference facade ensures even incidental UI reads never use real settings. */
internal class GuideMemoryPrefs : HookPrefs {
    private val values = mutableMapOf<String, Any?>()
    override fun getString(key: String, defaultValue: String) =
        values[key] as? String ?: defaultValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defaultValue: Set<String>) =
        (values[key] as? Set<String>)?.toSet() ?: defaultValue

    override fun getInt(key: String, defaultValue: Int) = values[key] as? Int ?: defaultValue
    override fun getLong(key: String, defaultValue: Long) = values[key] as? Long ?: defaultValue
    override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean) =
        values[key] as? Boolean ?: defaultValue

    override fun contains(key: String) = values.containsKey(key)
    override fun all(): Map<String, Any?> = values.toMap()
    override fun edit(): HookPrefsEditor = object : HookPrefsEditor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: Set<String>?) =
            apply { pending[key] = value?.toSet() }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            apply(); return true
        }

        override fun apply() {
            if (clearAll) values.clear()
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}

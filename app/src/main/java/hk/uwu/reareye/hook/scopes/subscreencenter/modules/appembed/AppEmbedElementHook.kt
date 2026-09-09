package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.hook.core.YukiBaseHooker
import org.w3c.dom.Element

/**
 * 在 `com.xiaomi.subscreencenter` 宿主中注册自定义 MAML 元素 `<AppEmbed>`。
 *
 * 不走 `FactoryCallback`（避免与宿主已有 callback 冲突），直接 hook
 * `ScreenElementFactory.createInstance`，在标签命中时返回
 * [AppEmbedElementFactory] 动态生成的 `ViewHolderScreenElement` 子类实例。
 *
 * 生成元素只负责把 MAML 生命周期交给 [AppEmbedBridge]；实际任务由 SystemUI 中已存在
 * 的 TaskView 基础设施创建，并通过 SurfacePackage 绑定到元素的 SurfaceView。
 */
class AppEmbedElementHook : YukiBaseHooker() {

    companion object {
        private const val TAG = "AppEmbedElementHook"
        private const val PACKAGE_SUBSCREEN = "com.xiaomi.subscreencenter"
    }

    override fun onHook() {
        loadApp(PACKAGE_SUBSCREEN) {
            trackResource(AppEmbedBridge)
            "com.miui.maml.elements.ScreenElementFactory".toClass().resolve()
                .firstMethod {
                    name = "createInstance"
                    parameters("org.w3c.dom.Element", "com.miui.maml.ScreenElementRoot")
                }
                .hook()
                .before {
                    if (!reloadGenerationGate.isOpen()) return@before
                    val xml = args(0).any() as? Element ?: return@before
                    if (!xml.tagName.equals(AppEmbedElementFactory.TAG_NAME, ignoreCase = true)) {
                        return@before
                    }
                    val root = args(1).any() ?: return@before
                    try {
                        result = AppEmbedElementFactory.createElement(
                            hostClassLoader = appClassLoader,
                            element = xml,
                            root = root,
                        )
                    } catch (error: Throwable) {
                        YLog.error("[$TAG] AppEmbed element creation failed", error)
                    }
                }
        }
    }

}

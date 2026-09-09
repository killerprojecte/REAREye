package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.content.Context
import android.view.View
import hk.uwu.reareye.hook.core.YLog
import org.w3c.dom.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * AppEmbed 生成元素与模块实现之间的 ClassLoader 中立桥。
 *
 * 宿主 Dex 适配器在构造阶段直接取出 MAML ScreenContext.mContext，并只向这里传公开的
 * Android Context 与 DOM Element。桥内不反射 MAML 或 hidden framework 对象。
 */
object AppEmbedBridge : AutoCloseable {
    private const val TAG = "AppEmbedBridge"

    /** 当前 Hook 代创建且尚未 finish 的全部元素，用于热重载时统一关闭远端 capability。 */
    private val delegates = ConcurrentHashMap.newKeySet<AppEmbedDelegate>()

    /** 关闭后拒绝旧代生成元素继续创建会话。 */
    @Volatile
    private var closed = false

    /** 解析 XML 并创建一个由当前代拥有的元素委托。 */
    @JvmStatic
    fun create(context: Context, xml: Element): Any {
        check(!closed) { "AppEmbed bridge generation is closed" }
        return AppEmbedDelegate(context, AppEmbedSpec.parse(xml)).also(delegates::add)
    }

    /** 返回 MAML ViewHolderScreenElement 要挂载的真实 SurfaceView。 */
    @JvmStatic
    fun getView(delegate: Any): View = requireDelegate(delegate).let {
        check(!closed) { "AppEmbed bridge generation is closed" }
        delegates.add(it)
        it.resolveView()
    }

    /** View 加入 MAML 容器；SurfaceView 自身 attach/surface 回调负责启动远端会话。 */
    @JvmStatic
    fun onViewAdded(delegate: Any, view: View?) {
        requireDelegate(delegate).verifyView(view)
    }

    /** View 离开 MAML 容器时释放本代 SurfaceView，允许后续 getView 重建。 */
    @JvmStatic
    fun onViewRemoved(delegate: Any, view: View?) {
        val appEmbed = requireDelegate(delegate)
        appEmbed.verifyView(view)
        appEmbed.releaseView()
    }

    /** MAML may init the same element again; finish releases this activation, not the delegate. */
    @JvmStatic
    fun onFinish(delegate: Any) {
        val appEmbed = requireDelegate(delegate)
        delegates.remove(appEmbed)
        appEmbed.releaseView()
    }

    /** MAML can pause a retained View without hiding or destroying its Android Surface. */
    @JvmStatic
    fun onPause(delegate: Any) {
        requireDelegate(delegate).setMamlActive(false)
    }

    /** Resume explicitly re-arms this activation even when Android reports no new Surface. */
    @JvmStatic
    fun onResume(delegate: Any) {
        requireDelegate(delegate).setMamlActive(true)
    }

    /** 生成类或 bridge 调用失败时的统一日志出口。 */
    @JvmStatic
    fun onError(where: String, error: Throwable) {
        YLog.error("[$TAG] generated element failed at $where", error)
    }

    /** 校验生成元素保存的 delegate 类型，拒绝旧代或损坏实例。 */
    private fun requireDelegate(value: Any): AppEmbedDelegate = value as? AppEmbedDelegate
        ?: error("AppEmbed generated element has an invalid delegate: ${value.javaClass.name}")

    /** 热重载冻结时关闭这一代全部元素、Binder capability 与输入区域。 */
    override fun close() {
        if (closed) return
        closed = true
        val snapshot = delegates.toList()
        delegates.clear()
        snapshot.forEach { delegate ->
            try {
                delegate.close()
            } catch (error: Throwable) {
                YLog.error("[$TAG] delegate cleanup failed pkg=${delegate.spec.packageName}", error)
            }
        }
    }
}

/** 单个 MAML AppEmbed 元素的本地 View 生命周期所有者。 */
private class AppEmbedDelegate(
    /** 创建 SurfaceView 的真实 subscreencenter Context。 */
    private val context: Context,
    /** 已严格校验的元素配置。 */
    val spec: AppEmbedSpec,
) : AutoCloseable {
    /** 当前挂载代使用的 SurfaceView，remove 后置空以支持再次加入。 */
    private var currentView: AppEmbedSurfaceView? = null

    /** finish 后拒绝生成新的 View。 */
    private var closed = false

    /** 返回现有 View，或为本次挂载创建完整同屏 TaskView 客户端。 */
    fun resolveView(): AppEmbedSurfaceView {
        check(!closed) { "AppEmbed element is finished" }
        return currentView ?: AppEmbedSurfaceView(context, spec).also { currentView = it }
    }

    /** 确认 MAML 回调中的 View 就是本委托当前实例。 */
    fun verifyView(view: View?) {
        val expected = currentView ?: error("AppEmbed callback arrived before getView")
        check(view === expected) {
            "AppEmbed callback View mismatch: expected=$expected actual=$view"
        }
    }

    /** Only a mounted activation needs a pause/resume notification; init creates missing Views. */
    fun setMamlActive(active: Boolean) {
        currentView?.setMamlActive(active)
    }

    /** 释放当前挂载代 View；后续 resolveView 会创建新实例。 */
    fun releaseView() {
        val view = currentView ?: return
        currentView = null
        view.close()
    }

    /** 永久结束元素并释放当前 View。 */
    override fun close() {
        if (closed) return
        closed = true
        releaseView()
    }
}

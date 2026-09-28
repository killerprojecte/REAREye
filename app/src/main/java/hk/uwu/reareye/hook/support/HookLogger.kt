package hk.uwu.reareye.hook.support

import hk.uwu.roxyhook.RLog

/**
 * YLog 兼容门面。
 *
 * 现有 Hook 模块只依赖 debug/info/warn/error 四个级别，因此门面保留这些调用习惯，
 * 底层直接委托 RoxyHook 的 RLog，不再暴露 YukiHookAPI 类型。
 */
object YLog {
    /** 输出 debug 文本或异常。 */
    fun debug(message: Any?) = RLog.debug(message.toString())

    /** 输出 info 文本或异常。 */
    fun info(message: Any?) = RLog.info(message.toString())

    /** 输出 warn 文本或异常。 */
    fun warn(message: Any?) {
        if (message is Throwable) RLog.warn(message.message.orEmpty(), message)
        else RLog.warn(message.toString())
    }

    /** 输出 error 文本或异常。 */
    fun error(message: Any?) {
        if (message is Throwable) RLog.error(message.message.orEmpty(), message)
        else RLog.error(message.toString())
    }

    /** 输出带异常对象的 debug 文本。 */
    fun debug(message: Any?, throwable: Throwable?) = RLog.debug(message.toString(), throwable)

    /** 输出带异常对象的 info 文本。 */
    fun info(message: Any?, throwable: Throwable?) = RLog.info(message.toString(), throwable)

    /** 输出带异常对象的 warn 文本。 */
    fun warn(message: Any?, throwable: Throwable?) = RLog.warn(message.toString(), throwable)

    /** 输出带异常对象的 error 文本。 */
    fun error(message: Any?, throwable: Throwable?) = RLog.error(message.toString(), throwable)
}





package android.util

object Log {
    @JvmStatic
    fun d(tag: String?, msg: String?): Int = 0

    @JvmStatic
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = 0

    @JvmStatic
    fun e(tag: String?, msg: String?): Int = 0

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = 0

    @JvmStatic
    fun w(tag: String?, msg: String?): Int = 0

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0
}

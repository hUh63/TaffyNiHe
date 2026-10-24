package top.wkbin.taixu.host

import android.content.Context
import android.content.SharedPreferences

/**
 * 太墟壳启动守卫（Safe Mode），由壳自己维护。
 *
 * 为什么放在壳模块里：壳的启动健康度只有壳自己知道（首帧什么时候真正画出来）。
 * 宿主（塔菲逆核）只读一个布尔结论，不需要参与壳的生命周期。
 *
 * 1. 壳 Activity `onCreate` 起手写 `pending = true`；
 * 2. 首帧真正绘制完成后清掉 `pending`、失败计数归零；
 * 3. 下次启动若发现 `pending` 仍在 → 上次首帧前就死了 → 失败计数 +1；
 * 4. 达到 [MAX_FAILS] 次即判定壳不可用，宿主回到自己的界面（逆向工具链始终可用）。
 */
object TaixuShellGuard {
    private const val PREFS = "taixu_shell_guard"
    private const val KEY_PENDING = "launch_pending"
    private const val KEY_FAILS = "launch_failures"
    private const val MAX_FAILS = 1

    fun shouldUseShell(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_PENDING, false)) {
            val fails = prefs.getInt(KEY_FAILS, 0) + 1
            prefs.edit().putInt(KEY_FAILS, fails).putBoolean(KEY_PENDING, false).apply()
            android.util.Log.w("TaixuShellGuard", "上次壳在首帧前退出，累计失败=$fails")
        }
        val fails = prefs.getInt(KEY_FAILS, 0)
        if (fails >= MAX_FAILS) {
            android.util.Log.w("TaixuShellGuard", "壳连续失败 $fails 次，本次不使用壳")
            return false
        }
        return true
    }

    fun markLaunching(context: Context) {
        prefs(context).edit().putBoolean(KEY_PENDING, true).apply()
    }

    fun markRendered(context: Context) {
        prefs(context).edit().putBoolean(KEY_PENDING, false).putInt(KEY_FAILS, 0).apply()
    }

    fun failures(context: Context): Int = prefs(context).getInt(KEY_FAILS, 0)

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

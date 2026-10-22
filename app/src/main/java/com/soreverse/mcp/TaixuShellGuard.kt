package com.soreverse.mcp

import android.content.Context
import android.content.SharedPreferences

/**
 * 太墟壳启动守卫（Safe Mode）。
 *
 * 太墟壳是整树移植的第三方 UI。只要它在「首帧渲染完成前」崩一次，用户就彻底进不去 App——
 * 这是不可接受的。这里用一个极轻的持久化标记做兜底：
 *
 * 1. 决定使用壳层前写入 `pending = true`；
 * 2. 壳层首帧渲染完成后清掉 `pending`、失败计数归零；
 * 3. 下次启动若发现 `pending` 仍为 true，说明上次在渲染前就死了 → 失败计数 +1；
 * 4. 连续 [MAX_FAILS] 次失败即自动改用塔菲原界面（逆向工具链始终可用），并在日志里留痕。
 *
 * 该机制不依赖任何第三方库，也不会写敏感信息。
 */
internal object TaixuShellGuard {
    private const val PREFS = "taixu_shell_guard"
    private const val KEY_PENDING = "launch_pending"
    private const val KEY_FAILS = "launch_failures"
    private const val MAX_FAILS = 2

    fun shouldUseShell(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_PENDING, false)) {
            val fails = prefs.getInt(KEY_FAILS, 0) + 1
            prefs.edit().putInt(KEY_FAILS, fails).putBoolean(KEY_PENDING, false).apply()
            android.util.Log.w("TaixuShellGuard", "上次太墟壳在首帧前退出，累计失败=$fails")
        }
        val fails = prefs.getInt(KEY_FAILS, 0)
        if (fails >= MAX_FAILS) {
            android.util.Log.w("TaixuShellGuard", "太墟壳连续失败 $fails 次，本次改用塔菲原界面")
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

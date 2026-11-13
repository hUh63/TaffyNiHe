package com.soreverse.mcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.soreverse.mcp.MainActivity
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.PermissionManager
import java.util.concurrent.Executors

/**
 * 悬浮控制台：在任何应用之上开一个可拖动的小窗，边用目标 App 边看日志、敲命令。
 *
 * 为什么需要它：调壳/调注入的时候，塔菲切到后台就看不到输出了，而目标应用一被切走状态就变
 * （很多壳会检测前后台）。悬浮窗让"看日志"和"操作目标"能同时进行。
 *
 * 实现上有意不用 Compose：Service 里挂 ComposeView 需要自己搭 LifecycleOwner/SavedState
 * 提供者，一旦宿主 App 的 lifecycle 状态变化就容易崩；这个面板只有日志 + 一行输入，
 * 用原生 View 反而更稳、启动更快。
 */
class FloatConsoleService : Service() {

    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var logView: TextView? = null
    private var panel: LinearLayout? = null
    private var input: EditText? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val logLines = ArrayList<String>()
    private var listener: ((String) -> Unit)? = null
    private var collapsed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            AppLog.w("悬浮控制台", "没有悬浮窗权限，无法显示")
            stopSelf()
            return
        }
        attachWindow()
        val cb: (String) -> Unit = { line: String ->
            main.post { appendLog(line) }
        }
        listener = cb
        AppLog.addListener(cb)
        appendLog("[taffy] 悬浮控制台已启动 · 输入命令后回车执行（走 root/Shizuku 通道）")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        listener?.let { runCatching { AppLog.removeListener(it) } }
        listener = null
        root?.let { v -> runCatching { windowManager?.removeView(v) } }
        root = null
        runCatching { io.shutdownNow() }
        super.onDestroy()
    }

    // ──────────────── 前台通知 ────────────────

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "taffy_float_console"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "悬浮控制台", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("塔菲逆核 · 悬浮控制台")
            .setContentText("点此返回应用；通知栏可随时关闭悬浮窗")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ──────────────── 悬浮窗 ────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun attachWindow() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val lp = WindowManager.LayoutParams(
            dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(140)
        params = lp

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E6101216"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }

        // 标题行（拖动手柄）
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
        }
        val title = TextView(this).apply {
            text = "控制台"
            setTextColor(Color.parseColor("#E6FFFFFF"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        titleBar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val fold = TextView(this).apply {
            text = "▁"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 14f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { toggleCollapse() }
        }
        titleBar.addView(fold)
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#FF8A80"))
            textSize = 14f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { stopSelf() }
        }
        titleBar.addView(close)
        titleBar.setOnTouchListener(DragTouch(lp))
        container.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))

        // 面板主体
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(8))
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(tv, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        logView = tv

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val et = EditText(this).apply {
            hint = "命令（如 id / pm list packages）"
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setTextColor(Color.parseColor("#FFFFFFFF"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        input = et
        inputRow.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val run = TextView(this).apply {
            text = "执行"
            setTextColor(Color.parseColor("#FFFFFFFF"))
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF2F6FED"))
            }
            setOnClickListener { runCommand() }
        }
        val runLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        runLp.marginStart = dp(8)
        inputRow.addView(run, runLp)
        body.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        panel = body
        container.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root = container
        runCatching { wm.addView(container, lp) }.onFailure { e ->
            AppLog.w("悬浮控制台", "添加悬浮窗失败: ${e.message}")
            stopSelf()
        }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        panel?.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        while (logLines.size > 300) logLines.removeAt(0)
        logView?.text = logLines.joinToString("\n")
        (logView?.parent as? ScrollView)?.post {
            (logView?.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun runCommand() {
        val cmd = input?.text?.toString()?.trim().orEmpty()
        if (cmd.isEmpty()) return
        input?.setText("")
        appendLog("$ $cmd")
        val channel = runCatching { PermissionManager.bestChannel().name }.getOrDefault("?")
        io.execute {
            val r = runCatching { PermissionManager.exec(cmd, 30) }.getOrNull()
            main.post {
                if (r == null) appendLog("[exit] 执行异常")
                else {
                    val out = (r.stdout + r.stderr).trim()
                    if (out.isNotEmpty()) appendLog(out.take(6000))
                    appendLog("[exit ${r.code}] via $channel")
                }
            }
        }
    }

    /** 拖动：按下时记录偏移，移动时改 window params。 */
    private inner class DragTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    root?.let { r -> runCatching { windowManager?.updateViewLayout(r, lp) } }
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val NOTIF_ID = 9101
        const val ACTION_STOP = "com.soreverse.mcp.float.STOP"
        const val ACTION_START = "com.soreverse.mcp.float.START"

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, FloatConsoleService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, FloatConsoleService::class.java)) }
        }
    }
}

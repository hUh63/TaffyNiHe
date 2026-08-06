package com.soreverse.mcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.soreverse.mcp.MainActivity
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.EngineProvider
import com.soreverse.mcp.core.IntegrityGuard
import com.soreverse.mcp.core.NetworkInspector
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.mcp.McpHttpServer
import java.util.Locale

class McpForegroundService : Service() {
    private var server: McpHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var floating: View? = null
    private var bubbleText: TextView? = null
    private var windowManager: WindowManager? = null
    private var pulseAnimator: ValueAnimator? = null
    private var activePort: Int = -1
    private var activeHost: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoSnapRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var isCollapsed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> startServer()
            ACTION_STOP -> {
                running = false
                runCatching { server?.tunnel?.requestStop() }
                val sv = server
                server = null
                currentServer = null
                Thread({
                    try { sv?.tunnel?.stop() } catch (e: Throwable) { AppLog.e("tunnel.stop() during ACTION_STOP", e) }
                    try { sv?.stop() } catch (e: Throwable) { AppLog.e("server.stop() during ACTION_STOP", e) }
                }, "mcp-stop").apply { isDaemon = true }.start()
                activePort = -1
                activeHost = ""
                val settings = SettingsStore(this)
                if (settings.floatingEnabled && Settings.canDrawOverlays(this)) {
                    // 保活模式：停止 MCP 服务但保留悬浮窗，显示"服务未启动"
                    createChannel()
                    val zh = settings.language == "zh" || (settings.language == "system" && Locale.getDefault().language == "zh")
                    startForeground(1001, notification(if (zh) "塔菲逆核保活中 · 服务未启动" else "Taffy keep-alive · service off"))
                    updateFloating()
                    AppLog.i("MCP server stopped, keeping service alive for floating window")
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_REFRESH_FLOATING -> {
                val settings = SettingsStore(this)
                if (server == null && settings.floatingEnabled && Settings.canDrawOverlays(this)) {
                    // 服务未运行但悬浮窗已开启：启动前台服务保活
                    createChannel()
                    val zh = settings.language == "zh" || (settings.language == "system" && Locale.getDefault().language == "zh")
                    startForeground(1001, notification(if (zh) "塔菲逆核保活中 · 服务未启动" else "Taffy keep-alive · service off"))
                }
                updateFloating()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Unregister the reconnect Receiver BEFORE calling tunnel.stop() so
        // the STOPPED broadcast that stop() emits cannot spawn a reconnect
        // sub-thread that re-enters tunnel.start() against a dying Service
        // Context.
        unregisterTunnelReconnect()
        // Flip running=false synchronously so any in-flight reconnect
        // thread spawned moments before unregisterTunnelReconnect() sees the
        // guard as false and exits without re-entering tunnel.start().
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        val sv = server
        server = null
        currentServer = null
        runCatching { sv?.tunnel?.requestStop() }
        Thread({
            try {
                sv?.tunnel?.stop()
            } catch (e: Throwable) {
                AppLog.e("tunnel.stop() failed during destroy", e)
            }
            try {
                sv?.stop()
            } catch (e: Throwable) {
                AppLog.e("server.stop() failed during destroy", e)
            }
        }, "mcp-teardown").apply { isDaemon = true }.start()
        wakeLock?.takeIf { it.isHeld }?.release()
        removeFloating()
        AppLog.i("Foreground service destroyed")
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w("Foreground service timeout: startId=$startId type=$fgsType")
        running = false
        runCatching { server?.tunnel?.requestStop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun startServer() {
        if (!IntegrityGuard.isTrusted(applicationContext)) {
            AppLog.e("MCP service start blocked by integrity guard")
            running = false
            stopSelf()
            return
        }
        val settings = SettingsStore(this)
        val host = settings.bindHost
        createChannel()
        // Avoid showing the bind wildcard 0.0.0.0 in the notification: users kept
        // typing 0.0.0.0:8000/mcp as the client URL and it never connects. When
        // bound to all interfaces, surface a real reachable address (LAN IP if
        // available, otherwise 127.0.0.1) plus the required /mcp path.
        val displayText = buildNotificationText(host, settings.port)
        startForeground(1001, notification(displayText))
        updateWakeLock(settings.wakeLockEnabled)
        EngineProvider.restoreWorkDirectory(applicationContext)
        if (server != null && activePort == settings.port && activeHost == host) {
            running = true
            updateFloating()
            AppLog.i("MCP server already running on $host:${settings.port}/mcp")
            return
        }
        server?.stop()
        runCatching {
            server = McpHttpServer(applicationContext, settings.port, host).also { it.start() }
            currentServer = server
            activePort = settings.port
            activeHost = host
            running = true
            updateFloating()
            maybeAutoStartTunnel(settings)
            registerTunnelReconnect(settings)
        }.onFailure {
            running = false
            activePort = -1
            activeHost = ""
            AppLog.e("Failed to start MCP server", it)
            stopSelf()
        }
        AppLog.i("MCP server started on $host:${settings.port}/mcp")
    }

    private fun maybeAutoStartTunnel(settings: SettingsStore) {
        if (!settings.tunnelAutoStart) return
        // If the user enabled "auto-start with service" but kept tunnelMode=off (the default),
        // honour the switch by falling back to QUICK mode and persisting it so the choice sticks
        // across restarts. The previous behaviour silently swallowed the auto-start, which is
        // the root cause of "tunnelAutoStart is on yet Cloudflare never starts".
        val raw = settings.tunnelMode
        val mode = when (raw) {
            "quick" -> com.soreverse.mcp.core.CloudflareTunnelManager.Mode.QUICK
            "named" -> com.soreverse.mcp.core.CloudflareTunnelManager.Mode.NAMED
            else -> {
                AppLog.i("Auto-start tunnel: tunnelMode was '$raw', promoting to 'quick' to honour tunnelAutoStart=true")
                settings.tunnelMode = "quick"
                com.soreverse.mcp.core.CloudflareTunnelManager.Mode.QUICK
            }
        }
        if (mode == com.soreverse.mcp.core.CloudflareTunnelManager.Mode.NAMED && settings.tunnelNamedToken.isBlank()) {
            AppLog.w("Auto-start tunnel: named mode selected but token is blank, skipping")
            return
        }
        Thread {
            // If the user toggled the master switch off again before this
            // thread got scheduled, abort cleanly instead of starting a
            // cloudflared child against a Service Context that is racing
            // to onDestroy. Same root cause as the reconnect race above.
            if (!running) {
                AppLog.i("Auto-start tunnel aborted: service stopped before launch")
                return@Thread
            }
            val target = settings.tunnelTargetPort.coerceAtLeast(settings.port)
            try {
                server?.tunnel?.start(target, mode, settings.tunnelNamedToken)
                AppLog.i("Auto-started Cloudflare tunnel: mode=${settings.tunnelMode} target=$target")
            } catch (e: Throwable) {
                AppLog.w("Auto-start tunnel failed: ${e.message}")
            }
        }.apply { isDaemon = true; name = "tunnel-autostart" }.start()
    }

    private var reconnectReceiver: android.content.BroadcastReceiver? = null
    private fun registerTunnelReconnect(settings: SettingsStore) {
        if (!settings.tunnelReconnect) return
        unregisterTunnelReconnect()
        val filter = android.content.IntentFilter("com.soreverse.mcp.TUNNEL_STATUS")
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val state = intent?.getStringExtra("state") ?: return
                val terminal = intent.getBooleanExtra("terminal", false)
                if (state == "FAILED" && settings.tunnelAutoStart && !terminal) {
                    AppLog.w("Tunnel reported FAILED — scheduling reconnect")
                    Thread {
                        // Back off long enough that an in-flight onDestroy()
                        // has time to finish tearing the tunnel down; the
                        // previous hard-coded 3s was the exact cause of the
                        // crash: the reconnect thread re-entered
                        // CloudflareTunnelManager.start() while stop() was
                        // still joining the watch thread under the same
                        // monitor. Pull the backoff from settings so the
                        // operator can widen it if their device takes longer
                        // to release the cloudflared child.
                        Thread.sleep(settings.tunnelReconnectBackoffSec.coerceIn(1, 60) * 1000L)
                        // Double-gate: `running` flips false synchronously in
                        // both stop(context) and onDestroy, so by the time the
                        // backoff elapses after the FAILED broadcast (which
                        // usually accompanies a user toggle off) we expect
                        // to observe the false here. Skipping the restart
                        // prevents spawning a fresh cloudflared child against
                        // a dying Service Context — the original crash root
                        // cause.
                        if (!running) {
                            AppLog.i("Tunnel reconnect skipped: service no longer running")
                            return@Thread
                        }
                        val mode = when (settings.tunnelMode) {
                            "quick" -> com.soreverse.mcp.core.CloudflareTunnelManager.Mode.QUICK
                            "named" -> com.soreverse.mcp.core.CloudflareTunnelManager.Mode.NAMED
                            else -> com.soreverse.mcp.core.CloudflareTunnelManager.Mode.QUICK
                        }
                        try {
                            server?.tunnel?.start(settings.tunnelTargetPort.coerceAtLeast(settings.port), mode, settings.tunnelNamedToken)
                        } catch (e: Throwable) {
                            AppLog.w("Tunnel reconnect start failed: ${e.message}")
                        }
                    }.apply { isDaemon = true; name = "tunnel-reconnect" }.start()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag", "DEPRECATION")
            registerReceiver(receiver, filter)
        }
        reconnectReceiver = receiver
    }

    private fun unregisterTunnelReconnect() {
        reconnectReceiver?.let { runCatching { unregisterReceiver(it) } }
        reconnectReceiver = null
    }

    private fun updateWakeLock(enabled: Boolean) {
        if (!enabled) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            return
        }
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoReverseMcp:server").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }.onFailure {
            AppLog.e("Failed to acquire WakeLock", it)
        }
    }

    private fun updateFloating() {
        val settings = SettingsStore(this)
        if (!settings.floatingEnabled || !Settings.canDrawOverlays(this)) {
            removeFloating()
            return
        }
        if (floating != null) {
            // 悬浮窗已存在，仅更新状态文字
            updateFloatingStatus()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val zh = settings.language == "zh" || (settings.language == "system" && Locale.getDefault().language == "zh")
        val density = resources.displayMetrics.density
        val isRunning = running
        val statusText = if (isRunning) (if (zh) "● 塔菲逆核运行中" else "● Taffy running") else (if (zh) "● 服务未启动" else "● Service off")
        val dotColor = if (isRunning) Color.argb(255, 52, 199, 89) else Color.argb(255, 255, 149, 0)
        val tv = TextView(this).apply {
            text = statusText
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            setPadding((14 * density).toInt(), (9 * density).toInt(), (14 * density).toInt(), (9 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 24, 30, 42))
                setStroke((1 * density).toInt(), Color.argb(80, 255, 255, 255))
                cornerRadius = 999f
            }
            elevation = 8 * density
            alpha = 0.7f
        }
        bubbleText = tv
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 280
        }
        floatingParams = params
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        val dragThresholdSq = 400f * 400f // 400px² drag threshold
        val longPressTimeout = 1000L

        tv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tv.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    downTime = System.currentTimeMillis()
                    moved = false
                    cancelScheduled()
                    if (!isCollapsed) {
                        // Schedule long press: if still held after 1000ms without moving, stop MCP
                        val lp = Runnable {
                            if (!moved) {
                                AppLog.i("Floating long press: stopping MCP service")
                                stop(this)
                            }
                        }
                        longPressRunnable = lp
                        mainHandler.postDelayed(lp, longPressTimeout)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    val distSq = deltaX * deltaX + deltaY * deltaY
                    if (!moved && distSq > dragThresholdSq) {
                        moved = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                    if (moved) {
                        params.x = startX + deltaX.toInt()
                        params.y = startY + deltaY.toInt()
                        windowManager?.updateViewLayout(tv, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelScheduled()
                    tv.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator()).setDuration(260).start()
                    val elapsed = System.currentTimeMillis() - downTime
                    if (isCollapsed) {
                        // 折叠态：点击展开恢复
                        if (!moved) expandFromBubble()
                    } else if (moved) {
                        // 拖拽释放 — 立即贴边
                        val width = resources.displayMetrics.widthPixels
                        params.x = if (params.x > width / 2) width - tv.width else 0
                        windowManager?.updateViewLayout(tv, params)
                        scheduleAutoSnap()
                    } else if (elapsed < longPressTimeout) {
                        // 点击 (<500ms)：打开 MainActivity
                        launchMainActivity()
                        scheduleAutoSnap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelScheduled()
                    tv.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator()).setDuration(180).start()
                    true
                }
                else -> false
            }
        }
        floating = tv
        windowManager?.addView(tv, params)
        startPulse(tv)
        scheduleAutoSnap()
        AppLog.i("Floating window shown")
    }

    private fun cancelScheduled() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
        autoSnapRunnable?.let { mainHandler.removeCallbacks(it) }
        autoSnapRunnable = null
    }

    private fun scheduleAutoSnap() {
        autoSnapRunnable?.let { mainHandler.removeCallbacks(it) }
        val tv = bubbleText ?: return
        val params = floatingParams ?: return
        val snap = Runnable {
            if (floating == null) return@Runnable
            val width = resources.displayMetrics.widthPixels
            val centerX = params.x + tv.width / 2
            val targetX = if (centerX > width / 2) width - tv.width else 0
            val fromX = params.x
            ValueAnimator.ofInt(fromX, targetX).apply {
                duration = 280
                addUpdateListener {
                    params.x = it.animatedValue as Int
                    runCatching { windowManager?.updateViewLayout(tv, params) }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        collapseToBubble()
                    }
                })
                start()
            }
        }
        autoSnapRunnable = snap
        mainHandler.postDelayed(snap, AUTO_SNAP_DELAY)
    }

    private fun updateFloatingStatus() {
        val tv = bubbleText ?: return
        val settings = SettingsStore(this)
        val zh = settings.language == "zh" || (settings.language == "system" && Locale.getDefault().language == "zh")
        val isRunning = running
        val statusText = if (isRunning) (if (zh) "● 塔菲逆核运行中" else "● Taffy running") else (if (zh) "● 服务未启动" else "● Service off")
        val dotColor = if (isRunning) Color.argb(255, 52, 199, 89) else Color.argb(255, 255, 149, 0)

        if (isCollapsed) {
            // 折叠态：只更新悬浮球颜色（状态点）
            (tv.background as? GradientDrawable)?.setColor(dotColor)
        } else {
            tv.text = statusText
        }
    }

    private fun collapseToBubble() {
        if (isCollapsed || floating == null || bubbleText == null || floatingParams == null) return
        isCollapsed = true

        val tv = bubbleText!!
        val params = floatingParams!!
        val density = resources.displayMetrics.density
        val targetSize = (BUBBLE_SIZE_DP * density).toInt()
        val settings = SettingsStore(this)
        val isRunning = running
        val dotColor = if (isRunning) Color.argb(255, 52, 199, 89) else Color.argb(255, 255, 149, 0)

        // 停止脉冲动画
        pulseAnimator?.cancel()
        pulseAnimator = null

        val fromWidth = tv.width.coerceAtLeast(1)
        val fromHeight = tv.height.coerceAtLeast(1)
        val fromAlpha = tv.alpha

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                val curW = (fromWidth + (targetSize - fromWidth) * p).toInt().coerceAtLeast(targetSize)
                val curH = (fromHeight + (targetSize - fromHeight) * p).toInt().coerceAtLeast(targetSize)
                val curAlpha = fromAlpha + (0.45f - fromAlpha) * p

                params.width = curW
                params.height = curH
                tv.alpha = curAlpha

                // 后半段渐隐文字
                if (p > 0.4f) tv.text = ""

                // 后半段从深色背景渐变到状态点颜色
                val blend = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
                val targetR = if (isRunning) 52 else 255
                val targetG = if (isRunning) 199 else 149
                val targetB = if (isRunning) 89 else 0
                val bgR = (24 + (targetR - 24) * blend).toInt()
                val bgG = (30 + (targetG - 30) * blend).toInt()
                val bgB = (42 + (targetB - 42) * blend).toInt()
                val bgA = (140 + (255 - 140) * blend).toInt()
                (tv.background as? GradientDrawable)?.apply {
                    setColor(Color.argb(bgA, bgR, bgG, bgB))
                    cornerRadius = 999f
                    if (p > 0.5f) setStroke(0, Color.TRANSPARENT)
                }
                tv.setPadding(0, 0, 0, 0)
                runCatching { windowManager?.updateViewLayout(tv, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    params.width = targetSize
                    params.height = targetSize
                    tv.text = ""
                    tv.alpha = 0.45f
                    tv.setPadding(0, 0, 0, 0)
                    (tv.background as? GradientDrawable)?.apply {
                        setColor(dotColor)
                        cornerRadius = targetSize / 2f
                        setStroke(0, Color.TRANSPARENT)
                    }
                    runCatching { windowManager?.updateViewLayout(tv, params) }
                    AppLog.i("Floating collapsed to bubble")
                }
            })
            start()
        }
    }

    private fun expandFromBubble() {
        if (!isCollapsed || floating == null || bubbleText == null || floatingParams == null) return
        isCollapsed = false

        val tv = bubbleText!!
        val params = floatingParams!!
        val density = resources.displayMetrics.density
        val settings = SettingsStore(this)
        val zh = settings.language == "zh" || (settings.language == "system" && Locale.getDefault().language == "zh")
        val isRunning = running
        val statusText = if (isRunning) (if (zh) "● 塔菲逆核运行中" else "● Taffy running") else (if (zh) "● 服务未启动" else "● Service off")

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                tv.alpha = 0.45f + (0.85f - 0.45f) * p

                if (p > 0.5f) {
                    val rp = (p - 0.5f) / 0.5f
                    tv.text = statusText
                    val padH = (14 * density * rp).toInt()
                    val padV = (9 * density * rp).toInt()
                    tv.setPadding(padH, padV, padH, padV)
                }

                (tv.background as? GradientDrawable)?.apply {
                    setColor(Color.argb(238, 24, 30, 42))
                    setStroke((1 * density).toInt(), Color.argb(110, 255, 255, 255))
                    cornerRadius = 999f
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    params.width = WindowManager.LayoutParams.WRAP_CONTENT
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT
                    tv.text = statusText
                    tv.alpha = 0.85f
                    val padH = (14 * density).toInt()
                    val padV = (9 * density).toInt()
                    tv.setPadding(padH, padV, padH, padV)
                    (tv.background as? GradientDrawable)?.apply {
                        setColor(Color.argb(238, 24, 30, 42))
                        setStroke((1 * density).toInt(), Color.argb(110, 255, 255, 255))
                        cornerRadius = 999f
                    }
                    runCatching { windowManager?.updateViewLayout(tv, params) }
                    startPulse(tv)
                    scheduleAutoSnap()
                    AppLog.i("Floating expanded from bubble")
                }
            })
            start()
        }
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun removeFloating() {
        cancelScheduled()
        pulseAnimator?.cancel()
        pulseAnimator = null
        floating?.let { runCatching { windowManager?.removeView(it) } }
        floating = null
        bubbleText = null
        floatingParams = null
        isCollapsed = false
    }

    private fun startPulse(view: View) {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.035f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val v = it.animatedValue as Float
                view.scaleX = v
                view.scaleY = v
            }
            start()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "塔菲逆核", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotificationText(host: String, port: Int): String {
        // The full MCP endpoint always requires the /mcp path. Show a concrete,
        // usable URL rather than the bind host so users don't type 0.0.0.0.
        return if (host == "0.0.0.0") {
            val lan = runCatching { NetworkInspector.primaryLanIpv4(applicationContext) }.getOrNull()
            if (lan != null) {
                "MCP 运行中 · 客户端填 http://$lan:$port/mcp（局域网）"
            } else {
                "MCP 运行中 · 客户端填 http://<本机IP>:$port/mcp（勿填 0.0.0.0）"
            }
        } else {
            "MCP 运行中 · 客户端填 http://127.0.0.1:$port/mcp（仅本机/ADB）"
        }
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("塔菲逆核")
            .setContentText(text)
            .setSmallIcon(com.soreverse.mcp.R.drawable.ic_stat_somcp)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.soreverse.mcp.START"
        const val ACTION_STOP = "com.soreverse.mcp.STOP"
        const val ACTION_REFRESH_FLOATING = "com.soreverse.mcp.FLOATING"
        private const val CHANNEL_ID = "so_reverse_mcp"
        private const val AUTO_SNAP_DELAY = 3000L
        private const val BUBBLE_SIZE_DP = 36
        @Volatile private var running: Boolean = false
        @Volatile var currentServer: McpHttpServer? = null
            private set

        fun isRunning(): Boolean = running

        fun start(context: Context) {
            if (!IntegrityGuard.isTrusted(context.applicationContext)) {
                AppLog.e("MCP service start rejected before dispatch by integrity guard")
                return
            }
            val intent = Intent(context, McpForegroundService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            running = false
            context.startService(Intent(context, McpForegroundService::class.java).setAction(ACTION_STOP))
        }

        fun refreshFloating(context: Context) {
            context.startService(Intent(context, McpForegroundService::class.java).setAction(ACTION_REFRESH_FLOATING))
        }
    }
}

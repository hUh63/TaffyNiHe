package com.soreverse.mcp.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.soreverse.mcp.MainActivity
import com.soreverse.mcp.R
import java.util.ArrayList

/**
 * Bore 隧道前台服务 - 移植自 MCP-Bridge-Enhanced TunnelService
 */
class BoreTunnelService : Service() {

    companion object {
        const val ACTION_TUNNEL_STATUS = "com.soreverse.mcp.BORE_TUNNEL_STATUS"
        const val ACTION_TUNNEL_EVENT = "com.soreverse.mcp.BORE_TUNNEL_EVENT"
        const val ACTION_STOP = "com.soreverse.mcp.boretunnel.STOP"
        const val EXTRA_BORE_HOST = "bore_host"
        const val EXTRA_BORE_PORT = "bore_port"
        const val EXTRA_LOCAL_PORT = "local_port"
        const val EXTRA_BORE_SECRET = "bore_secret"
        const val NOTIFICATION_ID = 3001

        private const val PREF_TUNNEL_URL = "bore_tunnel_url"
        private const val PREF_TUNNEL_RUNNING = "bore_tunnel_running"
        private const val PREF_TUNNEL_CONNECTING = "bore_tunnel_connecting"

        private var lastTunnelUrl: String? = null
        private val eventLog = mutableListOf<String>()
        private const val MAX_EVENTS = 100

        // 统计数据
        private var runningSinceMs = 0L
        private var accumulatedRunningMs = 0L
        private val totalReconnects = java.util.concurrent.atomic.AtomicInteger(0)
        private val totalConnections = java.util.concurrent.atomic.AtomicInteger(0)
        private val totalBytesTransferred = java.util.concurrent.atomic.AtomicLong(0)
        private val totalErrors = java.util.concurrent.atomic.AtomicInteger(0)

        fun getEventLog(): List<String> = synchronized(eventLog) { ArrayList(eventLog) }
        fun clearEventLog() { synchronized(eventLog) { eventLog.clear() } }

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences("bore_tunnel", MODE_PRIVATE)
                .getBoolean(PREF_TUNNEL_RUNNING, false)

        fun isConnecting(context: Context): Boolean =
            context.getSharedPreferences("bore_tunnel", MODE_PRIVATE)
                .getBoolean(PREF_TUNNEL_CONNECTING, false)

        fun getTunnelUrl(context: Context): String? =
            context.getSharedPreferences("bore_tunnel", MODE_PRIVATE)
                .getString(PREF_TUNNEL_URL, null)

        fun tunnelStats(): org.json.JSONObject {
            val running = lastTunnelUrl != null
            val currentRunningMs = if (running && runningSinceMs > 0) System.currentTimeMillis() - runningSinceMs else 0L
            val totalRunningMs = accumulatedRunningMs + currentRunningMs
            return org.json.JSONObject().apply {
                put("type", "bore")
                put("state", if (running) "RUNNING" else "STOPPED")
                put("publicUrl", lastTunnelUrl ?: org.json.JSONObject.NULL)
                put("totalRunningMs", totalRunningMs)
                put("currentRunningMs", currentRunningMs)
                put("totalReconnects", totalReconnects.get())
                put("totalConnections", totalConnections.get())
                put("totalBytesTransferred", totalBytesTransferred.get())
                put("totalErrors", totalErrors.get())
            }
        }

        fun resetStats() {
            accumulatedRunningMs = 0L
            runningSinceMs = 0L
            totalReconnects.set(0)
            totalConnections.set(0)
            totalBytesTransferred.set(0L)
            totalErrors.set(0)
        }
    }

    private var boreClient: BoreClient? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel("bore_tunnel", "Bore 隧道", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        if (ACTION_STOP == intent.action) {
            requestStop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val boreHost = intent.getStringExtra(EXTRA_BORE_HOST) ?: "bore.pub"
        val borePort = intent.getIntExtra(EXTRA_BORE_PORT, 7835)
        val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 8080)
        val boreSecret = intent.getStringExtra(EXTRA_BORE_SECRET) ?: ""

        startForeground(NOTIFICATION_ID, buildNotification("Bore 隧道启动中..."))

        synchronized(eventLog) { eventLog.clear() }
        getSharedPreferences("bore_tunnel", MODE_PRIVATE).edit()
            .putBoolean(PREF_TUNNEL_CONNECTING, true)
            .apply()

        startTunnel(boreHost, borePort, localPort, boreSecret)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun requestStop() {
        boreClient?.requestStop()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun addEvent(event: String) {
        synchronized(eventLog) {
            eventLog.add(event)
            while (eventLog.size > MAX_EVENTS) eventLog.removeAt(0)
        }
        this@BoreTunnelService.sendBroadcast(
            Intent(ACTION_TUNNEL_EVENT).putExtra("event", event)
        )
    }

    private fun startTunnel(boreHost: String, borePort: Int, localPort: Int, boreSecret: String) {
        stopTunnel()
        boreClient = BoreClient(boreHost, borePort, localPort, boreSecret.ifBlank { null })
        boreClient!!.setListener(object : BoreClient.BoreListener {
            override fun onConnected(publicUrl: String) {
                lastTunnelUrl = publicUrl
                runningSinceMs = System.currentTimeMillis()
                getSharedPreferences("bore_tunnel", MODE_PRIVATE).edit()
                    .putString(PREF_TUNNEL_URL, publicUrl)
                    .putBoolean(PREF_TUNNEL_RUNNING, true)
                    .putBoolean(PREF_TUNNEL_CONNECTING, false)
                    .apply()
                updateNotification("Bore 已连接: $publicUrl")
                broadcastStatus(true, publicUrl)
            }

            override fun onDisconnected() {
                if (runningSinceMs > 0) {
                    accumulatedRunningMs += System.currentTimeMillis() - runningSinceMs
                    runningSinceMs = 0L
                }
                lastTunnelUrl = null
                getSharedPreferences("bore_tunnel", MODE_PRIVATE).edit()
                    .putBoolean(PREF_TUNNEL_RUNNING, false)
                    .putBoolean(PREF_TUNNEL_CONNECTING, false)
                    .apply()
                updateNotification("Bore 已断开")
                broadcastStatus(false, null)
            }

            override fun onError(message: String) {
                totalErrors.incrementAndGet()
                addEvent("[错误] $message")
                updateNotification("Bore 错误: $message")
                broadcastStatus(false, null)
            }

            override fun onBytesTransferred(bytes: Long) {
                totalBytesTransferred.set(bytes)
            }

            override fun onConnectionEvent(event: String) {
                if (event.contains("重连") || event.contains("reconnect")) {
                    totalReconnects.incrementAndGet()
                }
                if (event.contains("数据连接") || event.contains("Connection")) {
                    totalConnections.incrementAndGet()
                }
                addEvent(event)
            }
        })
        boreClient!!.startTunnel()
    }

    private fun stopTunnel() {
        boreClient?.stop()
        boreClient = null
        getSharedPreferences("bore_tunnel", MODE_PRIVATE).edit()
            .putBoolean(PREF_TUNNEL_RUNNING, false)
            .putBoolean(PREF_TUNNEL_CONNECTING, false)
            .apply()
        lastTunnelUrl = null
        broadcastStatus(false, null)
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "bore_tunnel")
            .setContentTitle("Bore 隧道")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun broadcastStatus(connected: Boolean, url: String?) {
        this@BoreTunnelService.sendBroadcast(
            Intent(ACTION_TUNNEL_STATUS)
                .putExtra("connected", connected)
                .putExtra("url", url)
        )
    }
}
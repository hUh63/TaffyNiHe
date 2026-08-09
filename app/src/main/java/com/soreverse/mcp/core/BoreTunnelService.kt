package com.soreverse.mcp.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
        const val EXTRA_LOCAL_PORT = "local_port"
        const val NOTIFICATION_ID = 3001

        private const val PREF_TUNNEL_URL = "bore_tunnel_url"
        private const val PREF_TUNNEL_RUNNING = "bore_tunnel_running"

        private var lastTunnelUrl: String? = null
        private val eventLog = mutableListOf<String>()
        private const val MAX_EVENTS = 100

        fun getEventLog(): List<String> = synchronized(eventLog) { ArrayList(eventLog) }

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences("bore_tunnel", MODE_PRIVATE)
                .getBoolean(PREF_TUNNEL_RUNNING, false)

        fun getTunnelUrl(context: Context): String? =
            context.getSharedPreferences("bore_tunnel", MODE_PRIVATE)
                .getString(PREF_TUNNEL_URL, null)
    }

    private var boreClient: BoreClient? = null

    override fun onCreate() { super.onCreate() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }

        if (ACTION_STOP == intent.action) {
            requestStop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val boreHost = intent.getStringExtra(EXTRA_BORE_HOST) ?: "bore.pub"
        val localPort = intent.getIntExtra(EXTRA_LOCAL_PORT, 8080)

        startForeground(NOTIFICATION_ID, buildNotification("Bore 隧道启动中..."))

        synchronized(eventLog) { eventLog.clear() }

        startTunnel(boreHost, localPort)
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

    private fun startTunnel(boreHost: String, localPort: Int) {
        stopTunnel()
        boreClient = BoreClient(boreHost, localPort = localPort)
        boreClient!!.setListener(object : BoreClient.BoreListener {
            override fun onConnected(publicUrl: String) {
                lastTunnelUrl = publicUrl
                getSharedPreferences("bore_tunnel", MODE_PRIVATE).edit()
                    .putString(PREF_TUNNEL_URL, publicUrl)
                    .putBoolean(PREF_TUNNEL_RUNNING, true)
                    .apply()
                updateNotification("Bore 已连接: $publicUrl")
                broadcastStatus(true, publicUrl)
            }

            override fun onDisconnected() {
                lastTunnelUrl = null
                getSharedPreferences("bore_tunnel", MODE_PRIVATE).edit()
                    .putBoolean(PREF_TUNNEL_RUNNING, false)
                    .apply()
                updateNotification("Bore 已断开")
                broadcastStatus(false, null)
            }

            override fun onError(message: String) {
                addEvent("[错误] $message")
                updateNotification("Bore 错误: $message")
                broadcastStatus(false, null)
            }

            override fun onBytesTransferred(bytes: Long) {}

            override fun onConnectionEvent(event: String) {
                addEvent(event)
            }
        })
        boreClient!!.start()
    }

    private fun stopTunnel() {
        boreClient?.stop()
        boreClient = null
        getSharedPreferences("bore_tunnel", MODE_PRIVATE).edit()
            .putBoolean(PREF_TUNNEL_RUNNING, false)
            .apply()
        lastTunnelUrl = null
        broadcastStatus(false, null)
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "tunnel_channel")
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
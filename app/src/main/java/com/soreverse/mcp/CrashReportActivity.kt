package com.soreverse.mcp

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.soreverse.mcp.core.CrashReporter

class CrashReportActivity : ComponentActivity() {
    private lateinit var token: String
    private lateinit var report: String
    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(CrashReporter.EXTRA_TOKEN).orEmpty()
        report = CrashReporter.readReport(this, token).orEmpty()
        if (report.isBlank()) {
            report = "无法读取崩溃报告。\nProcess: ${CrashReporter.currentProcessName()}"
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showExitConfirmation()
                }
            },
        )
        setContentView(buildContent())
        CrashReporter.markReady(this, token)
    }

    private fun buildContent(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.rgb(242, 242, 247))
            addView(
                TextView(context).apply {
                    text = "塔菲逆核发生崩溃"
                    textSize = 22f
                    setTextColor(Color.rgb(28, 28, 30))
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                TextView(context).apply {
                    text = "完整崩溃信息已保留。你可以复制全部内容或长按选择文本；确认退出前应用不会继续终止。"
                    textSize = 14f
                    setTextColor(Color.rgb(99, 99, 102))
                    setPadding(0, dp(8), 0, dp(12))
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(
                        Button(context).apply {
                            text = "复制全部"
                            isAllCaps = false
                            setOnClickListener { copyAll() }
                        },
                    )
                    addView(
                        Button(context).apply {
                            text = "确认退出"
                            isAllCaps = false
                            setOnClickListener { showExitConfirmation() }
                        },
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                android.widget.CheckBox(context).apply {
                    text = "复制时包含匿名设备标识（随机 UUID，不含任何硬件信息）"
                    textSize = 13f
                    isChecked = CrashReporter.deviceIdEnabled(context)
                    setPadding(0, dp(4), 0, dp(4))
                    setOnCheckedChangeListener { _, checked ->
                        CrashReporter.setDeviceIdEnabled(context, checked)
                        if (::reportView.isInitialized) reportView.text = composedReport()
                    }
                },
            )
            addView(
                ScrollView(context).apply {
                    isFillViewport = true
                    setBackgroundColor(Color.WHITE)
                    addView(
                        TextView(context).apply {
                            reportView = this
                            text = composedReport()
                            textSize = 12f
                            typeface = Typeface.MONOSPACE
                            setTextColor(Color.rgb(28, 28, 30))
                            setTextIsSelectable(true)
                            movementMethod = ScrollingMovementMethod.getInstance()
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                        },
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply { topMargin = dp(12) },
            )
        }
    }

    private fun copyAll() {
        runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Taffy crash report", composedReport()))
        }.onSuccess {
            Toast.makeText(this, "已复制完整崩溃信息", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "系统剪贴板拒绝了复制，请长按选择文本", Toast.LENGTH_LONG).show()
        }
    }

    /** 报告正文 + 隐私闸门开关：渠道恒定附带；设备标识仅在用户勾选时附带(随机 UUID)。 */
    private fun composedReport(): String {
        val sb = StringBuilder(report)
        sb.append("\n\n---\n")
        sb.append("Channel: ").append(CrashReporter.channel(this)).append('\n')
        if (CrashReporter.deviceIdEnabled(this)) {
            sb.append("Device ID: ").append(CrashReporter.anonymousDeviceId(this)).append(" (anonymous, consent-gated)\n")
        } else {
            sb.append("Device ID: (未包含 · 隐私闸门默认关闭)\n")
        }
        return sb.toString()
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("退出塔菲逆核？")
            .setMessage("退出后主进程将继续执行系统默认崩溃流程。请先确认已经复制所需信息。")
            .setNegativeButton("继续查看", null)
            .setPositiveButton("退出应用") { _, _ -> confirmExit() }
            .show()
    }

    private fun confirmExit() {
        CrashReporter.confirmExit(this, token)
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }
}

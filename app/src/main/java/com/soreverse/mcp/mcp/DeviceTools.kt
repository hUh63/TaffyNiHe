package com.soreverse.mcp.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * 塔菲逆核: 设备信息 / 系统管理 / 应用管理 / 通讯交互 / 网络 / 实用工具。
 *
 * 参考开源 MCP Server 实现，适配塔菲逆核的 ToolHandler 架构。
 * 补齐逆向工作流中需要的通用设备操作能力。
 */
object DeviceTools {

    // ══════════════════════════════════════════════════════════════
    //  系统信息
    // ══════════════════════════════════════════════════════════════

    val deviceInfo = object : ToolHandler {
        override val meta = ToolMeta("taffy_device_info",
            "获取手机硬件与系统信息（品牌、型号、Android 版本、指纹等）",
            "Get device hardware and system info (brand, model, Android version, fingerprint, etc.)",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject = ok(JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
            put("fingerprint", Build.FINGERPRINT)
            put("buildId", Build.ID)
            put("buildTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(Build.TIME)))
            put("serial", try { Build.getSerial() } catch (_: Exception) { "unknown" })
            put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            put("bootloader", Build.BOOTLOADER)
            put("radioVersion", Build.getRadioVersion() ?: "unknown")
            put("host", Build.HOST)
            put("tags", Build.TAGS)
        })
    }

    val battery = object : ToolHandler {
        override val meta = ToolMeta("taffy_battery",
            "获取电池状态（电量、充电状态、温度、电压、健康度等）",
            "Get battery status (level, charging state, temperature, voltage, health, etc.)",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val intent = ctx.context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
            return ok(JSONObject().apply {
                put("level", level)
                put("scale", scale)
                put("percent", if (scale > 0) level * 100 / scale else -1)
                put("status", when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                })
                put("plugged", when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    else -> "none"
                })
                put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                put("temperatureCelsius", if (temp > 0) temp / 10.0 else -1.0)
                put("voltageMillivolts", voltage)
                put("health", when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    else -> "unknown"
                })
            })
        }
    }

    val storageInfo = object : ToolHandler {
        override val meta = ToolMeta("taffy_storage_info",
            "获取内部/外部存储空间使用情况（字节）",
            "Get internal/external storage space usage in bytes",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject = ok(JSONObject().apply {
            fun stat(path: File): JSONObject? = try {
                val sf = StatFs(path.absolutePath)
                JSONObject().apply {
                    put("path", path.absolutePath)
                    put("total", sf.totalBytes)
                    put("available", sf.availableBytes)
                    put("free", sf.freeBytes)
                    put("used", sf.totalBytes - sf.availableBytes)
                }
            } catch (_: Exception) { null }
            put("internal", stat(Environment.getDataDirectory()))
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null) put("external", stat(ext))
        })
    }

    val screenInfo = object : ToolHandler {
        override val meta = ToolMeta("taffy_screen_info",
            "获取屏幕信息（分辨率、密度、刷新率、亮度等）",
            "Get screen info (resolution, density, refresh rate, brightness, etc.)",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val wm = ctx.context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val refresh = try { wm.defaultDisplay.mode.refreshRate } catch (_: Exception) { -1.0 }
            val brightness = try {
                Settings.System.getInt(ctx.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            } catch (_: Exception) { -1 }
            return ok(JSONObject().apply {
                put("widthPixels", metrics.widthPixels)
                put("heightPixels", metrics.heightPixels)
                put("densityDpi", metrics.densityDpi)
                put("density", metrics.density)
                put("scaledDensity", metrics.scaledDensity)
                put("xdpi", metrics.xdpi)
                put("ydpi", metrics.ydpi)
                put("refreshRate", refresh)
                put("brightness", brightness)
            })
        }
    }

    val localeInfo = object : ToolHandler {
        override val meta = ToolMeta("taffy_locale_info",
            "获取系统语言与时区信息",
            "Get system language and timezone info",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val loc = Locale.getDefault()
            return ok(JSONObject().apply {
                put("language", loc.language)
                put("country", loc.country)
                put("displayLanguage", loc.displayLanguage)
                put("displayCountry", loc.displayCountry)
                put("timezone", java.util.TimeZone.getDefault().id)
                put("timezoneOffsetMinutes", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)
            })
        }
    }

    val systemProperties = object : ToolHandler {
        override val meta = ToolMeta("taffy_system_properties",
            "读取系统属性（build 相关等），可按 filter 过滤",
            "Read system properties (build-related), optional filter",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "filter" str "属性名过滤关键词"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val filter = args.str("filter")
            val keys = listOf(
                "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.security_patch",
                "ro.product.model", "ro.product.manufacturer", "ro.product.brand",
                "ro.product.device", "ro.hardware", "ro.bootloader", "ro.build.fingerprint",
                "ro.build.type", "ro.build.tags", "ro.build.id", "ro.build.date",
                "ro.serialno", "ro.product.cpu.abi", "ro.secure", "ro.debuggable",
                "persist.sys.timezone", "ro.config.ringtone", "gsm.version.baseband",
            )
            return ok(JSONObject().apply {
                for (k in keys) {
                    if (filter.isNotBlank() && !k.contains(filter)) continue
                    val v = readSystemProperty(k)
                    if (!v.isNullOrEmpty()) put(k, v)
                }
            })
        }

        private fun readSystemProperty(key: String): String? = try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════
    //  应用管理
    // ══════════════════════════════════════════════════════════════

    val installedApps = object : ToolHandler {
        override val meta = ToolMeta("taffy_installed_apps",
            "列出已安装应用（包名、名称、版本、安装时间等）",
            "List installed apps (package name, label, version, install time, etc.)",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "includeSystem" bool "是否包含系统应用（默认 false）"
                "limit" int "最多返回条数（默认 200）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val includeSystem = args.bool("includeSystem", false)
            val limit = args.intValue("limit", 200).coerceIn(1, 5000)
            val pm = ctx.context.packageManager
            val apps = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getInstalledApplications(0)
            val arr = JSONArray()
            var count = 0
            for (ai in apps) {
                if (count >= limit) break
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (isSystem && !includeSystem) continue
                try {
                    val v = pm.getPackageInfo(ai.packageName, 0)
                    arr.put(JSONObject().apply {
                        put("packageName", ai.packageName)
                        put("appName", pm.getApplicationLabel(ai).toString())
                        put("versionName", v.versionName ?: "")
                        put("versionCode", if (Build.VERSION.SDK_INT >= 28) v.longVersionCode else v.versionCode.toLong())
                        put("system", isSystem)
                        put("enabled", ai.enabled)
                        put("firstInstallTime", v.firstInstallTime)
                        put("lastUpdateTime", v.lastUpdateTime)
                        put("sourceDir", ai.sourceDir)
                    })
                    count++
                } catch (e: Exception) { com.soreverse.mcp.core.AppLog.w("silent-catch", e) }
            }
            return ok(JSONObject().put("count", arr.length()).put("apps", arr))
        }
    }

    val appInfo = object : ToolHandler {
        override val meta = ToolMeta("taffy_app_info",
            "获取单个应用的详细信息（包名、版本、安装时间、UID 等）",
            "Get detailed info for a single app by package name",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "packageName" str "应用包名"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val pkg = args.str("packageName")
            if (pkg.isBlank()) return err("INVALID_ARGUMENT", "packageName 不能为空", "packageName", pkg)
            val pm = ctx.context.packageManager
            return try {
                val ai = if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
                val v = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
                ok(JSONObject().apply {
                    put("packageName", pkg)
                    put("appName", pm.getApplicationLabel(ai).toString())
                    put("versionName", v.versionName ?: "")
                    put("versionCode", if (Build.VERSION.SDK_INT >= 28) v.longVersionCode else v.versionCode.toLong())
                    put("system", (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    put("enabled", ai.enabled)
                    put("firstInstallTime", v.firstInstallTime)
                    put("lastUpdateTime", v.lastUpdateTime)
                    put("uid", ai.uid)
                    put("dataDir", ai.dataDir)
                    put("sourceDir", ai.sourceDir)
                    put("targetSdk", ai.targetSdkVersion)
                    put("minSdk", try { ai.minSdkVersion } catch (_: Exception) { -1 })
                })
            } catch (e: Exception) {
                err("APP_NOT_FOUND", "未找到应用 $pkg", "packageName", pkg, "hint" to "使用 taffy_installed_apps 查看已安装应用列表")
            }
        }
    }

    val runningProcesses = object : ToolHandler {
        override val meta = ToolMeta("taffy_running_processes",
            "获取当前正在运行的进程信息（可能受限）",
            "Get currently running process info (may be restricted)",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val am = ctx.context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val arr = JSONArray()
            try {
                val procs = am.runningAppProcesses ?: emptyList()
                for (p in procs) {
                    arr.put(JSONObject().apply {
                        put("pid", p.pid)
                        put("processName", p.processName)
                        put("importance", p.importance)
                    })
                }
            } catch (e: Exception) { com.soreverse.mcp.core.AppLog.w("silent-catch", e) }
            return ok(JSONObject().put("count", arr.length()).put("processes", arr))
        }
    }

    val stopApp = object : ToolHandler {
        override val meta = ToolMeta("taffy_stop_app",
            "强制停止指定应用（需 Root/Shizuku 高级权限执行 am force-stop，否则回退到 killBackgroundProcesses）",
            "Force stop an app (requires Root/Shizuku for am force-stop, falls back to killBackgroundProcesses)",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "packageName" str "应用包名"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val pkg = args.str("packageName")
            if (pkg.isBlank()) return err("INVALID_ARGUMENT", "packageName 不能为空", "packageName", pkg)
            return try {
                val am = ctx.context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
                ok(JSONObject()
                    .put("packageName", pkg)
                    .put("mode", "app")
                    .put("note", "已尝试 killBackgroundProcesses（需系统权限才能真正强制停止）"))
            } catch (e: Exception) {
                err("IO_ERROR", "操作失败: ${e.message}", "packageName", pkg)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  通讯交互
    // ══════════════════════════════════════════════════════════════

    val clipboard = object : ToolHandler {
        override val meta = ToolMeta("taffy_clipboard",
            "读取或写入设备系统剪贴板。operation=read（默认）读取; operation=write 写入（需 text）",
            "Read or write the system clipboard. operation=read (default) or write (requires text)",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "operation".oneOf("操作类型", "read", "write")
                "text" str "写入剪贴板的文本（operation=write 时必填）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val operation = args.str("operation", "read").lowercase()
            val cm = ctx.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return when (operation) {
                "read" -> {
                    val clip = cm.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx.context).toString() else ""
                    ok(JSONObject()
                        .put("operation", "read")
                        .put("text", text)
                        .put("hasText", text.isNotEmpty()))
                }
                "write" -> {
                    val text = args.str("text")
                    if (text.isEmpty()) return err("INVALID_ARGUMENT", "写入剪贴板时 text 不能为空", "text", text)
                    cm.setPrimaryClip(ClipData.newPlainText("mcp", text))
                    ok(JSONObject().put("operation", "write").put("textLength", text.length))
                }
                else -> err("INVALID_ARGUMENT", "未知 operation: $operation", "operation", operation, "allowedValues" to "read, write")
            }
        }
    }

    val sendNotification = object : ToolHandler {
        override val meta = ToolMeta("taffy_send_notification",
            "在设备上发送一条系统通知。content 必填; priority 可选 low/normal/high/max",
            "Send a system notification on the device. content is required; priority: low/normal/high/max",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "content" str "通知内容（必填）"
                "title" str "通知标题（默认 MCP 通知）"
                "priority".oneOf("通知优先级", "low", "normal", "high", "max")
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val content = args.str("content")
            if (content.isBlank()) return err("INVALID_ARGUMENT", "content 不能为空", "content", content)
            val title = args.str("title", "MCP 通知")
            val priority = args.str("priority", "normal").lowercase()
            if (priority !in setOf("low", "normal", "high", "max"))
                return err("INVALID_ARGUMENT", "未知 priority: $priority", "priority", priority, "allowedValues" to "low, normal, high, max")

            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return err("PERMISSION_DENIED", "缺少通知权限 POST_NOTIFICATIONS", "permission", "POST_NOTIFICATIONS", "hint" to "请在系统设置中授权通知权限")
            }
            val channelId = "mcp_notifications"
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = android.app.NotificationChannel(channelId, "MCP 通知",
                    when (priority) {
                        "low" -> android.app.NotificationManager.IMPORTANCE_LOW
                        "high" -> android.app.NotificationManager.IMPORTANCE_HIGH
                        "max" -> android.app.NotificationManager.IMPORTANCE_MAX
                        else -> android.app.NotificationManager.IMPORTANCE_DEFAULT
                    })
                val nm = ctx.context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.createNotificationChannel(channel)
            }
            val builder = androidx.core.app.NotificationCompat.Builder(ctx.context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
            val nid = (System.currentTimeMillis() % 10000).toInt()
            androidx.core.app.NotificationManagerCompat.from(ctx.context).notify(nid, builder.build())
            return ok(JSONObject()
                .put("title", title)
                .put("content", content)
                .put("priority", priority)
                .put("notificationId", nid))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  权限
    // ══════════════════════════════════════════════════════════════

    val checkPermission = object : ToolHandler {
        override val meta = ToolMeta("taffy_check_permission",
            "检查应用是否已获得指定权限（传入权限名，如 READ_SMS）",
            "Check if the app has a given permission (e.g. READ_SMS)",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "permission" str "权限名（可带 android.permission. 前缀）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val name = args.str("permission")
            if (name.isBlank()) return err("INVALID_ARGUMENT", "permission 不能为空", "permission", name)
            val perm = if (name.startsWith("android.permission.")) name else "android.permission.$name"
            val granted = ctx.context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            return ok(JSONObject().put("permission", name).put("granted", granted))
        }
    }

    val permissionState = object : ToolHandler {
        override val meta = ToolMeta("taffy_permission_state",
            "汇总检查关键权限的授予状态（所有文件访问、悬浮窗、通知等）",
            "Summarize key permission states (all files access, overlay, notification, etc.)",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject = ok(JSONObject().apply {
            put("allFilesAccess", if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
                else ctx.context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
            put("overlayPermission", Settings.canDrawOverlays(ctx.context))
            put("notificationPermission", try {
                ctx.context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false })
            for (p in listOf("READ_PHONE_STATE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE")) {
                put(p, try {
                    ctx.context.checkSelfPermission("android.permission.$p") == PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) { false })
            }
        })
    }

    // ══════════════════════════════════════════════════════════════
    //  网络工具
    // ══════════════════════════════════════════════════════════════

    val httpRequest = object : ToolHandler {
        override val meta = ToolMeta("taffy_http_request",
            "发起 HTTP(S) 请求。method 支持 GET/POST/PUT/PATCH/DELETE/HEAD; 支持 body/bodyJson/form/headers",
            "Make an HTTP(S) request. Methods: GET/POST/PUT/PATCH/DELETE/HEAD; supports body/bodyJson/form/headers",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "url" str "请求地址（http/https）"
                "method".oneOf("HTTP 方法", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
                "headers" str "请求头 JSON 字符串"
                "body" str "字符串请求体"
                "bodyJson" str "JSON 请求体（自动设置 Content-Type: application/json）"
                "timeoutMs" int "超时毫秒（默认 15000）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val urlStr = args.str("url")
            if (urlStr.isBlank()) return err("INVALID_ARGUMENT", "url 不能为空", "url", urlStr)
            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))
                return err("INVALID_ARGUMENT", "仅支持 http/https 协议", "url", urlStr)
            val method = args.str("method", "GET").uppercase()
            val timeout = args.intValue("timeoutMs", 15000).coerceIn(1000, 120000)
            return try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = maxOf(timeout / 2, 10000)
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "TaffyNiHe-MCP/1.0 (${Build.MODEL})")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                val headersStr = args.str("headers")
                if (headersStr.isNotBlank()) {
                    val headers = JSONObject(headersStr)
                    for (k in headers.keys()) conn.setRequestProperty(k, headers.getString(k))
                }
                val bodyJsonStr = args.str("bodyJson")
                val bodyStr = args.str("body")
                var bodyBytes: ByteArray? = null
                var contentType: String? = null
                when {
                    bodyJsonStr.isNotBlank() -> { bodyBytes = bodyJsonStr.toByteArray(Charsets.UTF_8); contentType = "application/json; charset=utf-8" }
                    bodyStr.isNotBlank() -> { bodyBytes = bodyStr.toByteArray(Charsets.UTF_8); contentType = "text/plain; charset=utf-8" }
                }
                if (bodyBytes != null && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", contentType)
                    DataOutputStream(conn.outputStream).use { it.write(bodyBytes); it.flush() }
                }
                val code = conn.responseCode
                val input = if (code >= 400) conn.errorStream else conn.inputStream
                val maxBytes = 1_048_576
                val bytes = if (input != null) {
                    val bos = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    val bi = java.io.BufferedInputStream(input)
                    val stream = if ("gzip" == conn.getHeaderField("Content-Encoding")) GZIPInputStream(bi) else bi
                    stream.use { s ->
                        while (true) { val n = s.read(buf); if (n < 0) break; total += n; if (total > maxBytes) break; bos.write(buf, 0, n) }
                    }
                    bos.toByteArray()
                } else ByteArray(0)
                conn.disconnect()
                ok(JSONObject()
                    .put("status", code)
                    .put("statusText", conn.responseMessage ?: "")
                    .put("url", conn.url.toString())
                    .put("bytes", bytes.size)
                    .put("body", String(bytes, Charsets.UTF_8)))
            } catch (e: Exception) {
                err("NETWORK_ERROR", "请求失败: ${e.message}", "url", urlStr)
            }
        }
    }

    val shortenUrl = object : ToolHandler {
        override val meta = ToolMeta("taffy_shorten_url",
            "使用 tinyurl 服务生成短链接",
            "Shorten a URL using the tinyurl service",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "url" str "要缩短的链接"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val urlStr = args.str("url")
            if (urlStr.isBlank()) return err("INVALID_ARGUMENT", "url 不能为空", "url", urlStr)
            return try {
                val conn = URL("https://tinyurl.com/api-create.php?url=" + URLEncoder.encode(urlStr, "UTF-8")).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val text = conn.inputStream.bufferedReader().use { it.readText().trim() }
                conn.disconnect()
                if (text.startsWith("http")) ok(JSONObject().put("original", urlStr).put("short", text))
                else err("NETWORK_ERROR", "短链接服务返回异常: $text", "url", urlStr)
            } catch (e: Exception) {
                err("NETWORK_ERROR", "请求失败: ${e.message}", "url", urlStr)
            }
        }
    }

    val webDownload = object : ToolHandler {
        override val meta = ToolMeta("taffy_web_download",
            "下载远程文件到本地存储。url 为下载地址; path 为目标文件路径",
            "Download a remote file to local storage. url is the download URL; path is the target file path",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "url" str "下载地址"
                "path" str "目标文件路径"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val urlStr = args.str("url")
            val path = args.str("path")
            if (urlStr.isBlank()) return err("INVALID_ARGUMENT", "url 不能为空", "url", urlStr)
            if (path.isBlank()) return err("INVALID_ARGUMENT", "path 不能为空", "path", path)
            return try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                val file = File(path)
                file.parentFile?.mkdirs()
                var total = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(8192)
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); total += n }
                    }
                }
                conn.disconnect()
                ok(JSONObject()
                    .put("url", urlStr)
                    .put("path", path)
                    .put("bytesDownloaded", total)
                    .put("fileSize", file.length()))
            } catch (e: Exception) {
                err("NETWORK_ERROR", "下载失败: ${e.message}", "url", urlStr)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  实用工具
    // ══════════════════════════════════════════════════════════════

    val timeNow = object : ToolHandler {
        override val meta = ToolMeta("taffy_time_now",
            "获取当前时间戳（秒/毫秒）与格式化时间",
            "Get current timestamp (seconds/millis) and formatted time",
            "device", ToolClass.EXTRA,
        ) { SchemaBuilder.emptyObject() }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val now = System.currentTimeMillis()
            return ok(JSONObject().apply {
                put("epochSeconds", now / 1000)
                put("epochMillis", now)
                put("iso8601", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(now)))
                put("local", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now)))
                put("timezone", java.util.TimeZone.getDefault().id)
            })
        }
    }

    val jsonFormat = object : ToolHandler {
        override val meta = ToolMeta("taffy_json_format",
            "格式化、压缩或验证 JSON 字符串。operation=pretty（默认）/ minify / validate",
            "Format, minify, or validate a JSON string. operation: pretty (default) / minify / validate",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "json" str "待处理的 JSON 字符串"
                "operation".oneOf("处理方式", "pretty", "minify", "validate")
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val input = args.str("json")
            if (input.isBlank()) return err("INVALID_ARGUMENT", "json 不能为空", "json", input)
            val op = args.str("operation", "pretty").lowercase()
            return try {
                val obj = if (input.trimStart().startsWith("[")) JSONArray(input) else JSONObject(input)
                when (op) {
                    "validate" -> ok(JSONObject().put("valid", true).put("type", if (obj is JSONObject) "object" else "array"))
                    "minify" -> ok(JSONObject().put("result", obj.toString()))
                    else -> ok(JSONObject().put("result", if (obj is JSONObject) obj.toString(2) else (obj as JSONArray).toString(2)))
                }
            } catch (e: Exception) {
                ok(JSONObject().put("valid", false).put("error", "JSON 无效: ${e.message}"))
            }
        }
    }

    val textConvert = object : ToolHandler {
        override val meta = ToolMeta("taffy_text_convert",
            "文本格式转换。operation: upper/lower/trim/trim_lines/remove_empty_lines/normalize_newlines/reverse/count",
            "Text conversion. operation: upper/lower/trim/trim_lines/remove_empty_lines/normalize_newlines/reverse/count",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "text" str "待处理的文本"
                "operation".oneOf("转换方式", "upper", "lower", "trim", "trim_lines", "remove_empty_lines", "normalize_newlines", "reverse", "count")
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val text = args.str("text")
            val op = args.str("operation", "trim").lowercase()
            if (op == "count") return ok(JSONObject()
                .put("chars", text.length)
                .put("words", text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size)
                .put("lines", text.lines().size))
            val result = when (op) {
                "upper" -> text.uppercase()
                "lower" -> text.lowercase()
                "trim" -> text.trim()
                "trim_lines" -> text.lines().joinToString("\n") { it.trim() }
                "remove_empty_lines" -> text.lines().filter { it.isNotBlank() }.joinToString("\n")
                "normalize_newlines" -> text.replace("\r\n", "\n").replace("\r", "\n")
                "reverse" -> text.reversed()
                else -> return err("INVALID_ARGUMENT", "未知 operation: $op", "operation", op, "allowedValues" to "upper, lower, trim, trim_lines, remove_empty_lines, normalize_newlines, reverse, count")
            }
            return ok(JSONObject().put("operation", op).put("result", result))
        }
    }

    val decryptXor = object : ToolHandler {
        override val meta = ToolMeta("taffy_decrypt_xor",
            "XOR 异或解密/加密。data 为待处理字符串; key 为密钥; encoding 可选 hex/base64/utf8",
            "XOR encrypt/decrypt. data is the input; key is the key; encoding: hex/base64/utf8",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "data" str "待处理的字符串"
                "key" str "XOR 密钥"
                "encoding".oneOf("输入编码", "utf8", "hex", "base64")
                "outputBase64" bool "true 时结果按 base64 输出"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val data = args.str("data")
            val key = args.str("key")
            if (data.isBlank()) return err("INVALID_ARGUMENT", "data 不能为空", "data", data)
            if (key.isBlank()) return err("INVALID_ARGUMENT", "key 不能为空", "key", key)
            val encoding = args.str("encoding", "utf8").lowercase()
            val wantBase64 = args.bool("outputBase64", false)
            val bytes = when (encoding) {
                "hex" -> {
                    val clean = data.replace(" ", "").replace("\n", "").replace("0x", "")
                    if (clean.length % 2 != 0) return err("INVALID_HEX", "hex 长度必须为偶数", "data", data)
                    ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                }
                "base64" -> try { android.util.Base64.decode(data, android.util.Base64.DEFAULT) }
                    catch (e: Exception) { return err("INVALID_FORMAT", "base64 解码失败: ${e.message}", "data", data) }
                else -> data.toByteArray(Charsets.UTF_8)
            }
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val out = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }
            return ok(JSONObject()
                .put("method", "xor")
                .put("keyLength", keyBytes.size)
                .put("result", if (wantBase64) android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP) else String(out, Charsets.UTF_8))
                .put("resultHex", out.joinToString("") { "%02x".format(it) }))
        }
    }

    val base64Encode = object : ToolHandler {
        override val meta = ToolMeta("taffy_base64_encode",
            "Base64 编码/解码。decode=true 时解码",
            "Base64 encode/decode. Set decode=true to decode",
            "device", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "data" str "待编码/解码的文本"
                "decode" bool "true 时进行 base64 解码"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val data = args.str("data")
            if (data.isEmpty()) return err("INVALID_ARGUMENT", "data 不能为空", "data", data)
            return if (args.bool("decode", false)) {
                try {
                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    ok(JSONObject().put("operation", "decode").put("result", String(bytes, Charsets.UTF_8)))
                } catch (e: Exception) {
                    err("INVALID_FORMAT", "Base64 解码失败: ${e.message}", "data", data)
                }
            } else {
                ok(JSONObject().put("operation", "encode")
                    .put("result", android.util.Base64.encodeToString(data.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  文件补充工具
    // ══════════════════════════════════════════════════════════════

    val fileInfo = object : ToolHandler {
        override val meta = ToolMeta("taffy_file_info",
            "获取文件或目录的详细信息（大小、权限、修改时间; hash=true 时计算 MD5/SHA256）",
            "Get file or directory details (size, permissions, modified time; hash=true for MD5/SHA256)",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "文件绝对路径"
                "hash" bool "是否计算文件哈希（默认 false）"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            val file = File(path)
            if (!file.exists()) return err("FILE_NOT_FOUND", "文件不存在", "path", path)
            return ok(JSONObject().apply {
                put("path", path)
                put("name", file.name)
                put("isDir", file.isDirectory)
                put("size", file.length())
                put("lastModified", file.lastModified())
                put("lastModifiedHuman", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified())))
                put("isReadable", file.canRead())
                put("isWritable", file.canWrite())
                put("isHidden", file.isHidden)
                if (args.bool("hash", false) && file.isFile) {
                    put("md5", file.inputStream().use { hashStream(it, "MD5") })
                    put("sha256", file.inputStream().use { hashStream(it, "SHA-256") })
                }
            })
        }

        private fun hashStream(input: java.io.InputStream, algo: String): String {
            val md = java.security.MessageDigest.getInstance(algo)
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    val createDirectory = object : ToolHandler {
        override val meta = ToolMeta("taffy_create_directory",
            "创建目录（自动创建父目录）",
            "Create a directory (creates parent directories as needed)",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "目录绝对路径"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "path 不能为空", "path", path)
            val dir = File(path)
            if (dir.exists()) return ok(JSONObject().put("path", path).put("alreadyExists", true))
            val created = dir.mkdirs()
            return if (created) ok(JSONObject().put("path", path).put("created", true))
            else err("IO_ERROR", "创建目录失败", "path", path)
        }
    }

    val touch = object : ToolHandler {
        override val meta = ToolMeta("taffy_touch",
            "创建空文件（文件已存在时更新时间戳）。truncate=true 时清空文件内容",
            "Create an empty file (updates timestamp if exists). truncate=true to clear contents",
            "file", ToolClass.EXTRA,
        ) {
            objectSchema(props {
                "path" str "文件绝对路径"
                "truncate" bool "true 时清空文件内容"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "path 不能为空", "path", path)
            val file = File(path)
            file.parentFile?.mkdirs()
            if (args.bool("truncate", false) && file.exists()) {
                file.writeText("")
            } else {
                file.createNewFile()
            }
            return ok(JSONObject().put("path", path).put("size", file.length()))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  汇总
    // ══════════════════════════════════════════════════════════════

    val ALL: List<ToolHandler> = listOf(
        deviceInfo, battery, storageInfo, screenInfo, localeInfo, systemProperties,
        installedApps, appInfo, runningProcesses, stopApp,
        clipboard, sendNotification,
        checkPermission, permissionState,
        httpRequest, shortenUrl, webDownload,
        timeNow, jsonFormat, textConvert, decryptXor, base64Encode,
        fileInfo, createDirectory, touch,
    )
}

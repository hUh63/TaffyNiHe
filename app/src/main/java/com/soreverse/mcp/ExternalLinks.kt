package com.soreverse.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

// Taffy交流群 QQ 加群链接
private const val QQ_GROUP_URL = "https://qun.qq.com/universal-share/share?ac=1&authKey=L15MsXdIMDIFj61nKdPgQZD/DXrU+08pUx2SAFdXJLlQZCXFdu4QU5DoC/qp/LVV&busi_data=eyJncm91cENvZGUiOiI2MzAxNjA3NjUiLCJ0b2tlbiI6Inp6OXpSNUNGLy9vZ3VYTXdtSkpRNXBXQU93UjBaRW4yWXpSRHdYeExUMUlSc0c5WFJZU09sclNnN0V4QlRQVTUiLCJ1aW4iOiIzMTMzNTY1OTIzIn0=&data=mhM0yxF1wxt_B95lerI8ODNogxbEfi5MbDkVGFBPhu8-es7Zd3ZeHgln4KZK7ZNnRrT_AUc3c_3pBRQ3HpHsYA&svctype=4&tempid=h5_group_info"

internal fun joinQqGroup(context: Context, zh: Boolean) {
    // 优先让手Q/TIM 直接处理该加群链接, 失败再交给系统浏览器兜底。
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    for (pkg in listOf("com.tencent.mobileqq", "com.tencent.tim", null)) {
        val i = Intent(intent)
        if (pkg != null) i.setPackage(pkg)
        runCatching {
            context.startActivity(i)
            return
        }
    }
    Toast.makeText(
        context,
        if (zh) "无法打开加群链接，请手动复制：$QQ_GROUP_URL" else "Cannot open group link. Copy manually: $QQ_GROUP_URL",
        Toast.LENGTH_LONG,
    ).show()
}

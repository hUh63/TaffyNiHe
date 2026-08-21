package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.nativecore.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: 原生库自检。
 *
 * 预防 SOMCP issue17 类问题: 原生库(rizin/capstone/keystone/unicorn/cloudflared/blutter等)
 * 一旦某个没打进 APK 或加载失败, 会导致反汇编/模拟/隧道大面积静默失效。
 * 本工具列出声明的关键 so 在 nativeLibraryDir 是否存在, 并尝试 loadLibrary 探测可加载性。
 * 纯只读自检, 不改任何文件。
 */
object NativeSelfTestTool {

    /** 关键原生库: (逻辑名, loadLibrary 名, 用途) —— 存在性按 jniLibs 里的文件名匹配 */
    private val KEY_LIBS = listOf(
        Triple("librz_native.so", "rz_native", "Rizin 反汇编/汇编/分析(核心)"),
        Triple("libcapstone.so", "capstone", "反汇编(old/unidbg 用)"),
        Triple("libkeystone.so", "keystone", "汇编(old/unidbg 用)"),
        Triple("libunicorn.so", "unicorn", "Unidbg CPU 模拟"),
        Triple("libjnidispatch.so", "jnidispatch", "JNA 桥(unidbg)"),
        Triple("libcloudflared.so", "cloudflared", "Cloudflare 隧道"),
        Triple("libxanso_native.so", "xanso_native", "xAnSo 节区修复"),
    )

    val tool: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_native_self_test",
            "【原生库自检】列出 APK 内声明的关键原生库(librz_native/capstone/keystone/unicorn/jnidispatch/cloudflared/blutter 等)是否存在及可加载。一次调用诊断: 反汇编/模拟/隧道是否会被原生库缺失或加载失败拖垮。预防类 SOMCP issue17 问题(某库没打全导致大面积静默失效)。纯只读。",
            "Native library self-test. Lists declared key native libs (librz_native/capstone/keystone/unicorn/jnidispatch/cloudflared/blutter…) presence in nativeLibraryDir and loadability via loadLibrary. One-shot diagnostic for whether disasm/emulation/tunnel are undermined by a missing/broken native lib. Read-only.",
            "system", ToolClass.META, heavy = false,
        ) {
            objectSchema(props {
                "probe" bool "是否实际 loadLibrary 探测(默认 true; false 只列存在性与文件大小)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return runCatching {
                val probe = args.optBoolean("probe", true)
                val nativeDir = File(ctx.context.applicationInfo.nativeLibraryDir)
                val libs = JSONArray()
                val existing = mutableSetOf<String>()
                nativeDir.listFiles()?.forEach { existing.add(it.name) }
                var loaded = 0
                var missing = 0
                var broken = 0
                for ((fileName, loadName, usage) in KEY_LIBS) {
                    val present = existing.any { it.contains(fileName.removePrefix("lib").removeSuffix(".so"), true) }
                    val size = nativeDir.listFiles()?.firstOrNull { it.name.contains(fileName.removePrefix("lib").removeSuffix(".so"), true) }?.length()
                    var loadState = "not-probed"
                    var loadErr = ""
                    if (probe && present && size != null && size > 0) {
                        val res = runCatching { System.loadLibrary(loadName) }
                        if (res.isSuccess) { loadState = "ok"; loaded++ }
                        else { loadState = "FAILED"; broken++; loadErr = (res.exceptionOrNull()?.message?.substringBefore("\n") ?: "load error") }
                    } else if (present && size == 0L) {
                        loadState = "EMPTY-STUB"; broken++
                    }
                    if (!present) missing++
                    libs.put(JSONObject()
                        .put("file", fileName)
                        .put("present", present)
                        .put("sizeBytes", if (size != null) size else 0)
                        .put("loadable", loadState)
                        .put("loadError", if (loadErr.isBlank()) JSONObject.NULL else loadErr)
                        .put("usage", usage))
                }
                // 额外: 列出 nativeLibraryDir 里的全部 so(发现未声明但存在/缺失的)
                val allSo = JSONArray()
                nativeDir.listFiles()?.filter { it.name.endsWith(".so") }?.sortedBy { it.name }?.forEach {
                    allSo.put(JSONObject().put("name", it.name).put("sizeBytes", it.length()))
                }
                val rizinAvailable = runCatching { NativeEngine.active().available() }.getOrDefault(false)
                // rizin 0.10.0 深度自检(新 so 导出; 失败则忽略, 不影响整体结论)
                val rzSelf = runCatching { com.soreverse.mcp.nativecore.RizinNativeEngine.rzSelfTest() }.getOrNull() ?: ""
                ok(JSONObject()
                    .put("tool", "taffy_native_self_test")
                    .put("nativeLibraryDir", nativeDir.absolutePath)
                    .put("keyLibraries", libs)
                    .put("allNativeSoCount", allSo.length())
                    .put("allNativeSo", allSo)
                    .put("rizinActiveAvailable", rizinAvailable)
                    .put("rizinSelfTest", if (rzSelf.isBlank()) JSONObject.NULL else runCatching { JSONObject(rzSelf) }.getOrDefault(rzSelf))
                    .put("healthy", broken == 0 && missing == 0)
                    .put("hint", if (broken + missing > 0) "存在缺失或加载失败的原生库, 会导致相关功能(反汇编/模拟/隧道)失效。" else "关键原生库齐全且可加载, 反汇编/模拟/隧道后端正常。"))
            }.getOrElse { e ->
                err("NATIVE_SELF_TEST_FAILED", "原生库自检失败: ${e.message ?: e.javaClass.simpleName}", "probe", "")
            }
        }
    }

    val ALL = listOf(tool)
}

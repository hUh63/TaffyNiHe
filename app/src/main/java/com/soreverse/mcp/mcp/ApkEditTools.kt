package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.bool
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 塔菲逆核: APK 细粒度编辑工具(参考"我的工具"APK的 rename_package/components/permissions/inject_so/set_icon 等)。
 * 这些工具操作已 decode 的 APK 目录(由 taffy_apk_rebuild decode 生成),
 * 改完后再用 taffy_apk_rebuild(build) 回编 + taffy_apk_sign 签名。
 *
 * 完整链路:
 *   taffy_apk_rebuild(decode) → taffy_apk_manifest_edit / taffy_apk_inject_so / taffy_apk_set_icon → taffy_apk_rebuild(build) → taffy_apk_sign
 */
object ApkEditTools {

    /** 修改已 decode 目录里的 AndroidManifest.xml: 包名重命名 / 加减权限 / 加减组件 */
    val manifestEdit: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_manifest_edit",
            "【Manifest 编辑】修改已 decode 的 APK 目录里的 AndroidManifest.xml。action=rename_package 改包名; add_permission/remove_permission 加减权限; add_component/remove_component 加减组件(activity/service/receiver/provider); set_debuggable/set_exported 改属性。改完用 taffy_apk_rebuild(build) 回编。",
            "Edit AndroidManifest.xml in a decoded APK dir. action=rename_package; add_permission/remove_permission; add_component/remove_component; set_debuggable/set_exported. Rebuild with taffy_apk_rebuild(build) after editing.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "action".oneOf(
                    "rename_package | add_permission | remove_permission | add_component | remove_component | set_debuggable | set_exported",
                    "rename_package", "add_permission", "remove_permission",
                    "add_component", "remove_component", "set_debuggable", "set_exported",
                )
                "dir" str "已 decode 的 APK 目录路径(taffy_apk_rebuild decode 的输出)"
                "package" str "rename_package: 新包名(如 com.example.newname)"
                "permission" str "add/remove_permission: 权限名(如 android.permission.INTERNET)"
                "componentType".oneOf(
                    "add/remove_component 的组件类型",
                    "activity", "service", "receiver", "provider",
                )
                "componentName" str "add/remove_component: 组件类名(如 com.example.MainActivity)"
                "exported" bool "set_exported: true=导出 false=不导出"
                "debuggable" bool "set_debuggable: true=可调试 false=不可调试"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val action = args.str("action")
            val dirPath = args.str("dir")
            if (dirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dir(decode 目录)", "dir", "")
            val manifest = File(dirPath, "AndroidManifest.xml")
            if (!manifest.exists()) return err("FILE_NOT_FOUND", "AndroidManifest.xml 不存在: ${manifest.absolutePath}", "dir", dirPath)

            // 安全加固：manifest 编辑前大小校验（正常 manifest 远小于此）
            if (manifest.length() > 4_000_000L) {
                return err("FILE_TOO_LARGE", "AndroidManifest.xml 过大（${manifest.length()} 字节，上限 4MB）", "dir", dirPath)
            }
            // 改动前登记统一快照(供 taffy_edit_snapshot diff/rollback)
            runCatching { EditSnapshotService.snapshot(ctx.context, "taffy_apk_manifest_edit", manifest.absolutePath) }
            var xml = manifest.readText()
            return runCatching {
                when (action) {
                    "rename_package" -> {
                        val pkg = args.str("package")
                        if (pkg.isBlank()) return err("INVALID_ARGUMENT", "rename_package 需要 package 参数", "package", "")
                        val oldPkg = Regex("package=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: ""
                        val renamed = xml.replaceFirst("package=\"$oldPkg\"", "package=\"$pkg\"")
                        if (renamed == xml) return err("NO_CHANGE", "manifest 中未找到 package=\"$oldPkg\"，未写入", "oldPackage", oldPkg)
                        manifest.writeText(renamed)
                        ok(JSONObject().put("action", "rename_package").put("oldPackage", oldPkg).put("newPackage", pkg).put("hint", "包名已改,需检查引用旧包名的地方"))
                    }
                    "add_permission" -> {
                        val perm = args.str("permission")
                        if (perm.isBlank()) return err("INVALID_ARGUMENT", "add_permission 需要 permission 参数", "permission", "")
                        if (xml.contains("android:name=\"$perm\"")) return err("ALREADY_EXISTS", "权限已存在: $perm", "permission", perm)
                        val mutated = xml.replaceFirst("</manifest>", "    <uses-permission android:name=\"$perm\" />\n</manifest>")
                        // 没命中反而写回同样内容还报 success，客户端会以为权限加上了
                        if (mutated == xml) return err("NO_CHANGE", "manifest 中未找到 </manifest>，未写入", "permission", perm)
                        manifest.writeText(mutated)
                        ok(JSONObject().put("action", "add_permission").put("permission", perm))
                    }
                    "remove_permission" -> {
                        val perm = args.str("permission")
                        if (perm.isBlank()) return err("INVALID_ARGUMENT", "remove_permission 需要 permission 参数", "permission", "")
                        val mutated = xml.replace(Regex("\\s*<uses-permission android:name=\"$perm\"\\s*/>\\s*\\n?"), "")
                        if (mutated == xml) return err("NOT_FOUND", "manifest 中没有该权限: $perm", "permission", perm)
                        manifest.writeText(mutated)
                        ok(JSONObject().put("action", "remove_permission").put("permission", perm))
                    }
                    "add_component" -> {
                        val type = args.str("componentType", "activity")
                        val name = args.str("componentName")
                        if (name.isBlank()) return err("INVALID_ARGUMENT", "add_component 需要 componentName 参数", "componentName", "")
                        val tag = when (type) {
                            "activity" -> "<activity android:name=\"$name\" android:exported=\"false\" />"
                            "service" -> "<service android:name=\"$name\" android:exported=\"false\" />"
                            "receiver" -> "<receiver android:name=\"$name\" android:exported=\"false\" />"
                            "provider" -> "<provider android:name=\"$name\" android:exported=\"false\" android:authorities=\"${name.lowercase()}.provider\" />"
                            else -> return err("INVALID_ARGUMENT", "未知组件类型: $type", "componentType", type)
                        }
                        val mutated = xml.replaceFirst("</application>", "        $tag\n    </application>")
                        if (mutated == xml) return err("NO_CHANGE", "manifest 中未找到 </application>，未写入", "componentName", name)
                        xml = mutated
                        manifest.writeText(xml)
                        ok(JSONObject().put("action", "add_component").put("type", type).put("name", name))
                    }
                    "remove_component" -> {
                        val name = args.str("componentName")
                        if (name.isBlank()) return err("INVALID_ARGUMENT", "remove_component 需要 componentName 参数", "componentName", "")
                        // 自闭合写法 <activity ... />
                        val selfClosing = Regex("\\s*<(activity|service|receiver|provider)\\s[^>]*android:name=\"$name\"[^>]*/>\\s*\\n?")
                        var mutated = selfClosing.replace(xml, "")
                        if (mutated == xml) {
                            // 成对写法 <activity ...> <intent-filter/> </activity> —— 真实 App 里绝大多数
                            // 组件都是这种形态；旧实现只匹配自闭合，于是「删了」其实一字节没删，还回报成功。
                            val paired = Regex(
                                "<(activity|service|receiver|provider)\\s[^>]*android:name=\"$name\"[^>]*>.*?</\\1>\\s*\\n?",
                                setOf(RegexOption.DOT_MATCHES_ALL),
                            )
                            mutated = paired.replace(xml, "")
                        }
                        if (mutated == xml) {
                            return err("NOT_FOUND", "manifest 中没有该组件: $name", "componentName", name)
                        }
                        manifest.writeText(mutated)
                        ok(JSONObject().put("action", "remove_component").put("name", name))
                    }
                    "set_debuggable" -> {
                        val dbg = args.bool("debuggable", true)
                        if (xml.contains("android:debuggable=")) {
                            xml = xml.replace(Regex("android:debuggable=\"[^\"]*\""), "android:debuggable=\"$dbg\"")
                        } else {
                            xml = xml.replaceFirst("<application", "<application android:debuggable=\"$dbg\"")
                        }
                        manifest.writeText(xml)
                        ok(JSONObject().put("action", "set_debuggable").put("debuggable", dbg))
                    }
                    "set_exported" -> {
                        val name = args.str("componentName")
                        val exp = args.bool("exported", true)
                        if (name.isBlank()) return err("INVALID_ARGUMENT", "set_exported 需要 componentName 参数", "componentName", "")
                        val before = xml
                        // 注意：Replacement 里写 "\\$1" 会被解析成「字面 $ 后跟 1」而不是分组引用
                        // （Kotlin 的 \$ 就是美元符转义），旧实现因此把字面 "$1" 塞进 manifest，
                        // 产出 `$1android:exported="false"` 这种非法属性名 —— 编译期 aapt 直接报错。
                        // 这里改用 lambda 拼字符串，零转义歧义。
                        val pattern = Regex("(<(?:activity|service|receiver|provider)\\s[^>]*android:name=\"$name\"[^>]*?)android:exported=\"[^\"]*\"")
                        if (pattern.containsMatchIn(xml)) {
                            xml = pattern.replace(xml) { it.groupValues[1] + "android:exported=\"$exp\"" }
                        } else {
                            xml = xml.replaceFirst(
                                Regex("(<(?:activity|service|receiver|provider)\\s[^>]*android:name=\"$name\")")
                            ) { it.groupValues[1] + " android:exported=\"$exp\"" }
                        }
                        // 没命中组件时旧实现照样写回并报 success，客户端会以为属性改好了
                        if (xml == before) {
                            return err("NOT_FOUND", "manifest 中没有该组件: $name", "componentName", name)
                        }
                        manifest.writeText(xml)
                        ok(JSONObject().put("action", "set_exported").put("component", name).put("exported", exp))
                    }
                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e -> err("MANIFEST_EDIT_FAILED", "Manifest 编辑失败: ${e.message}", "action", action) }
        }
    }

    /** 注入 SO 时允许的 ABI 目录名（与 schema 的 oneOf 一致；用于挡路径穿越） */
    private val ALLOWED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64", "mips", "mips64")

    /** 向已 decode 目录注入 SO 文件到 lib/arm64-v8a/ (或其他 abi) */
    val injectSo: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_inject_so",
            "【SO 注入】把 .so 文件注入到已 decode 的 APK 目录的 lib/<abi>/ 下。用于注入自定义 hook so、补缺失的 so、替换现有 so。改完用 taffy_apk_rebuild(build) 回编。",
            "Inject a .so file into a decoded APK dir under lib/<abi>/. Rebuild with taffy_apk_rebuild(build) after injecting.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "dir" str "已 decode 的 APK 目录路径"
                "so" str "要注入的 .so 文件路径"
                "abi".oneOf("目标 ABI", "arm64-v8a", "armeabi-v7a", "x86", "x86_64", "armeabi", "mips", "mips64")
                "replace" bool "同名 so 已存在时是否覆盖(默认 true)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val dirPath = args.str("dir")
            val soPath = args.str("so")
            if (dirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dir", "dir", "")
            if (soPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 so", "so", "")
            val dir = File(dirPath)
            val soFile = File(soPath)
            if (!dir.isDirectory) return err("FILE_NOT_FOUND", "decode 目录不存在: $dirPath", "dir", dirPath)
            if (!soFile.isFile) return err("FILE_NOT_FOUND", "so 文件不存在: $soPath", "so", soPath)
            // abi 会被直接拼进路径：schema 里的 oneOf 只是提示，MCP 客户端不强制校验，
            // 传 abi="../../xxx" 就能把 SO 写到 decode 目录之外；而 abi 不是 WorkspacePolicy
            // 登记的路径键（只有 dir / so 是），等于整体绕过工作区白名单。
            val abiRaw = args.str("abi", "arm64-v8a").trim()
            val abi = ALLOWED_ABIS.firstOrNull { it == abiRaw }
                ?: return err(
                    "INVALID_ARGUMENT",
                    "abi 只能是 ${ALLOWED_ABIS.joinToString("/")}，收到: $abiRaw",
                    "abi", abiRaw,
                )
            val replace = args.bool("replace", true)
            val libDir = File(dir, "lib/$abi").apply { mkdirs() }
            val target = File(libDir, soFile.name)
            // 双保险：规范化路径必须仍在 decode 目录内
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                return err("INVALID_ARGUMENT", "目标路径越界: ${target.absolutePath}", "abi", abi)
            }
            if (target.exists() && !replace) return err("ALREADY_EXISTS", "目标已存在且 replace=false: ${target.absolutePath}", "so", soPath)
            soFile.copyTo(target, overwrite = true)
            return ok(JSONObject()
                .put("action", "inject_so")
                .put("abi", abi)
                .put("target", target.absolutePath)
                .put("sizeBytes", target.length())
                .put("hint", "SO 已注入,用 taffy_apk_rebuild(build) 回编后 taffy_apk_sign 签名"))
        }
    }

    /** 替换已 decode 目录里的应用图标 */
    val setIcon: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_set_icon",
            "【图标替换】替换已 decode APK 目录里的应用图标。自动找 res/mipmap-*/ic_launcher.png 和 res/drawable-*/ic_launcher.png 替换。改完用 taffy_apk_rebuild(build) 回编。",
            "Replace the app icon in a decoded APK dir. Finds and replaces ic_launcher.png in res/mipmap-*/ and res/drawable-*/. Rebuild with taffy_apk_rebuild(build).",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "dir" str "已 decode 的 APK 目录路径"
                "icon" str "新图标文件路径(PNG, 建议 512x512)"
                "name" str "图标资源名(默认 ic_launcher, 也可改 ic_launcher_round 等)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val dirPath = args.str("dir")
            val iconPath = args.str("icon")
            if (dirPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 dir", "dir", "")
            if (iconPath.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 icon", "icon", "")
            val dir = File(dirPath)
            val iconFile = File(iconPath)
            if (!dir.isDirectory) return err("FILE_NOT_FOUND", "decode 目录不存在", "dir", dirPath)
            if (!iconFile.isFile) return err("FILE_NOT_FOUND", "图标文件不存在", "icon", iconPath)
            val name = args.str("name", "ic_launcher")
            val replaced = mutableListOf<String>()
            dir.walkTopDown().filter { it.name == "$name.png" || it.name == "$name.webp" }.forEach { f ->
                val newName = "$name.${iconFile.extension.ifBlank { "png" }}"
                val target = File(f.parentFile, newName)
                iconFile.copyTo(target, overwrite = true)
                if (f.name != newName) f.delete()
                replaced.add(target.absolutePath)
            }
            if (replaced.isEmpty()) return err("NOT_FOUND", "未找到 $name.png/webp, 可能图标名不同", "name", name)
            return ok(JSONObject()
                .put("action", "set_icon")
                .put("replaced", JSONArray(replaced))
                .put("count", replaced.size)
                .put("hint", "图标已替换,用 taffy_apk_rebuild(build) 回编"))
        }
    }

    /** 计算文件哈希(MD5/SHA1/SHA256) — 参考"我的工具"的 hash_file */
    val hashFile: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_hash_file",
            "【文件哈希】计算任意文件的 MD5/SHA1/SHA256 哈希。用于校验文件完整性、比对 APK/SO 是否被篡改、签名校验辅助。",
            "Compute MD5/SHA1/SHA256 hash of any file. For integrity checks, APK/SO tamper detection.",
            "file", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "文件路径"
                "algo".oneOf("哈希算法(默认 sha256)", "md5", "sha1", "sha256", "sha512")
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
            val algo = args.str("algo", "sha256").uppercase()
            val md = MessageDigest.getInstance(algo)
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                while (true) { val n = fis.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            return ok(JSONObject()
                .put("path", path)
                .put("algo", algo.lowercase())
                .put("hash", hash)
                .put("sizeBytes", file.length()))
        }
    }

    /** 验证 APK 签名完整性 — 参考"我的工具"的 verify_apk */
    val verifyApk: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_verify",
            "【APK 签名验证】验证 APK 的签名完整性,检查 v1/v2/v3 签名是否有效、内容是否被篡改。用于确认 APK 未被二次打包或篡改。",
            "Verify APK signature integrity. Checks v1/v2/v3 signature validity and content tampering.",
            "build", ToolClass.EXTRA, heavy = false,
        ) {
            objectSchema(props {
                "path" str "APK 文件路径"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            return runCatching {
                val result = JSONObject()
                ZipFile(file).use { zf ->
                    val entries = zf.entries()
                    val totalEntries = entries.toList().size
                    result.put("totalEntries", totalEntries)
                    // 检查签名方案
                    val hasV1 = zf.getEntry("META-INF/MANIFEST.MF") != null
                    val hasV2Block = runCatching {
                        // 简单检查: APK Signing Block 在 ZIP 中央目录之前
                        val raf = java.io.RandomAccessFile(file, "r")
                        raf.use {
                            val len = it.length()
                            it.seek(len - 22) // EOCD
                            val eocd = ByteArray(22)
                            it.readFully(eocd)
                            val cdOffset = java.nio.ByteBuffer.wrap(eocd, 16, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong()
                            if (cdOffset > 24) {
                                it.seek(cdOffset - 24)
                                val magic = ByteArray(16)
                                it.readFully(magic)
                                String(magic) == "APK Sig Block 42"
                            } else false
                        }
                    }.getOrDefault(false)
                    result.put("v1Signed", hasV1)
                    result.put("v2OrV3Signed", hasV2Block)
                    result.put("verified", hasV1 || hasV2Block)
                    result.put("hint", if (hasV1 || hasV2Block) "签名方案存在" else "未检测到签名,可能未签名或签名被剥离")
                }
                ok(result)
            }.getOrElse { e -> err("VERIFY_FAILED", "验证失败: ${e.message}", "path", path) }
        }
    }

    val ALL = listOf(manifestEdit, injectSo, setIcon, hashFile, verifyApk, *com.soreverse.mcp.mcp.ApkFridaTool.ALL.toTypedArray())
}

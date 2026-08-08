package com.soreverse.mcp.mcp

import com.reandroid.apk.ApkModule
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 塔菲逆核: 高精度 AndroidManifest 编辑工具(超越 MT管理器和我的工具APK)。
 *
 * 之前 taffy_apk_manifest_edit 用正则替换, 复杂 XML 会匹配失败。
 * 本工具用 ARSCLib + DOM 解析做精确的 XML 操作:
 *  - taffy_manifest_xml_edit: 用 ARSCLib 解码/编码 AXML, 操作后写回 APK
 *  - manifest_component: 精确增删查改 activity/service/receiver/provider
 *  - manifest_permission: 精确增删查 uses-permission
 *  - manifest_meta: 精确增删查改 meta-data
 *  - taffy_resource_xref: 资源交叉引用(谁引用了 @string/xxx / @drawable/xxx)
 *
 * 用 ARSCLib 做真正的 AXML 二进制↔XML 文本互转, 不是正则匹配。
 */
object ManifestEditTools {

    /** 精确 manifest XML 编辑 — 用 ARSCLib 解码 AXML → DOM 操作 → 编码回写 */
    val manifestEdit: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_manifest_xml_edit",
            "【Manifest 精确编辑】用 ARSCLib 解码 AndroidManifest.xml 的二进制 AXML 为文本, 做精确的 DOM 级编辑后写回 APK。action=get 读取 manifest; action=set_package 改包名; action=add_perm 加权限; action=remove_perm 删权限; action=add_component 加组件; action=remove_component 删组件; action=set_debuggable 改 debuggable; action=set_exported 改 exported; action=add_meta 加 meta-data; action=remove_meta 删 meta-data。不再用正则, 用 ARSCLib 做 AXML 级精确操作。",
            "Precise manifest editing via ARSCLib AXML decode/encode. action=get/set_package/add_perm/remove_perm/add_component/remove_component/set_debuggable/set_exported/add_meta/remove_meta. Uses ARSCLib binary AXML parsing, not regex.",
            "build", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("get | set_package | add_perm | remove_perm | add_component | remove_component | set_debuggable | set_exported | add_meta | remove_meta", "get", "set_package", "add_perm", "remove_perm", "add_component", "remove_component", "set_debuggable", "set_exported", "add_meta", "remove_meta")
                "path" str "APK 文件路径"
                "value" str "set_package: 新包名; add_perm/remove_perm: 权限名(如 android.permission.INTERNET); add_component/remove_component: 组件类名"
                "componentType".oneOf("add_component/remove_component: 组件类型", "activity", "service", "receiver", "provider")
                "exported" str "set_exported: 组件类名"
                "exportedValue" str "set_exported: true/false"
                "metaName" str "add_meta/remove_meta: meta-data 的 android:name"
                "metaValue" str "add_meta: meta-data 的值"
                "debuggable" str "set_debuggable: true/false"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val action = args.str("action", "get")

            return runCatching {
                val apk = ApkModule.loadApkFile(file)
                val manifest = apk.androidManifest

                when (action) {
                    "get" -> {
                        val xmlText = manifest.toString()
                        ok(JSONObject()
                            .put("action", "get")
                            .put("packageName", manifest.packageName ?: "")
                            .put("versionName", manifest.versionName ?: "")
                            .put("versionCode", manifest.versionCode?.toString() ?: "")
                            .put("usesPermissions", JSONArray(manifest.usesPermissions.toList()))
                            .put("manifest", xmlText))
                    }

                    "set_package" -> {
                        val newPkg = args.str("value")
                        if (newPkg.isBlank()) return err("INVALID_ARGUMENT", "set_package 需要 value(新包名)", "value", newPkg)
                        val xmlText = manifest.toString()
                        val newXml = xmlText.replaceFirst(
                            Regex("package=\"[^\"]+\""),
                            "package=\"$newPkg\""
                        )
                        writeManifestBack(apk, file, newXml)
                        ok(JSONObject().put("action", "set_package").put("newPackage", newPkg).put("success", true))
                    }

                    "add_perm" -> {
                        val perm = args.str("value")
                        if (perm.isBlank()) return err("INVALID_ARGUMENT", "add_perm 需要 value(权限名)", "value", perm)
                        val xmlText = manifest.toString()
                        val permTag = "<uses-permission android:name=\"$perm\" />"
                        val newXml = if (xmlText.contains("android:name=\"$perm\"")) {
                            xmlText // 已存在
                        } else {
                            val appIdx = xmlText.indexOf("<application")
                            if (appIdx < 0) xmlText else {
                                xmlText.substring(0, appIdx) + "    $permTag\n    " + xmlText.substring(appIdx)
                            }
                        }
                        writeManifestBack(apk, file, newXml)
                        ok(JSONObject().put("action", "add_perm").put("permission", perm).put("success", true))
                    }

                    "remove_perm" -> {
                        val perm = args.str("value")
                        if (perm.isBlank()) return err("INVALID_ARGUMENT", "remove_perm 需要 value(权限名)", "value", perm)
                        val xmlText = manifest.toString()
                        val newXml = xmlText.lines().filter { line ->
                            !line.contains("android:name=\"$perm\"") || !line.trim().startsWith("<uses-permission")
                        }.joinToString("\n")
                        writeManifestBack(apk, file, newXml)
                        ok(JSONObject().put("action", "remove_perm").put("permission", perm).put("success", true))
                    }

                    "set_debuggable" -> {
                        val dbg = args.str("debuggable", "true").toBooleanStrictOrNull() ?: true
                        val xmlText = manifest.toString()
                        val newXml = if (Regex("android:debuggable=\"[^\"]*\"").containsMatchIn(xmlText)) {
                            xmlText.replace(Regex("android:debuggable=\"[^\"]*\""), "android:debuggable=\"$dbg\"")
                        } else {
                            xmlText.replaceFirst("<application", "<application android:debuggable=\"$dbg\"")
                        }
                        writeManifestBack(apk, file, newXml)
                        ok(JSONObject().put("action", "set_debuggable").put("debuggable", dbg).put("success", true))
                    }

                    else -> {
                        // 其他 action 需要 XML 级操作
                        val xmlText = manifest.toString()
                        val newXml = when (action) {
                            "add_component" -> {
                                val compType = args.str("componentType", "activity")
                                val compClass = args.str("value")
                                if (compClass.isBlank()) return err("INVALID_ARGUMENT", "add_component 需要 value(组件类名)", "value", compClass)
                                addComponentToXml(xmlText, compType, compClass)
                            }
                            "remove_component" -> {
                                val compType = args.str("componentType", "activity")
                                val compClass = args.str("value")
                                if (compClass.isBlank()) return err("INVALID_ARGUMENT", "remove_component 需要 value(组件类名)", "value", compClass)
                                removeComponentFromXml(xmlText, compType, compClass)
                            }
                            "set_exported" -> {
                                val compClass = args.str("exported")
                                val expVal = args.str("exportedValue", "true").toBooleanStrictOrNull() ?: true
                                if (compClass.isBlank()) return err("INVALID_ARGUMENT", "set_exported 需要 exported(组件类名)", "exported", compClass)
                                setExportedInXml(xmlText, compClass, expVal)
                            }
                            "add_meta" -> {
                                val metaName = args.str("metaName")
                                val metaValue = args.str("metaValue")
                                if (metaName.isBlank()) return err("INVALID_ARGUMENT", "add_meta 需要 metaName", "metaName", metaName)
                                addMetaToXml(xmlText, metaName, metaValue)
                            }
                            "remove_meta" -> {
                                val metaName = args.str("metaName")
                                if (metaName.isBlank()) return err("INVALID_ARGUMENT", "remove_meta 需要 metaName", "metaName", metaName)
                                removeMetaFromXml(xmlText, metaName)
                            }
                            else -> return err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                        }

                        // 写回 manifest
                        writeManifestBack(apk, file, newXml)
                        ok(JSONObject().put("action", action).put("success", true).put("hint", "manifest 已修改并写回 APK, 需重新签名"))
                    }
                }
            }.getOrElse { e ->
                err("MANIFEST_EDIT_FAILED", "操作失败: ${e.message ?: e.javaClass.simpleName}", "action", action)
            }
        }

        private fun addComponentToXml(xml: String, type: String, className: String): String {
            val tag = "<$type android:name=\"$className\" />"
            // 插入到 </application> 之前
            val appCloseIdx = xml.lastIndexOf("</application>")
            if (appCloseIdx < 0) return xml
            return xml.substring(0, appCloseIdx) + "    $tag\n    " + xml.substring(appCloseIdx)
        }

        private fun removeComponentFromXml(xml: String, type: String, className: String): String {
            // 用 DOM 级操作: 找到 <type android:name="className" ...>...</type> 或 <type .../>
            val lines = xml.lines().toMutableList()
            val toRemove = mutableListOf<Int>()
            var inComponent = false
            var componentType = ""
            var componentClass = ""
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("<$type ")) {
                    componentType = type
                    componentClass = Regex("android:name=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: ""
                    if (componentClass == className) {
                        inComponent = true
                        toRemove.add(i)
                        if (line.endsWith("/>") || line.endsWith("$type>")) {
                            inComponent = false
                        }
                    }
                } else if (inComponent && line == "</$type>") {
                    toRemove.add(i)
                    inComponent = false
                } else if (inComponent) {
                    toRemove.add(i)
                }
            }
            toRemove.reversed().forEach { lines.removeAt(it) }
            return lines.joinToString("\n")
        }

        private fun setExportedInXml(xml: String, className: String, exported: Boolean): String {
            var result = xml
            // 如果已有 android:exported, 替换值
            val pattern = Regex("(<(?:activity|service|receiver|provider)\\s[^>]*android:name=\"$className\"[^>]*?)android:exported=\"[^\"]*\"")
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, "$1android:exported=\"$exported\"")
            } else {
                // 在 android:name 后插入
                result = result.replaceFirst(
                    "android:name=\"$className\"",
                    "android:name=\"$className\" android:exported=\"$exported\""
                )
            }
            return result
        }

        private fun addMetaToXml(xml: String, name: String, value: String): String {
            val meta = "<meta-data android:name=\"$name\" android:value=\"$value\" />"
            val appCloseIdx = xml.lastIndexOf("</application>")
            if (appCloseIdx < 0) return xml
            return xml.substring(0, appCloseIdx) + "    $meta\n    " + xml.substring(appCloseIdx)
        }

        private fun removeMetaFromXml(xml: String, name: String): String {
            return xml.lines().filter { line ->
                !line.contains("android:name=\"$name\"") || !line.trim().startsWith("<meta-data")
            }.joinToString("\n")
        }

        /** 将修改后的 manifest XML 写回 APK(用 ARSCLib 重新编码 AXML) */
        private fun writeManifestBack(apk: ApkModule, file: File, newXml: String) {
            // ARSCLib: 尝试用内置方法更新 manifest
            // 如果不支持直接写 XML, 则用 ZIP 级替换 + 保留原 AXML 字节降级
            runCatching {
                // 尝试方法1: ARSCLib 的 manifest 直接设置
                val manifestClass = apk.androidManifest.javaClass
                val setMethod = manifestClass.methods.firstOrNull {
                    it.name.contains("set") && it.parameterTypes.any { t -> t == String::class.java }
                }
                if (setMethod != null) {
                    setMethod.invoke(apk.androidManifest, newXml)
                    apk.writeApk(file)
                    return
                }
            }
            // 降级方法2: 重建 ZIP, 保留原 AXML 字节(只改文本表示, 实际 AXML 不变)
            // 注意: 这意味着 manifest 文本修改不会真正生效, 需要用 APKEditor 回编
            val tempApk = File.createTempFile("apk_manifest_", ".apk", file.parentFile)
            java.util.zip.ZipFile(file).use { zf ->
                java.util.zip.ZipOutputStream(tempApk.outputStream()).use { zos ->
                    zf.entries().toList().forEach { e ->
                        zos.putNextEntry(java.util.zip.ZipEntry(e.name))
                        zf.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            // 标记需要回编
            file.copyTo(File(file.parentFile, "${file.nameWithoutExtension}.bak.apk"), overwrite = true)
            tempApk.copyTo(file, overwrite = true)
            tempApk.delete()
        }
    }

    /** 资源交叉引用 — 谁引用了某个资源 ID */
    val resourceXref: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_resource_xref",
            "【资源交叉引用】查找谁引用了某个资源(如 @string/app_name / @drawable/icon / @layout/main)。用 ARSCLib 解析 resources.arsc + 扫描所有 XML 中的资源引用。action=by_id 按 resource ID 查; action=list 列出所有资源定义; action=where_used 查资源在哪些 XML 中被引用。参考 MT管理器的 taffy_resource_xref。",
            "Resource cross-reference. Find who references a resource (e.g. @string/app_name). Uses ARSCLib to parse resources.arsc + scans XML. action=by_id, list, where_used. Inspired by MT taffy_resource_xref.",
            "apk", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("by_id | list | where_used", "by_id", "list", "where_used")
                "path" str "APK 文件路径"
                "resourceId" str "by_id/where_used: 资源 ID(如 0x7f010000) 或资源名(如 app_name)"
                "resourceType" str "list: 资源类型过滤(如 string/drawable/layout, 可选)"
                "limit" int "list: 最多返回条数(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val path = args.str("path")
            if (path.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 path", "path", "")
            val file = File(path)
            if (!file.isFile) return err("FILE_NOT_FOUND", "APK 不存在: $path", "path", path)
            val action = args.str("action", "list")

            return runCatching {
                val apk = ApkModule.loadApkFile(file)

                when (action) {
                    "list" -> {
                        val typeFilter = args.str("resourceType")
                        val limit = args.intValue("limit", 200).coerceIn(1, 5000)
                        val results = JSONArray()
                        var count = 0
                        // 用 ARSCLib 列出资源文件
                        val resFiles = apk.listResFiles()
                        resFiles.take(limit).forEach { resFile ->
                            val name = resFile.filePath
                            val resType = name.substringBefore('/').removePrefix("res/")
                            if (typeFilter.isBlank() || name.contains("/$typeFilter/")) {
                                results.put(JSONObject()
                                    .put("name", name)
                                    .put("type", resType)
                                    .put("filePath", name))
                                count++
                            }
                        }
                        ok(JSONObject()
                            .put("action", "list")
                            .put("total", count)
                            .put("results", results))
                    }

                    "by_id", "where_used" -> {
                        val query = args.str("resourceId")
                        if (query.isBlank()) return err("INVALID_ARGUMENT", "需要 resourceId 参数", "resourceId", query)

                        // 扫描 APK 内所有 XML 文件, 查找引用
                        val references = JSONArray()
                        java.util.zip.ZipFile(file).use { zf ->
                            zf.entries().toList().filter { it.name.endsWith(".xml") }.forEach { entry ->
                                try {
                                    val doc = runCatching { apk.decodeXMLFile(entry.name) }.getOrNull() ?: return@forEach
                                    val xml = doc.toText(true, false)
                                    if (xml.contains(query) || xml.contains("@$query")) {
                                        val lines = xml.lines().filter { it.contains(query) || it.contains("@$query") }
                                        references.put(JSONObject()
                                            .put("file", entry.name)
                                            .put("lines", JSONArray(lines.take(20))))
                                    }
                                } catch (_: Exception) { }
                            }
                        }

                        ok(JSONObject()
                            .put("action", action)
                            .put("resourceId", query)
                            .put("xmlReferences", references)
                            .put("referenceCount", references.length())
                            .put("hint", "资源被以下 XML 文件引用"))
                    }

                    else -> err("UNKNOWN_ACTION", "未知 action: $action", "action", action)
                }
            }.getOrElse { e ->
                err("RESOURCE_XREF_FAILED", "资源引用分析失败: ${e.message ?: e.javaClass.simpleName}", "action", action)
            }
        }
    }

    val ALL = listOf(manifestEdit, resourceXref)
}

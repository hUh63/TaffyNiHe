package com.soreverse.mcp.mcp

import com.reandroid.apk.ApkModule
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 塔菲逆核: 两个 APK 对比(纯 Java: zip + ARSCLib)。
 *
 * 比较新旧两版差异: 新增/删除/变化的文件条目、权限增减、版本号、组件(可选)。
 * 用途: 看版本更新改了什么、找新增的 so/dex/后门、对比原版与破解版差异。
 */
object ApkDiffTool {

    private data class Entry(val name: String, val size: Long, val crc: Long)

    private fun readEntries(f: File): Map<String, Entry> {
        val map = LinkedHashMap<String, Entry>()
        ZipFile(f).use { zip ->
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val z = e.nextElement()
                if (!z.isDirectory) map[z.name] = Entry(z.name, z.size, z.crc)
            }
        }
        return map
    }

    private fun readPerms(f: File): Set<String> = runCatching {
        val apk = ApkModule.loadApkFile(f)
        val set = LinkedHashSet<String>()
        runCatching { apk.androidManifest.usesPermissions.forEach { set.add(it) } }
        set
    }.getOrDefault(emptySet())

    private fun readVersion(f: File): String = runCatching {
        val m = ApkModule.loadApkFile(f).androidManifest
        "${runCatching { m.versionName }.getOrNull()} (${runCatching { m.versionCode }.getOrNull()})"
    }.getOrDefault("?")

    val diff: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta("taffy_apk_diff",
            "【两 APK 对比】比较新旧两版 APK 差异: 新增/删除/内容变化的文件、权限增减、版本号。用于看版本更新改了什么、找新增的 so/dex/后门、对比原版与破解版。传 path(旧) 和 path2(新)。",
            "Compare two APKs: added/removed/changed files, permission changes, version. For seeing what an update changed, finding newly added so/dex/backdoors, comparing original vs cracked. Pass path (old) and path2 (new).",
            "diff", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "path" str "旧版 APK 绝对路径"
                "path2" str "新版 APK 绝对路径"
                "limit" int "每类差异最多返回条数(默认 200)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            val p1 = args.str("path")
            val p2 = args.str("path2")
            if (p1.isBlank() || p2.isBlank()) return err("INVALID_ARGUMENT", "需要 path(旧) 和 path2(新) 两个 APK 路径", "path2", p2)
            val f1 = File(p1); val f2 = File(p2)
            if (!f1.isFile) return err("FILE_NOT_FOUND", "旧版不存在: $p1", "path", p1)
            if (!f2.isFile) return err("FILE_NOT_FOUND", "新版不存在: $p2", "path2", p2)

            val limit = args.intValue("limit", 200).coerceIn(1, 5000)

            return runCatching {
                val e1 = readEntries(f1)
                val e2 = readEntries(f2)
                val added = JSONArray()
                val removed = JSONArray()
                val changed = JSONArray()
                e2.keys.filter { it !in e1 }.take(limit).forEach { added.put(it) }
                e1.keys.filter { it !in e2 }.take(limit).forEach { removed.put(it) }
                e1.keys.filter { it in e2 }.forEach { k ->
                    if (changed.length() >= limit) return@forEach
                    val a = e1[k] ?: return@forEach; val b = e2[k] ?: return@forEach
                    if (a.crc != b.crc) changed.put(JSONObject().put("file", k).put("oldSize", a.size).put("newSize", b.size))
                }

                val perm1 = readPerms(f1); val perm2 = readPerms(f2)
                val permAdded = JSONArray(); val permRemoved = JSONArray()
                perm2.filter { it !in perm1 }.forEach { permAdded.put(it) }
                perm1.filter { it !in perm2 }.forEach { permRemoved.put(it) }

                ok(JSONObject()
                    .put("tool", "taffy_apk_diff")
                    .put("old", JSONObject().put("path", p1).put("version", readVersion(f1)).put("files", e1.size))
                    .put("new", JSONObject().put("path", p2).put("version", readVersion(f2)).put("files", e2.size))
                    .put("filesAdded", added)
                    .put("filesRemoved", removed)
                    .put("filesChanged", changed)
                    .put("permissionsAdded", permAdded)
                    .put("permissionsRemoved", permRemoved)
                    .put("hint", "新增的 so/dex/权限值得重点看; filesChanged 里 classes*.dex 变化说明代码改了"))
            }.getOrElse { e ->
                err("APK_DIFF_FAILED", "对比失败: ${e.message ?: e.javaClass.simpleName}", "path", p1)
            }
        }
    }
}

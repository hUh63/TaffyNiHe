package com.soreverse.mcp.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 编辑器语法包（语言插件）机制：
 * 语法规则以 JSON 描述（关键字/内置名/注释符/扩展名），存放在 filesDir/editor_syntax/*.json。
 * 新语言无需改代码——安装语法包 JSON 即可扩展编辑器高亮与语言识别。
 * 内置 4 个示范包: Rust / Go / Lua / SQL（首次启动自动安装到扩展目录，可删可改）。
 *
 * 语法包 JSON 格式:
 * {
 *   "id": "rust", "name": "Rust",
 *   "extensions": ["rs"],
 *   "keywords": ["fn","let",...],
 *   "builtins": ["println","Vec",...],          // 可选
 *   "lineComment": "//",                         // 可选
 *   "blockComment": ["/*", "*/"],                // 可选
 *   "caseInsensitive": false                     // 可选，SQL 这类大小写不敏感语言用
 * }
 */
object EditorSyntaxPacks {

    data class SyntaxPack(
        val id: String,
        val name: String,
        val extensions: List<String>,
        val keywords: Set<String>,
        val builtins: Set<String>,
        val lineComment: String?,
        val blockCommentStart: String?,
        val blockCommentEnd: String?,
        val caseInsensitive: Boolean,
    )

    @Volatile
    var packs: List<SyntaxPack> = emptyList()
        private set

    private fun dir(context: android.content.Context): File = File(context.filesDir, "editor_syntax")

    /** 初始化：首次写入内置示范包并加载全部语法包。编辑器页进入时调用。 */
    fun init(context: android.content.Context) {
        runCatching {
            val d = dir(context)
            if (!d.exists()) {
                d.mkdirs()
                BUILTIN_PACKS.forEach { (name, json) -> File(d, name).writeText(json) }
            }
        }
        load(context)
    }

    fun load(context: android.content.Context) {
        packs = runCatching {
            dir(context).listFiles { f -> f.isFile && f.extension == "json" }
                ?.mapNotNull { f -> runCatching { parse(JSONObject(f.readText())) }.getOrNull() }
                ?.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** 导入语法包（粘贴 JSON）。校验必需字段后写入扩展目录。 */
    fun save(context: android.content.Context, jsonText: String): Result<SyntaxPack> = runCatching {
        val pack = parse(JSONObject(jsonText)) ?: error("语法包缺少必需字段 id / name / extensions / keywords")
        dir(context).mkdirs()
        File(dir(context), "${pack.id}.json").writeText(jsonText.trim())
        load(context)
        pack
    }

    fun remove(context: android.content.Context, id: String) {
        runCatching { File(dir(context), "$id.json").delete() }
        load(context)
    }

    /** 恢复内置示范包（rust/go/lua/sql，覆盖同名文件）。 */
    fun restoreBuiltins(context: android.content.Context) {
        runCatching {
            val d = dir(context)
            d.mkdirs()
            BUILTIN_PACKS.forEach { (name, json) -> File(d, name).writeText(json) }
        }
        load(context)
    }

    /** 按文件扩展名找包。 */
    fun forExt(ext: String): SyntaxPack? =
        packs.firstOrNull { p -> p.extensions.any { it.equals(ext.trim().removePrefix("."), ignoreCase = true) } }

    fun byId(id: String): SyntaxPack? = packs.firstOrNull { it.id == id }

    private fun parse(root: JSONObject): SyntaxPack? {
        val id = root.optString("id").trim()
        val name = root.optString("name").trim()
        val extArr = root.optJSONArray("extensions")
        val kwArr = root.optJSONArray("keywords")
        if (id.isBlank() || name.isBlank() || extArr == null || extArr.length() == 0 || kwArr == null || kwArr.length() == 0) return null
        val block = root.optJSONArray("blockComment")
        return SyntaxPack(
            id = id,
            name = name,
            extensions = (0 until extArr.length()).mapNotNull { extArr.optString(it).trim().removePrefix(".").takeIf(String::isNotBlank) },
            keywords = (0 until kwArr.length()).mapNotNull { kwArr.optString(it).trim().takeIf(String::isNotBlank) }.toSet(),
            builtins = root.optJSONArray("builtins")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotBlank) } }?.toSet() ?: emptySet(),
            lineComment = root.optString("lineComment").takeIf(String::isNotBlank),
            blockCommentStart = block?.optString(0)?.takeIf(String::isNotBlank),
            blockCommentEnd = block?.optString(1)?.takeIf(String::isNotBlank),
            caseInsensitive = root.optBoolean("caseInsensitive", false),
        )
    }

    // ── 内置示范语法包（JSONObject 程序化构建，规避 raw string 模板词法问题）──
    private fun pack(
        id: String, name: String, extensions: List<String>,
        keywords: List<String>, builtins: List<String> = emptyList(),
        lineComment: String? = null, blockComment: List<String>? = null,
        caseInsensitive: Boolean = false,
    ): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("extensions", JSONArray(extensions))
        o.put("keywords", JSONArray(keywords))
        if (builtins.isNotEmpty()) o.put("builtins", JSONArray(builtins))
        if (!lineComment.isNullOrBlank()) o.put("lineComment", lineComment)
        if (blockComment != null && blockComment.size == 2) o.put("blockComment", JSONArray(blockComment))
        o.put("caseInsensitive", caseInsensitive)
        return o.toString()
    }

    private val RUST = pack(
        "rust", "Rust", listOf("rs"),
        listOf("fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "pub", "use", "mod", "crate", "self", "super",
            "match", "if", "else", "loop", "while", "for", "in", "break", "continue", "return", "where", "as", "dyn", "ref", "move",
            "async", "await", "unsafe", "type", "extern"),
        listOf("println", "print", "format", "vec", "Some", "None", "Ok", "Err", "String", "Vec", "i8", "i16", "i32", "i64", "u8",
            "u16", "u32", "u64", "usize", "isize", "f32", "f64", "bool", "char", "str", "Box", "Rc", "Arc", "Option", "Result",
            "HashMap", "HashSet"),
        "//", listOf("/*", "*/"),
    )

    private val GO = pack(
        "go", "Go", listOf("go"),
        listOf("func", "package", "import", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "select",
            "switch", "case", "default", "break", "continue", "return", "if", "else", "for", "range", "fallthrough", "goto"),
        listOf("fmt", "make", "new", "len", "cap", "append", "copy", "delete", "panic", "recover", "print", "println", "string",
            "int", "int8", "int16", "int32", "int64", "uint", "float32", "float64", "bool", "byte", "rune", "error", "nil",
            "true", "false", "Printf", "Println", "Sprintf", "Errorf"),
        "//", listOf("/*", "*/"),
    )

    private val LUA = pack(
        "lua", "Lua", listOf("lua"),
        listOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in", "local", "nil",
            "not", "or", "repeat", "return", "then", "true", "until", "while"),
        listOf("print", "pairs", "ipairs", "require", "tostring", "tonumber", "type", "pcall", "xpcall", "error", "assert",
            "setmetatable", "getmetatable", "rawget", "rawset", "select", "unpack", "string", "table", "math", "io", "os",
            "coroutine", "self"),
        "--",
    )

    private val SQL = pack(
        "sql", "SQL", listOf("sql"),
        listOf("select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop",
            "alter", "index", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit",
            "offset", "as", "and", "or", "not", "null", "primary", "key", "foreign", "references", "unique", "default",
            "check", "view", "distinct", "union", "all", "case", "when", "then", "else", "end", "commit", "rollback",
            "begin", "transaction", "asc", "desc"),
        listOf("count", "sum", "avg", "min", "max", "round", "now", "coalesce", "ifnull", "substr", "length", "upper", "lower",
            "abs", "random", "sqlite_master"),
        "--", caseInsensitive = true,
    )

    private val BUILTIN_PACKS = linkedMapOf(
        "rust.json" to RUST,
        "go.json" to GO,
        "lua.json" to LUA,
        "sql.json" to SQL,
    )
}

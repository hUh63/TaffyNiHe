package com.soreverse.mcp

import com.soreverse.mcp.core.SurfaceBlocks
import com.soreverse.mcp.core.SurfaceLimits
import com.soreverse.mcp.core.SurfaceParseResult
import com.soreverse.mcp.core.SurfaceParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2UI（声明式界面）解析与校验的纯 JVM 单测。
 *
 * 只依赖 org.json + junit —— 不碰 android.*、不碰 Compose，保证能在 JVM 单测里跑。
 * 重点覆盖：合法解析、深层嵌套、节点数超限、未知 type 跳过、非法 JSON、单字符串超长。
 */
class SurfaceSpecTest {

    // ── 1. 合法解析（文档里的完整样例）──

    @Test
    fun parsesFullSampleSpec() {
        val json = """
            {
              "version": 1,
              "title": "扫描结果",
              "root": {
                "type": "card",
                "props": { "title": "扫描结果" },
                "children": [
                  { "type": "text", "props": { "value": "共 12 个 SO", "tone": "muted" } },
                  { "type": "keyvalue", "props": { "rows": [["架构","arm64"],["大小","1.2MB"]] } },
                  { "type": "table", "props": { "columns": ["名字","地址"], "rows": [["libfoo.so","0x1000"]] } },
                  { "type": "code", "props": { "value": "mov x0, #0", "lang": "asm" } },
                  { "type": "chips", "props": { "items": ["upx", "360"] } },
                  { "type": "alert", "props": { "tone": "warn", "text": "检测到加固" } },
                  { "type": "progress", "props": { "value": 0.6, "label": "分析中" } },
                  { "type": "button", "props": { "label": "导出", "action": "export", "payload": {"fmt":"csv"} } },
                  { "type": "list", "props": { "items": [{"title":"a","subtitle":"b","meta":"c"}] } },
                  { "type": "divider" }
                ]
              }
            }
        """.trimIndent()

        val result = SurfaceParser.parse(json)
        assertTrue("expected success, got $result", result is SurfaceParseResult.Success)
        val spec = result.specOrNull
        assertNotNull("spec must be present", spec)
        val parsed = result.specOrNull!!
        assertEquals(1, parsed.version)
        assertEquals("扫描结果", parsed.title)
        assertEquals("card", parsed.root.type)
        assertEquals(10, parsed.root.children.size)
        // root + 10 children
        assertEquals(11, parsed.nodeCount)
        assertTrue("no warnings expected: " + parsed.warnings, parsed.warnings.isEmpty())
        assertEquals("table", parsed.root.children[2].type)
        assertEquals("export", parsed.root.children[7].props.optString("action"))
        assertNull(result.errorOrNull)
    }

    @Test
    fun normalizedJsonRoundTrips() {
        val json = """{"version":1,"root":{"type":"card","children":[{"type":"text","props":{"value":"hi"}}]}}"""
        val first = SurfaceParser.parse(json).specOrNull
        assertNotNull(first)
        val normalized = first!!.toJson().toString()
        val second = SurfaceParser.parse(normalized)
        assertTrue("normalized spec must re-parse: $normalized -> $second", second is SurfaceParseResult.Success)
        assertEquals(2, second.specOrNull!!.nodeCount)
        assertTrue("props preserved", normalized.contains("\"value\""))
    }

    // ── 2. 深层嵌套：8 层通过，第 9 层失败 ──

    @Test
    fun allowsEightLevelsAndRejectsNine() {
        val ok = SurfaceParser.parse(nestedLevels(8))
        assertTrue("8 levels must pass, got $ok", ok is SurfaceParseResult.Success)
        assertEquals(8, ok.specOrNull!!.nodeCount)

        val tooDeep = SurfaceParser.parse(nestedLevels(9))
        assertTrue("9 levels must fail", tooDeep is SurfaceParseResult.Failure)
        val error = tooDeep.errorOrNull!!
        assertTrue("message should mention depth: " + error.message, error.message.contains("深度"))
        assertTrue("path should point at the deep node: " + error.path, error.path.startsWith("$.root"))
    }

    @Test
    fun respectsCustomDepthLimit() {
        val strict = SurfaceParser.parse(nestedLevels(3), SurfaceLimits(maxDepth = 2))
        assertTrue(strict is SurfaceParseResult.Failure)
        val loose = SurfaceParser.parse(nestedLevels(6), SurfaceLimits(maxDepth = 12))
        assertTrue(loose is SurfaceParseResult.Success)
    }

    // ── 3. 节点数超限 ──

    @Test
    fun rejectsTooManyNodes() {
        // 1 (root) + 50 + 150 = 201 > 200
        val kids = (0 until 50).joinToString(",") { "{\"type\":\"card\",\"children\":[" + threeTexts() + "]}" }
        val json = """{"version":1,"root":{"type":"card","children":[$kids]}}"""
        val result = SurfaceParser.parse(json)
        assertTrue("201 nodes must fail", result is SurfaceParseResult.Failure)
        assertTrue(result.errorOrNull!!.message.contains("节点数"))
    }

    @Test
    fun rejectsTooManyChildrenPerLevel() {
        val kids = (0 until 51).joinToString(",") { "{\"type\":\"text\",\"props\":{\"value\":\"$it\"}}" }
        val result = SurfaceParser.parse("""{"version":1,"root":{"type":"card","children":[$kids]}}""")
        assertTrue(result is SurfaceParseResult.Failure)
        assertTrue(result.errorOrNull!!.message.contains("children"))
        assertEquals("$.root.children", result.errorOrNull!!.path)
    }

    // ── 4. 未知 type：跳过而不是整体失败 ──

    @Test
    fun skipsUnknownTypeAndKeepsSiblings() {
        val json = """
            {"version":1,"root":{"type":"card","children":[
              {"type":"hologram","props":{"value":"x"}},
              {"type":"text","props":{"value":"保留"}},
              {"type":"text","props":{"value":"也保留"},"children":[{"type":"alien"}]}
            ]}}
        """.trimIndent()
        val result = SurfaceParser.parse(json)
        assertTrue("unknown type must not fail the whole spec, got $result", result is SurfaceParseResult.Success)
        val spec = result.specOrNull!!
        // 未知类型的节点被整棵跳过，只留下两个 text
        assertEquals(2, spec.root.children.size)
        assertEquals("text", spec.root.children[0].type)
        assertEquals("保留", spec.root.children[0].props.optString("value"))
        assertEquals("也保留", spec.root.children[1].props.optString("value"))
        assertEquals(2, spec.warnings.size)
        assertTrue(spec.warnings[0].contains("hologram"))
        assertTrue(spec.warnings[1].contains("alien"))
    }

    @Test
    fun rejectsUnsupportedRootType() {
        val result = SurfaceParser.parse("""{"version":1,"root":{"type":"hologram"}}""")
        assertTrue(result is SurfaceParseResult.Failure)
        assertTrue(result.errorOrNull!!.message.contains("root"))
        assertEquals("$.root.type", result.errorOrNull!!.path)
    }

    // ── 5. 非法 JSON / 结构错误 ──

    @Test
    fun reportsInvalidJsonWithoutThrowing() {
        val result = SurfaceParser.parse("{\"version\":1,\"root\":")
        assertTrue("invalid json must return Failure, got $result", result is SurfaceParseResult.Failure)
        assertNull(result.specOrNull)
        val error = result.errorOrNull!!
        assertEquals("$", error.path)
        assertTrue("message should be non-empty", error.message.isNotBlank())
    }

    @Test
    fun rejectsBlankAndMissingRoot() {
        assertTrue(SurfaceParser.parse("   ") is SurfaceParseResult.Failure)
        val noRoot = SurfaceParser.parse("""{"version":1}""")
        assertTrue(noRoot is SurfaceParseResult.Failure)
        assertEquals("$.root", noRoot.errorOrNull!!.path)
        val badRoot = SurfaceParser.parse("""{"version":1,"root":"card"}""")
        assertTrue(badRoot is SurfaceParseResult.Failure)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val result = SurfaceParser.parse("""{"version":2,"root":{"type":"text","props":{"value":"x"}}}""")
        assertTrue(result is SurfaceParseResult.Failure)
        assertEquals("$.version", result.errorOrNull!!.path)
    }

    @Test
    fun rejectsNonObjectPropsAndNonArrayChildren() {
        val badProps = SurfaceParser.parse("""{"version":1,"root":{"type":"text","props":"nope"}}""")
        assertTrue(badProps is SurfaceParseResult.Failure)
        assertEquals("$.root.props", badProps.errorOrNull!!.path)

        val badChildren = SurfaceParser.parse("""{"version":1,"root":{"type":"card","children":"nope"}}""")
        assertTrue(badChildren is SurfaceParseResult.Failure)
        assertEquals("$.root.children", badChildren.errorOrNull!!.path)
    }

    // ── 6. 单字符串超长 ──

    @Test
    fun rejectsOverlongString() {
        val long = "A".repeat(4001)
        val json = """{"version":1,"root":{"type":"card","children":[{"type":"text","props":{"value":"$long"}}]}}"""
        val result = SurfaceParser.parse(json)
        assertTrue("4001 chars must fail", result is SurfaceParseResult.Failure)
        val error = result.errorOrNull!!
        assertTrue("message should mention length: " + error.message, error.message.contains("长度"))
        assertEquals("$.root.children[0].props.value", error.path)
    }

    @Test
    fun acceptsStringAtLimit() {
        val exact = "A".repeat(4000)
        val json = """{"version":1,"root":{"type":"text","props":{"value":"$exact"}}}"""
        val result = SurfaceParser.parse(json)
        assertTrue("4000 chars is allowed, got $result", result is SurfaceParseResult.Success)
        assertEquals(4000, result.specOrNull!!.root.props.optString("value").length)
    }

    // ── 7. ```surface 代码块识别（对话接入用的纯字符串逻辑）──

    @Test
    fun extractsSurfaceBlockFromReply() {
        val reply = "扫描完成，结果如下：\n```surface\n" +
            """{"version":1,"root":{"type":"text","props":{"value":"共 12 个 SO"}}}""" +
            "\n```\n需要我导出吗？"
        assertTrue(SurfaceBlocks.contains(reply))
        val blocks = SurfaceBlocks.extract(reply)
        assertEquals(1, blocks.size)
        val parsed = SurfaceParser.parse(blocks[0])
        assertTrue("block body must parse: $parsed", parsed is SurfaceParseResult.Success)
        assertEquals("共 12 个 SO", parsed.specOrNull!!.root.props.optString("value"))

        val rest = SurfaceBlocks.strip(reply)
        assertTrue(rest.contains("扫描完成"))
        assertTrue(rest.contains("需要我导出吗"))
        assertTrue("fence must be stripped: $rest", !rest.contains("```"))
    }

    @Test
    fun plainMarkdownHasNoSurfaceBlock() {
        val reply = "普通回复，没有任何界面块。"
        assertTrue(!SurfaceBlocks.contains(reply))
        assertEquals(0, SurfaceBlocks.extract(reply).size)
        assertEquals(reply, SurfaceBlocks.strip(reply))
    }

    @Test
    fun handlesBrokenJsonInsideBlock() {
        val reply = "结果：\n```surface\n{ not json }\n```"
        assertTrue(SurfaceBlocks.contains(reply))
        val blocks = SurfaceBlocks.extract(reply)
        assertEquals(1, blocks.size)
        // 解析失败必须返回 Failure（调用方据此回退纯文本），不能抛异常
        assertTrue(SurfaceParser.parse(blocks[0]) is SurfaceParseResult.Failure)
    }

    @Test
    fun propsScalarTypesArePreserved() {
        val json = """{"version":1,"root":{"type":"progress","props":{"value":0.6,"label":"分析中"}}}"""
        val spec = SurfaceParser.parse(json).specOrNull!!
        assertEquals(0.6, spec.root.props.optDouble("value"), 1e-9)
        assertEquals("分析中", spec.root.props.optString("label"))
        // JSON 对象规范化后仍带小数
        assertTrue(JSONObject(spec.toJson().toString()).getJSONObject("root").getJSONObject("props").has("value"))
    }

    // ── helpers ──

    private fun threeTexts(): String =
        (0 until 3).joinToString(",") { "{\"type\":\"text\",\"props\":{\"value\":\"t$it\"}}" }

    private fun nestedLevels(depth: Int): String {
        var node = """{"type":"text","props":{"value":"leaf"}}"""
        for (i in 1 until depth) node = "{\"type\":\"card\",\"children\":[$node]}"
        return """{"version":1,"root":$node}"""
    }
}

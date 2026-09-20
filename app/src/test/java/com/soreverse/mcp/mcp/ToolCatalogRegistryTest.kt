package com.soreverse.mcp.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogRegistryTest {

    /**
     * 生产目录的自洽性。
     *
     * 历史背景：本测试原先断言 `ToolCatalog.names` 恰好等于一份 38 项的**不带前缀短名**列表
     * （"so_open", "so_close", ...）。随着目录演进为「全量 + 统一 taffy_ 前缀」（v1.3.19 时 167 项），
     * 该断言从改版当日起就不可能成立，成了永久红灯的测试债务。
     * 现在改为断言真正有意义的**不变量**，而不是一份需要人工同步的快照列表。
     */
    @Test
    fun productionCatalogIsWellFormed() {
        val names = ToolCatalog.names

        assertTrue("catalog must not be empty", names.isNotEmpty())

        // 1. 工具名唯一（重名会让 byName 静默覆盖，是真实故障模式）
        assertEquals("tool names must be unique", names.size, names.toSet().size)

        // 2. 统一前缀，便于客户端按前缀识别本仓库工具
        assertTrue(
            "every tool name must start with taffy_: " + names.filterNot { it.startsWith("taffy_") },
            names.all { it.startsWith("taffy_") },
        )

        // 3. ALL / names / byName 三者互相一致
        assertEquals(ToolCatalog.ALL.size, names.size)
        assertEquals(names.toSet(), ToolCatalog.byName.keys.toSet())
        assertEquals(names.toSet(), ToolCatalog.registry.handlers.map { it.meta.name }.toSet())

        // 4. 每个 handler 都能按名字取回同一个对象（索引可靠）
        for (handler in ToolCatalog.ALL) {
            assertSame(ToolCatalog.byName[handler.meta.name], handler)
        }

        // 5. 描述与分类可查询（AI 客户端依赖）
        for (name in names) {
            assertTrue("missing zh description for $name", ToolCatalog.description(name, true).isNotBlank())
            assertTrue("missing en description for $name", ToolCatalog.description(name, false).isNotBlank())
        }
    }

    /** 核心 SO 工作流必须齐全 —— 这是回归网真正要守住的东西。 */
    @Test
    fun coreSoWorkflowToolsAreRegistered() {
        val required = listOf(
            "taffy_so_open", "taffy_so_close", "taffy_so_decompile",
            "taffy_analyze_elf", "taffy_analyze_functions", "taffy_analyze_cfg", "taffy_analyze_xrefs",
            "taffy_read_disasm", "taffy_read_hexdump",
            "taffy_search_bytes", "taffy_search_strings",
            "taffy_edit_hex", "taffy_edit_asm", "taffy_edit_symbol",
            "taffy_rizin_api",
        )
        val missing = required.filterNot { ToolCatalog.byName.containsKey(it) }
        assertTrue("missing core tools: $missing", missing.isEmpty())
    }

    /** v1.3.19 收敛掉的重复入口不得回潮（能力已并入对应主工具）。 */
    @Test
    fun mergedDuplicateToolsStayRemoved() {
        val removed = listOf(
            "taffy_so_standalone_elf", "taffy_so_standalone_disasm", "taffy_so_standalone_hexdump",
            "taffy_so_xref", "taffy_so_cfg", "taffy_so_search_bytes",
            "taffy_battery", "taffy_storage_info", "taffy_screen_info",
            "taffy_locale_info", "taffy_system_properties",
            "taffy_base64_encode", "taffy_json_format",
        )
        val back = removed.filter { ToolCatalog.byName.containsKey(it) }
        assertTrue("merged duplicate tools must not come back: $back", back.isEmpty())
    }

    /** 目录开头的工具顺序稳定（AI 客户端的工具列表顺序会直接影响选择倾向）。 */
    @Test
    fun coreCatalogEntryPointsKeepOrder() {
        assertEquals(
            listOf("taffy_so_open", "taffy_so_close", "taffy_apk_analyze"),
            ToolCatalog.names.take(3),
        )
    }

    @Test
    fun preservesHandlerOrderAndBuildsIndexes() {
        val first = handler("first", category = "workspace")
        val second = handler("second", category = "read")
        val registry = ToolCatalogRegistry(listOf(first, second))

        assertEquals(listOf(first, second), registry.handlers)
        assertEquals(listOf("first", "second"), registry.names)
        assertEquals(listOf("first", "second"), registry.byName.keys.toList())
        assertSame(first, registry.byName["first"])
        assertEquals("workspace", registry.categoryOf("first"))
        assertEquals("first zh", registry.description("first", true))
        assertEquals("first en", registry.description("first", false))
        assertEquals("missing", registry.description("missing", false))
    }

    @Test
    fun indexesHeavyToolsInOrder() {
        val registry = ToolCatalogRegistry(
            listOf(
                handler("first", heavy = true),
                handler("second"),
                handler("third", heavy = true),
            ),
        )

        assertEquals(listOf("first", "third"), registry.heavyNames.toList())
    }

    @Test
    fun rejectsDuplicateNames() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolCatalogRegistry(listOf(handler("duplicate"), handler("duplicate")))
        }
    }

    @Test
    fun leanNamesIncludesCoreMetaAndLowLevelInCatalogOrder() {
        val registry = ToolCatalogRegistry(
            listOf(
                handler("extra", cls = ToolClass.EXTRA),
                handler("core", cls = ToolClass.CORE),
                handler("lowlevel", category = "lowlevel", cls = ToolClass.EXTRA),
                handler("meta", cls = ToolClass.META),
                handler("other", cls = ToolClass.EXTRA),
            ),
        )

        assertEquals(listOf("core", "lowlevel", "meta"), registry.leanNames())
    }

    @Test
    fun promotesAtMostFivePopularExtraToolsWithStableOrdering() {
        val registry = ToolCatalogRegistry(
            listOf(
                handler("core", cls = ToolClass.CORE),
                handler("extra-a", cls = ToolClass.EXTRA),
                handler("extra-b", cls = ToolClass.EXTRA),
                handler("extra-c", cls = ToolClass.EXTRA),
                handler("extra-d", cls = ToolClass.EXTRA),
                handler("extra-e", cls = ToolClass.EXTRA),
                handler("extra-f", cls = ToolClass.EXTRA),
            ),
        )
        val popularity = mapOf(
            "extra-a" to 10L,
            "extra-b" to 30L,
            "extra-c" to 30L,
            "extra-d" to 20L,
            "extra-e" to 5L,
            "extra-f" to 1L,
        )

        assertEquals(
            listOf("core", "extra-b", "extra-c", "extra-d", "extra-a", "extra-e"),
            registry.leanNames(popularity),
        )
    }

    private fun handler(
        name: String,
        category: String = "test",
        cls: ToolClass = ToolClass.EXTRA,
        heavy: Boolean = false,
    ): ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            name = name,
            zh = "$name zh",
            en = "$name en",
            category = category,
            cls = cls,
            heavy = heavy,
            schemaBuilder = { error("unused") },
        )

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject = error("unused")
    }
}

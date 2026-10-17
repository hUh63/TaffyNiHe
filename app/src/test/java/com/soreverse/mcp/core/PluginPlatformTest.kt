package com.soreverse.mcp.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件平台的纯逻辑单测。
 *
 * 目录扫描与运行需要真实 Context / Python 运行时，只能在真机验；但「版本比较、权限匹配、
 * 清单序列化」这三件事是平台判断的依据，必须在 JVM 上钉死——判错就会出现「明明不兼容却放行」
 * 或「声明了权限却调不动工具」。
 */
class PluginPlatformTest {

    @Test
    fun versionAtLeastComparesNumericallyNotLexically() {
        assertTrue(PluginManager.versionAtLeast("1.3.108", "1.3.99"))
        assertTrue(PluginManager.versionAtLeast("1.3.108", "1.3.108"))
        assertFalse(PluginManager.versionAtLeast("1.3.99", "1.3.100"))
        assertTrue("blank requirement always passes", PluginManager.versionAtLeast("1.0", ""))
    }

    @Test
    fun versionAtLeastHandlesMissingSegments() {
        assertTrue("1.4 >= 1.4.0", PluginManager.versionAtLeast("1.4", "1.4.0"))
        assertFalse("1.4 < 1.4.1", PluginManager.versionAtLeast("1.4", "1.4.1"))
        assertTrue("v prefix tolerated", PluginManager.versionAtLeast("v1.5.0", "1.4.9"))
    }

    @Test
    fun allowsToolSupportsWildcardsAndExactMatch() {
        assertTrue(PluginManager.allowsTool(listOf("tools:taffy_so_open"), "taffy_so_open"))
        assertFalse(PluginManager.allowsTool(listOf("tools:taffy_so_open"), "taffy_so_close"))
        assertTrue(PluginManager.allowsTool(listOf("tools:taffy_so_*"), "taffy_so_decompile"))
        assertFalse(PluginManager.allowsTool(listOf("tools:taffy_so_*"), "taffy_apk_analyze"))
        assertTrue(PluginManager.allowsTool(listOf("tools:*"), "anything"))
    }

    @Test
    fun allowsToolDeniesWhenNothingDeclared() {
        assertFalse("undeclared must not imply all-access", PluginManager.allowsTool(emptyList(), "taffy_so_open"))
        assertFalse(
            "file-only permission must not grant tools",
            PluginManager.allowsTool(listOf("files:read", "net:outbound"), "taffy_so_open")
        )
    }

    @Test
    fun pluginInfoJsonCarriesHealthAndIssues() {
        val info = PluginManager.PluginInfo(
            id = "demo",
            name = "Demo",
            version = "1.0",
            author = "a",
            description = "d",
            entry = "plugin.py",
            minAppVersion = "1.3.0",
            permissions = listOf("tools:taffy_so_*"),
            settingsCount = 2,
            dir = File("/tmp/demo"),
            hasEntry = false,
            issues = listOf("入口文件不存在：plugin.py"),
        )
        val json = info.toJson()
        assertEquals("demo", json.getString("id"))
        assertFalse(json.getBoolean("healthy"))
        assertEquals(1, json.getJSONArray("issues").length())
        assertEquals(1, json.getJSONArray("permissions").length())
        assertEquals(2, json.getInt("settingsCount"))
    }

    @Test
    fun healthyPluginReportsNoIssues() {
        val info = PluginManager.PluginInfo(
            id = "ok",
            name = "OK",
            version = "1.0",
            author = "",
            description = "",
            entry = "plugin.py",
            minAppVersion = "",
            permissions = listOf("tools:*"),
            settingsCount = 0,
            dir = File("/tmp/ok"),
            hasEntry = true,
            issues = emptyList(),
        )
        assertTrue(info.toJson().getBoolean("healthy"))
    }
}

package com.soreverse.mcp.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路径参数白名单的回归测试。
 *
 * 工具里凡是当「文件系统路径」用的参数，必须登记在 [WorkspacePolicy.PATH_KEYS]；
 * 漏一个就等于整条调用绕过工作区策略（能读任意文件、往任意目录写）。
 * 历史上 apkPath / dexPath / pathA / pathB 都这么漏过，这里是防回归的钉子。
 */
class WorkspacePolicyPathKeysTest {

    @Test
    fun pathLikeToolArgumentsAreRegistered() {
        val required = listOf(
            // 通用
            "path", "filePath", "dir", "workDir", "sourcePath", "targetPath",
            "outputDir", "outputFile", "outFile", "inputFile",
            // APK / DEX
            "inputApk", "outputApk", "apkPath", "dexPath", "outDex",
            // 双输入工具
            "pathA", "pathB",
            // 2026-11 补齐的三个
            "mappingPath", "saveDir", "logFile",
        )
        val missing = required.filter { it !in WorkspacePolicy.PATH_KEYS }
        assertTrue("以下路径参数未登记到 WorkspacePolicy.PATH_KEYS（会绕过工作区策略）: $missing", missing.isEmpty())
    }

    @Test
    fun nonPathKeysAreNotOverBlocked() {
        // 内容体与标识符不能被当成路径，否则正常调用会被判 PATH_OUTSIDE_WORKSPACE。
        val contentKeys = listOf("content", "hex", "base64", "name", "symbolName", "url", "query", "action")
        val wrong = contentKeys.filter { it in WorkspacePolicy.PATH_KEYS }
        assertTrue("这些不是路径，却被登记为路径键: $wrong", wrong.isEmpty())
    }
}

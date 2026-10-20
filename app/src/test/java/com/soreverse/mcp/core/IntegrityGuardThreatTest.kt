package com.soreverse.mcp.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ROM 级 ART/DEX 转储检测（FART/Ark 系）的离线规则固定测试。
 *
 * 规则拆成纯函数正是为此：两条判定都只吃「反射看到的方法名」和「build 身份字段」，
 * 因此可以在 JVM 单测里把「原版平台不报」和「被改平台必报」都钉死，不必等真机。
 */
class IntegrityGuardThreatTest {

    /**
     * ArkOS-angler-8.1 改的是平台而不是本进程：`dalvik.system.DexFile` 多出开关，
     * `android.app.ActivityThread` 多出整条管线。任一半单独出现即可判定。
     */
    @Test
    fun fartDumpSwitchOnDexFileIsAThreat() {
        assertNotNull(
            IntegrityGuard.fartDumpThreat(
                listOf("openDexFileNative", "setFartDumpEnabled", "isDexOptNeeded"),
                emptyList()
            )
        )
    }

    @Test
    fun fartDumpPipelineOnActivityThreadIsAThreat() {
        assertNotNull(
            IntegrityGuard.fartDumpThreat(
                emptyList(),
                listOf("currentActivityThread", "runFartDump", "performLaunchActivity")
            )
        )
    }

    /**
     * 原版 ActivityThread 本身就有 dump 面（`am dump` 用），那些名字不能误判；
     * 空清单（反射解析不到类）不是证据，必须保持沉默而不是误报。
     */
    @Test
    fun stockDumpSurfaceAndEmptyEvidenceAreNotThreats() {
        assertNull(
            IntegrityGuard.fartDumpThreat(
                listOf(
                    "openDexFileNative",
                    "loadDex",
                    "openInMemoryDexFile",
                    "getDexFileOutputPaths",
                    "isDexOptNeeded",
                    "dumpDexFile"
                ),
                listOf(
                    "currentActivityThread",
                    "handleDumpActivity",
                    "handleDumpProvider",
                    "handleDumpService",
                    "handleDumpHeap",
                    "handleStopBinderTrackingAndDump",
                    "dumpMemInfoTable",
                    "nDumpGraphicsInfo",
                    "performLaunchActivity"
                )
            )
        )
        assertNull(IntegrityGuard.fartDumpThreat(emptyList(), emptyList()))
    }

    /** 按成员名精确匹配，不做前缀——注入名是固定标识符，近似名不能被当成已验证的那一个。 */
    @Test
    fun fartDumpMatchIsExactNotPrefix() {
        assertNull(
            IntegrityGuard.fartDumpThreat(
                listOf("setFartDumpEnabledX", "dumpDexFileInfo"),
                listOf("runFartDumpNow", "readFartDumpSwitchesLater")
            )
        )
    }

    @Test
    fun arkUnpackRomIdentityIsAThreat() {
        assertNotNull(IntegrityGuard.arkUnpackRomThreat("Ark脱壳机(菜鸟八哥QQ2380494437)"))
        assertNotNull(
            IntegrityGuard.arkUnpackRomThreat(
                "Nexus 6P",
                "aosp_angler",
                "aosp_angler-userdebug 8.1.0 OPM8.190105.002 Ark脱壳机 v2 test-keys"
            )
        )
    }

    @Test
    fun ordinaryDeviceIdentityIsNotAThreat() {
        assertNull(
            IntegrityGuard.arkUnpackRomThreat(
                "Pixel 7",
                "aosp_angler-userdebug 8.1.0 OPM8.190105.002 eng.ubuntu.20260705.125925 test-keys",
                "Android/aosp_angler/angler:8.1.0/OPM8.190105.002/ubuntu07051259:userdebug/test-keys"
            )
        )
        assertNull(IntegrityGuard.arkUnpackRomThreat(null, null, null))
    }
}

package com.dsmcp.tool.engine

import android.util.Log
import org.json.JSONObject

/**
 * JNI bridge to the native C++ layer (libdsmcp_native.so).
 *
 * Provides format detection, ELF (.so) and PE (.dll) parsing/patching,
 * hex utilities, and byte search — all implemented in self-contained C++
 * with no external library dependencies.
 *
 * Thread-safety: the native parser creates fresh objects per call, but to
 * be safe against any future global state, all calls are serialized behind
 * a reentrant lock (same pattern as SOMCP's LiefEngine).
 */
class NativeBridge {

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadError: String = ""

    init {
        val result = runCatching { System.loadLibrary("dsmcp_native") }
        loaded = result.isSuccess
        if (!loaded) {
            loadError = result.exceptionOrNull()?.message ?: "Unknown load error"
            Log.w(TAG, "Failed to load libdsmcp_native: $loadError")
        } else {
            Log.i(TAG, "libdsmcp_native loaded successfully")
        }
    }

    fun available(): Boolean = loaded

    fun loadStatus(): String = if (loaded) "loaded" else "failed: $loadError"

    private val lock = java.util.concurrent.locks.ReentrantLock()

    private inline fun <T> serial(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    // ── Format detection ──

    fun detectFormat(data: ByteArray): String = serial {
        if (!loaded) "unknown"
        else runCatching { nativeDetectFormat(data) }.getOrDefault("unknown")
    }

    // ── Hex utilities ──

    fun toHex(data: ByteArray, spaced: Boolean = false): String = serial {
        if (!loaded) ""
        else runCatching { nativeToHex(data, spaced) }.getOrDefault("")
    }

    fun fromHex(hex: String): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeFromHex(hex) }.getOrDefault(ByteArray(0))
    }

    fun hexDump(data: ByteArray, offset: Int = 0, length: Int = 256): String = serial {
        if (!loaded) ""
        else runCatching { nativeHexDump(data, offset, length) }.getOrDefault("")
    }

    fun searchBytes(data: ByteArray, pattern: String): JSONObject = serial {
        if (!loaded) JSONObject("""{"hits":[],"count":0}""")
        else JSONObject(runCatching { nativeSearchBytes(data, pattern) }.getOrDefault("""{"hits":[],"count":0}"""))
    }

    // ── ELF (.so) operations ──

    fun elfParse(data: ByteArray): JSONObject = serial {
        JSONObject(runCatching { nativeElfParse(data) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun elfPatchVa(data: ByteArray, va: Long, patch: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeElfPatchVa(data, va, patch) }.getOrDefault(ByteArray(0))
    }

    fun elfPatchOffset(data: ByteArray, offset: Long, patch: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeElfPatchOffset(data, offset, patch) }.getOrDefault(ByteArray(0))
    }

    fun elfGetSectionContent(data: ByteArray, sectionName: String): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeElfGetSectionContent(data, sectionName) }.getOrDefault(ByteArray(0))
    }

    fun elfSetSectionContent(data: ByteArray, sectionName: String, content: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeElfSetSectionContent(data, sectionName, content) }.getOrDefault(ByteArray(0))
    }

    fun elfAddExportedFunction(data: ByteArray, addr: Long, name: String): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeElfAddExportedFunction(data, addr, name) }.getOrDefault(ByteArray(0))
    }

    fun elfRemoveSymbol(data: ByteArray, name: String): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeElfRemoveSymbol(data, name) }.getOrDefault(ByteArray(0))
    }

    fun elfRenameSymbol(data: ByteArray, oldName: String, newName: String): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeElfRenameSymbol(data, oldName, newName) }.getOrDefault(ByteArray(0))
    }

    fun elfExtractStrings(data: ByteArray, maxCount: Int = 5000): JSONObject = serial {
        JSONObject(runCatching { nativeElfExtractStrings(data, maxCount) }.getOrDefault("""{"strings":[]}"""))
    }

    fun elfVaToOffset(data: ByteArray, va: Long): Long = serial {
        if (!loaded) -1L
        else runCatching { nativeElfVaToOffset(data, va) }.getOrDefault(-1L)
    }

    // ── PE (.dll) operations ──

    fun peParse(data: ByteArray): JSONObject = serial {
        JSONObject(runCatching { nativePeParse(data) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun pePatchVa(data: ByteArray, va: Long, patch: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativePePatchVa(data, va, patch) }.getOrDefault(ByteArray(0))
    }

    fun pePatchRva(data: ByteArray, rva: Long, patch: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativePePatchRva(data, rva, patch) }.getOrDefault(ByteArray(0))
    }

    fun pePatchOffset(data: ByteArray, offset: Long, patch: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativePePatchOffset(data, offset, patch) }.getOrDefault(ByteArray(0))
    }

    fun peGetSectionContent(data: ByteArray, sectionName: String): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativePeGetSectionContent(data, sectionName) }.getOrDefault(ByteArray(0))
    }

    fun peSetSectionContent(data: ByteArray, sectionName: String, content: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativePeSetSectionContent(data, sectionName, content) }.getOrDefault(ByteArray(0))
    }

    fun peVaToOffset(data: ByteArray, va: Long): Long = serial {
        if (!loaded) -1L
        else runCatching { nativePeVaToOffset(data, va) }.getOrDefault(-1L)
    }

    fun peRvaToOffset(data: ByteArray, rva: Long): Long = serial {
        if (!loaded) -1L
        else runCatching { nativePeRvaToOffset(data, rva) }.getOrDefault(-1L)
    }

    // ── .NET (.dll) operations ──

    /** 检测文件是否为 .NET/Mono 程序集，返回详细诊断信息 */
    fun dotnetDetect(data: ByteArray): JSONObject = serial {
        JSONObject(runCatching { nativeDotnetDetect(data) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun dotnetListTypes(data: ByteArray): JSONObject = serial {
        JSONObject(runCatching { nativeDotnetListTypes(data) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun dotnetListMethods(data: ByteArray, typeFilter: Int = 0): JSONObject = serial {
        JSONObject(runCatching { nativeDotnetListMethods(data, typeFilter) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun dotnetListStrings(data: ByteArray, maxCount: Int = 5000): JSONObject = serial {
        JSONObject(runCatching { nativeDotnetListStrings(data, maxCount) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun dotnetDumpIl(data: ByteArray, methodToken: Int): JSONObject = serial {
        JSONObject(runCatching { nativeDotnetDumpIl(data, methodToken) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun dotnetDisasm(data: ByteArray, methodToken: Int, pseudoCode: Boolean = false): JSONObject = serial {
        JSONObject(runCatching { nativeDotnetDisasm(data, methodToken, pseudoCode) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun dotnetResolveToken(data: ByteArray, token: Int): JSONObject = serial {
        JSONObject(runCatching { nativeDotnetResolveToken(data, token) }.getOrDefault("""{"error":"native_unavailable"}"""))
    }

    fun dotnetEditIl(data: ByteArray, methodToken: Int, ilOffset: Int, patch: ByteArray): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeDotnetEditIl(data, methodToken, ilOffset, patch) }.getOrDefault(ByteArray(0))
    }

    fun dotnetEditString(data: ByteArray, usOffset: Int, newStr: String): ByteArray = serial {
        if (!loaded) ByteArray(0)
        else runCatching { nativeDotnetEditString(data, usOffset, newStr) }.getOrDefault(ByteArray(0))
    }

    // ── Native declarations ──

    private external fun nativeDetectFormat(data: ByteArray): String
    private external fun nativeToHex(data: ByteArray, spaced: Boolean): String
    private external fun nativeFromHex(hex: String): ByteArray
    private external fun nativeHexDump(data: ByteArray, offset: Int, length: Int): String
    private external fun nativeSearchBytes(data: ByteArray, pattern: String): String

    private external fun nativeElfParse(data: ByteArray): String
    private external fun nativeElfPatchVa(data: ByteArray, va: Long, patch: ByteArray): ByteArray
    private external fun nativeElfPatchOffset(data: ByteArray, offset: Long, patch: ByteArray): ByteArray
    private external fun nativeElfGetSectionContent(data: ByteArray, sectionName: String): ByteArray
    private external fun nativeElfSetSectionContent(data: ByteArray, sectionName: String, content: ByteArray): ByteArray
    private external fun nativeElfAddExportedFunction(data: ByteArray, addr: Long, name: String): ByteArray
    private external fun nativeElfRemoveSymbol(data: ByteArray, name: String): ByteArray
    private external fun nativeElfRenameSymbol(data: ByteArray, oldName: String, newName: String): ByteArray
    private external fun nativeElfExtractStrings(data: ByteArray, maxCount: Int): String
    private external fun nativeElfVaToOffset(data: ByteArray, va: Long): Long

    private external fun nativePeParse(data: ByteArray): String
    private external fun nativePePatchVa(data: ByteArray, va: Long, patch: ByteArray): ByteArray
    private external fun nativePePatchRva(data: ByteArray, rva: Long, patch: ByteArray): ByteArray
    private external fun nativePePatchOffset(data: ByteArray, offset: Long, patch: ByteArray): ByteArray
    private external fun nativePeGetSectionContent(data: ByteArray, sectionName: String): ByteArray
    private external fun nativePeSetSectionContent(data: ByteArray, sectionName: String, content: ByteArray): ByteArray
    private external fun nativePeVaToOffset(data: ByteArray, va: Long): Long
    private external fun nativePeRvaToOffset(data: ByteArray, rva: Long): Long

    private external fun nativeDotnetDetect(data: ByteArray): String
    private external fun nativeDotnetListTypes(data: ByteArray): String
    private external fun nativeDotnetListMethods(data: ByteArray, typeFilter: Int): String
    private external fun nativeDotnetListStrings(data: ByteArray, maxCount: Int): String
    private external fun nativeDotnetDumpIl(data: ByteArray, methodToken: Int): String
    private external fun nativeDotnetDisasm(data: ByteArray, methodToken: Int, pseudoCode: Boolean): String
    private external fun nativeDotnetResolveToken(data: ByteArray, token: Int): String
    private external fun nativeDotnetEditIl(data: ByteArray, methodToken: Int, ilOffset: Int, patch: ByteArray): ByteArray
    private external fun nativeDotnetEditString(data: ByteArray, usOffset: Int, newStr: String): ByteArray

    companion object {
        private const val TAG = "NativeBridge"
    }
}

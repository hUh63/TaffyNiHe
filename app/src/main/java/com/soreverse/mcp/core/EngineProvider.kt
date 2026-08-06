package com.soreverse.mcp.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.dsmcp.tool.engine.BinaryEngine
import com.soreverse.mcp.engine.NativeSoEngine
import java.io.File

object EngineProvider {
    @Volatile private var engine: NativeSoEngine? = null
    @Volatile private var binaryEngine: BinaryEngine? = null

    fun get(context: Context): NativeSoEngine {
        return engine ?: synchronized(this) {
            engine ?: NativeSoEngine(context.applicationContext).also { engine = it }
        }
    }

    fun getBinaryEngine(context: Context): BinaryEngine {
        return binaryEngine ?: synchronized(this) {
            binaryEngine ?: BinaryEngine(
                context.applicationContext,
                initialWorkDirPath = extractWorkDirPath(SettingsStore(context.applicationContext).treeUri),
            ).also { binaryEngine = it }
        }
    }

    fun restoreWorkDirectory(context: Context) {
        SettingsStore(context.applicationContext).treeUri?.let { uri ->
            runCatching { get(context).setWorkDirectory(uri) }
                .onFailure { AppLog.e("Failed to restore work directory", it) }
            // Also update BinaryEngine's work directory
            runCatching { getBinaryEngine(context).updateWorkDirectory(extractWorkDirPath(uri)) }
                .onFailure { AppLog.e("Failed to update BinaryEngine work directory", it) }
        }
    }

    /**
     * Called when the user selects a new work directory in the UI.
     * Updates both NativeSoEngine and BinaryEngine.
     */
    fun setWorkDirectory(context: Context, uri: Uri) {
        runCatching { get(context).setWorkDirectory(uri) }
            .onFailure { AppLog.e("Failed to set work directory", it) }
        runCatching { getBinaryEngine(context).updateWorkDirectory(extractWorkDirPath(uri)) }
            .onFailure { AppLog.e("Failed to update BinaryEngine work directory", it) }
    }

    /**
     * Best-effort extraction of a file-system path from a SAF tree URI.
     * Returns null for URIs that cannot be resolved to a path (e.g. external SD cards
     * with non-primary document IDs). BinaryEngine falls back to its default output dir.
     */
    private fun extractWorkDirPath(uri: Uri?): String? {
        if (uri == null) return null
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            when {
                docId.startsWith("primary:") -> {
                    val suffix = docId.removePrefix("primary:")
                    File(Environment.getExternalStorageDirectory(), suffix).path
                }
                docId.startsWith("raw:") -> {
                    docId.removePrefix("raw:")
                }
                else -> null
            }
        } catch (e: Exception) {
            AppLog.w("Could not extract path from tree URI: ${e.message}")
            null
        }
    }
}

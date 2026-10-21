package top.wkbin.taixu.plugin.cli

import kotlinx.serialization.json.Json
import top.wkbin.taixu.core.model.ToolManifest

internal object ManifestCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(manifest: ToolManifest): String = json.encodeToString(ToolManifest.serializer(), manifest)
}

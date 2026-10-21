package top.wkbin.taixu.harness.plugins

import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.plugin.packages.PackageSchemas

/** Shared strict dialect: package admission and actual execution enforce the same rules. */
object PluginSchemas {
    fun requireSupported(schema: JsonObject) = PackageSchemas.requireSupported(schema)
    fun valid(schema: JsonObject, arguments: JsonObject) = PackageSchemas.valid(schema, arguments)
}

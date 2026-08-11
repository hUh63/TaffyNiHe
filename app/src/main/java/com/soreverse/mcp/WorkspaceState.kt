package com.soreverse.mcp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一个分析任务：围绕一个主文件（APK 或 SO）的持续分析记录。任务记录持久化，不保存内存现场。 */
internal data class TaskRecord(
    val id: String,
    var title: String,
    var kind: String,          // "apk" | "so" | "mixed"
    var mainPath: String,      // 主文件路径（content:// 或绝对路径）
    var mainName: String,      // 主文件名
    var createdAt: Long,
    var updatedAt: Long,
    var status: String,        // "active" | "completed"
    var notes: String = "",    // 用户备注
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("kind", kind)
        .put("mainPath", mainPath).put("mainName", mainName)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("status", status).put("notes", notes)

    companion object {
        fun fromJson(o: JSONObject): TaskRecord = TaskRecord(
            id = o.optString("id"),
            title = o.optString("title"),
            kind = o.optString("kind"),
            mainPath = o.optString("mainPath"),
            mainName = o.optString("mainName"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            status = o.optString("status"),
            notes = o.optString("notes"),
        )
    }
}

/** 分析工作区 + 任务的统一状态。由 SoReverseApp remember 持有，跨页共享。 */
internal class WorkspaceState(private val context: Context) {
    // 工具页状态（六个工具的独立 UI 状态 + 共享工作区）
    val tools = ToolPagesState()

    // 当前任务
    var currentTaskId by mutableStateOf<String?>(null)

    // 所有任务（含历史）
    var tasks by mutableStateOf<List<TaskRecord>>(emptyList())

    // 当前活跃工具（分析页左侧选中）；默认不选，展开工具列表后由用户选择
    var activeTool by mutableStateOf("")

    private val storeFile: File = File(context.filesDir, "analysis-tasks.json")

    init {
        loadTasks()
    }

    fun currentTask(): TaskRecord? = tasks.firstOrNull { it.id == currentTaskId }

    private fun loadTasks() {
        tasks = runCatching {
            val root = JSONObject(storeFile.readText())
            val arr = root.optJSONArray("tasks") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { TaskRecord.fromJson(it) } }
        }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }
        currentTaskId = tasks.firstOrNull { it.status == "active" }?.id
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            storeFile.writeText(JSONObject().put("tasks", arr).toString())
        }
    }

    /** 新建或切换到某个任务。 */
    fun useTask(task: TaskRecord) {
        currentTaskId = task.id
        if (tasks.none { it.id == task.id }) {
            tasks = listOf(task) + tasks
        }
        persist()
    }

    /** 新建任务（围绕主文件）。 */
    fun createTask(title: String, kind: String, mainPath: String, mainName: String): TaskRecord {
        val now = System.currentTimeMillis()
        val task = TaskRecord(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { mainName },
            kind = kind,
            mainPath = mainPath,
            mainName = mainName,
            createdAt = now,
            updatedAt = now,
            status = "active",
        )
        currentTaskId = task.id
        tasks = listOf(task) + tasks
        persist()
        return task
    }

    /** 标记任务完成。 */
    fun completeTask(id: String) {
        tasks = tasks.map { if (it.id == id) it.copy(status = "completed", updatedAt = System.currentTimeMillis()) else it }
        persist()
    }

    /** 清除（删除）任务。 */
    fun deleteTask(id: String) {
        tasks = tasks.filterNot { it.id == id }
        if (currentTaskId == id) currentTaskId = null
        persist()
    }

    /** 继续未完成任务：切回该任务并设为当前。 */
    fun continueTask(id: String) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        currentTaskId = id
        tasks = tasks.map { if (it.id == id) it.copy(status = "active", updatedAt = System.currentTimeMillis()) else it }
        persist()
    }
}
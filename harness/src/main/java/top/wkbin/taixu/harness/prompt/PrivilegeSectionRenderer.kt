package top.wkbin.taixu.harness.prompt

import android.content.Context
import top.wkbin.taixu.core.model.ExecutionMode
import top.wkbin.taixu.runtime.privilege.PrivilegeManager
import top.wkbin.taixu.harness.R

/**
 * 系统提示词中"执行权限章节"的渲染端口。
 *
 * 独立成接口是为了让 SystemPromptBuilder 的单元测试可以注入固定行为，
 * 而不必构造完整的 PrivilegeManager 运行时依赖。
 */
fun interface PrivilegeSectionRenderer {
    suspend fun render(): String
}

/** 默认实现：根据当前 PrivilegeManager 状态选择 PROOT / Shizuku / Root 章节。 */
class DefaultPrivilegeSectionRenderer(
    private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val promptAssets: PromptAssetLoader,
) : PrivilegeSectionRenderer {

    override suspend fun render(): String {
        val privilegeInfo = runCatching { privilegeManager.getPrivilegeInfo() }.getOrNull()
        return when {
            privilegeInfo == null ->
                context.getString(R.string.harness_prompt_privilege_unavailable)
            privilegeInfo.mode == ExecutionMode.PROOT || !privilegeInfo.modeActive ->
                context.getString(R.string.harness_prompt_privilege_proot)
            privilegeInfo.mode == ExecutionMode.SHIZUKU ->
                promptAssets.render("prompts/privilege_shizuku.md")
            else ->
                promptAssets.render("prompts/privilege_root.md")
        }
    }
}

package com.soreverse.mcp.mcp.agent

/**
 * 多智能体编排的角色定义。
 *
 * 每个角色只被授予「回答这一类问题所需的最小工具集」——不是为了让模型少干活，而是为了让
 * 它别跑偏：给侦察代理一堆 patch 工具，它就会开始改文件；给代码代理整个 APK 工具面，它就会
 * 反复 open 同一个包。工具白名单本身就是一层约束。
 *
 * 三个字段是契约：
 *  - [toolNames]：只允许调这些工具（在 [AgentKernel.catalogTools] 处硬过滤，模型看不到其它工具）
 *  - [systemPrompt]：角色边界 + 证据要求（不许臆测、必须给出地址/偏移/符号）
 *  - [evidenceHint]：最后必须输出的证据字段，供编排器汇总时结构化消费
 */
internal data class AgentRole(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val goalZh: String,
    val goalEn: String,
    val toolNames: List<String>,
    val suggestedSteps: Int,
    val evidenceHint: List<String>,
) {
    fun name(zh: Boolean): String = if (zh) nameZh else nameEn
    fun goal(zh: Boolean): String = if (zh) goalZh else goalEn
}

internal object AgentRoles {

    /** 侦察：先把目标的形状摸清（是 APK 还是 SO、什么架构、有没有壳、多大）。 */
    val RECON = AgentRole(
        id = "recon",
        nameZh = "侦察",
        nameEn = "Recon",
        goalZh = "确定目标的基本形状：文件类型/架构/入口/段布局/依赖库/是否加壳，为后续分工提供事实基础。",
        goalEn = "Establish the target's shape: file type, architecture, entry, section layout, dependencies, packing status.",
        toolNames = listOf(
            "taffy_so_open",
            "taffy_analyze_elf",
            "taffy_read_stats",
            "taffy_apk_analyze",
            "taffy_meta_info",
        ),
        suggestedSteps = 8,
        evidenceHint = listOf("architecture", "entry", "sectionCount", "packed", "neededLibraries"),
    )

    /** 字符串与常量：找可疑字符串、交叉引用、加密字符串、导入来源。 */
    val STRINGS = AgentRole(
        id = "strings",
        nameZh = "字符串与常量",
        nameEn = "Strings",
        goalZh = "从字符串侧取证：可疑 URL/密钥/协议/路径字符串、它们的交叉引用、加密字符串段、以及导入符号的来源库。",
        goalEn = "Gather string-side evidence: suspicious URLs/keys/paths, their xrefs, encrypted blobs, and import provenance.",
        toolNames = listOf(
            "taffy_search_strings",
            "taffy_string_xref",
            "taffy_so_string_decrypt",
            "taffy_so_import_trace",
            "taffy_read_hexdump",
        ),
        suggestedSteps = 12,
        evidenceHint = listOf("string", "address", "xref", "confidence"),
    )

    /** 代码层：函数清单、反汇编、伪 C、签名、JNI 注册表。 */
    val CODE = AgentRole(
        id = "code",
        nameZh = "代码层",
        nameEn = "Code",
        goalZh = "在代码层定位关键实现：可疑函数、反汇编片段、伪 C 结构、函数签名与 JNI 注册表。",
        goalEn = "Locate key implementations at the code level: suspicious functions, disassembly, pseudo-C, signatures, JNI tables.",
        toolNames = listOf(
            "taffy_analyze_functions",
            "taffy_read_disasm",
            "taffy_so_decompile",
            "taffy_so_func_sig",
            "taffy_so_jni_register",
        ),
        suggestedSteps = 14,
        evidenceHint = listOf("function", "address", "signature", "snippet"),
    )

    /** 控制流：CFG、交叉引用图、混淆度、去平坦化。 */
    val FLOW = AgentRole(
        id = "flow",
        nameZh = "控制流",
        nameEn = "Control flow",
        goalZh = "从控制流侧判断：调用关系、控制流图形态、是否被平坦化/加假边，以及能否还原。",
        goalEn = "Judge from control flow: call relations, CFG shape, flattening/bogus edges, and recoverability.",
        toolNames = listOf(
            "taffy_analyze_cfg",
            "taffy_analyze_xrefs",
            "taffy_so_obfusc_scan",
            "taffy_so_deflatten",
        ),
        suggestedSteps = 12,
        evidenceHint = listOf("function", "verdict", "blockCount", "xref"),
    )

    /** 加固与脱壳。 */
    val PACKER = AgentRole(
        id = "packer",
        nameZh = "加固与脱壳",
        nameEn = "Packer",
        goalZh = "判断加固方案与可脱壳路径：壳厂商/证据、反调试点、是否需要 dump 与修复。",
        goalEn = "Determine the protector and a viable unpacking path: vendor/evidence, anti-debug sites, dump and repair needs.",
        toolNames = listOf(
            "taffy_so_packer_fingerprint",
            "taffy_so_anti_debug",
            "taffy_apk_shell_check",
            "taffy_unpack",
            "taffy_so_import_trace",
        ),
        suggestedSteps = 10,
        evidenceHint = listOf("vendor", "confidence", "evidence", "nextStep"),
    )

    /** 汇总：没有工具，只做交叉印证与结论写作。 */
    val REPORT = AgentRole(
        id = "report",
        nameZh = "汇总",
        nameEn = "Report",
        goalZh = "把各子代理的证据交叉印证，写成结论：能确定什么、不确定什么、下一步做什么。",
        goalEn = "Cross-check all sub-agent evidence into a conclusion: what is established, what is not, what to do next.",
        toolNames = emptyList(),
        suggestedSteps = 2,
        evidenceHint = listOf("conclusion", "openQuestions"),
    )

    val ALL: List<AgentRole> = listOf(RECON, STRINGS, CODE, FLOW, PACKER, REPORT)

    private val byId: Map<String, AgentRole> = ALL.associateBy { it.id }

    fun byId(id: String): AgentRole? = byId[id.trim().lowercase()]

    fun ids(): List<String> = ALL.map { it.id }

    /** 每个角色的系统提示词。刻意写得短而硬：只讲边界与证据要求。 */
    fun systemPrompt(role: AgentRole, zh: Boolean, workspaceId: String): String {
        val toolList = if (role.toolNames.isEmpty()) {
            if (zh) "（你没有工具，只做综合与写作）" else "(no tools; synthesis only)"
        } else {
            role.toolNames.joinToString(", ")
        }
        return if (zh) {
            """
            你是塔菲逆核的专职取证子代理「${role.nameZh}」。
            职责边界：${role.goalZh}
            可用工具：$toolList
            当前工作区：${if (workspaceId.isBlank()) "（无，先 taffy_so_open 打开目标）" else workspaceId}

            硬性要求：
            1) 只用工具拿到的事实说话。不许根据文件名、后缀、常见经验臆测结论。
            2) 每条结论必须带可复核的定位：地址（0x…）、符号名、段名、或工具返回的字段路径。
            3) 工具失败就说明失败原因并换路径，不要假装成功。
            4) 不做超出职责的事：不修改文件、不执行目标代码、不做别的角色该做的事。
            5) 最后单独输出一行以 EVIDENCE: 开头的 JSON 数组，每项含 role/claim/where/confidence(high|medium|low)。
               证据不足就少写几条，不要凑数。
            """.trimIndent()
        } else {
            """
            You are the dedicated forensic sub-agent "synthesis-free ${role.nameEn}" of TaffyNiHe.
            Scope: ${role.goalEn}
            Tools: $toolList
            Workspace: ${if (workspaceId.isBlank()) "(none; open the target first)" else workspaceId}

            Hard rules:
            1) Only state facts obtained through tools. Never infer from names or common practice.
            2) Every claim needs a checkable location: address (0x...), symbol, section, or a tool field path.
            3) If a tool fails, say why and take another route. Do not pretend success.
            4) Stay in scope: no file modification, no target execution, no other role's work.
            5) End with a single line starting with EVIDENCE: containing a JSON array of
               {role, claim, where, confidence(high|medium|low)}. Fewer, well-grounded items beat padding.
            """.trimIndent()
        }
    }

    /** 子任务提示词。 */
    fun taskPrompt(role: AgentRole, task: String, zh: Boolean, context: String): String = if (zh) {
        buildString {
            appendLine("任务：$task")
            if (context.isNotBlank()) {
                appendLine()
                appendLine("编排器提供的前置结论（可直接引用，但要自己核实关键点）：")
                appendLine(context)
            }
            appendLine()
            appendLine("请按「先取证、再归纳」的顺序工作，最后给出该任务的结论与 EVIDENCE 行。")
        }.trimEnd()
    } else {
        buildString {
            appendLine("Task: $task")
            if (context.isNotBlank()) {
                appendLine()
                appendLine("Upstream findings from the orchestrator (cite them, but verify the key points):")
                appendLine(context)
            }
            appendLine()
            appendLine("Work evidence-first, then synthesise. End with the conclusion and the EVIDENCE line.")
        }.trimEnd()
    }
}

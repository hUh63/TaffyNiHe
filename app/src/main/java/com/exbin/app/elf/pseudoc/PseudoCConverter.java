package com.exbin.app.elf.pseudoc;

import com.exbin.app.elf.DisassembledInstruction;
import com.exbin.app.elf.FunctionInfo;

import java.util.List;

/**
 * 伪 C 反汇编接口.
 * <p>
 * 设计原则:
 * - 输入:函数基本信息 + 反汇编指令列表 + 上下文(机器, 调用约定等)
 * - 输出:人类可读的伪 C 文本(一行行字符串)
 * - 实现方式:基于指令助记符和操作数的模式匹配 + 调用约定推断
 * <p>
 * 后续要支持更复杂的反编译器时,只需要新增实现并在
 * {@link PseudoCRegistry#create} 里选择即可,无需修改 UI 层.
 */
public interface PseudoCConverter {

    /**
     * 把一个函数的反汇编指令转换为伪 C 文本.
     *
     * @param ctx 函数反汇编上下文
     * @return 伪 C 文本(已分行的字符串列表)
     */
    List<String> convert(PseudoCContext ctx);

    /** 该实现的名字(用于 UI 显示) */
    String name();

    /**
     * 上下文:提供给转换器所需的所有信息.
     */
    final class PseudoCContext {
        public String functionName;
        public long functionAddress;
        public long functionSize;
        public int machine;          // ElfConstants.EM_*
        public boolean isThumb;      // ARM32 Thumb 模式
        public List<DisassembledInstruction> instructions;
        public java.util.Map<Long, String> labels; // addr -> 名字
        public java.util.Map<Long, String> imports; // 跳转目标 -> 导入符号名

        // ── v2.9.35: 符号表 + 类型推断扩展 ──

        /** 函数名 → C 签名字符串 (如 "void* malloc(size_t)"), 由 NativeBridge.lookupKnownSig 或 restoreSignatures 填充 */
        public java.util.Map<String, String> signatureMap;

        /** 函数地址 → FunctionInfo (native 结构化签名已还原), r2dec 调用点参数对齐 (v4.6) */
        public java.util.Map<Long, FunctionInfo> signatureByAddr;

        /** 地址 → 符号名 (统一: 本地函数+导入+导出+数据标签), 用于解析 adrp+add 全局变量和 sub_XXXX 调用 */
        public java.util.Map<Long, String> symbolMap;

        /** 字符串地址 → 内容 (r2 Csj 等价物), 用于 marker math find_string (adrp+add 解析出字符串字面量) */
        public java.util.Map<Long, String> stringMap;

        /** afvj 局部变量声明 [type, name] (r2 风格命名如 var_10h), 来自 NativeBridge.buildAfvj 的 SP/BP 项 */
        public java.util.List<String[]> afvjLocals;

        /** 全局变量地址 → 类型字符串 (如 "int", "void*", "pthread_mutex_t"), 由 NativeBridge.analyzeSoDataNative 填充 */
        public java.util.Map<Long, String> globalVarTypes;

        /** 全局变量地址 → 符号名 (如 "g_config", "g_lock"), 由 ELF 符号表/DataLabels 填充 */
        public java.util.Map<Long, String> globalVarNames;

        /** 当前函数的预还原签名 (如 "int JNI_OnLoad(JavaVM* vm, void* reserved)"), 由 nativeRestoreSignatures 填充 */
        public String funcSignature;

        // ── v3.3.1: Native 结构化签名 (与 funcSignature 同源, 结构化形态) ──
        //    由 nativeRestoreSignaturesStructured 填充, r2dec 直接消费, 不再 parse 字符串
        public String nativeRetType;
        public java.util.List<String> nativeParamTypes;
        public java.util.List<String> nativeParamNames;
        public boolean[] nativeParamFloat;
        public boolean[] nativeParamWide;
        public int[] nativeParamStackOffsets;
        public boolean nativeNoreturn;
        public boolean nativeVariadic;
        public boolean nativeIsStatic;

        /** mangled 名 → demangled 名映射, 由 NativeBridge.demangle 填充 */
        public java.util.Map<String, String> demangledNames;

        /** v3.1: SO 文件路径, 用于 C++ native ELF 符号解析 (PLT/导入表/symtab) */
        public String soPath;

        /** 塔菲扩展: 由 Kotlin 侧用 rizin(agfj) 预构建的控制流图; 非空时跳过 native CFA。 */
        public com.exbin.app.elf.ControlFlowAnalyzer.CFG prebuiltCfg;
        // ── v3.3: aflj 数据模型 — 函数统计 (bbs/callrefs/datarefs) ──
        public int bbCount;
        public int callRefs;
        public int dataRefs;

        /** v3.3.2: 与 instructions 等长的不可达标记 (noreturn 传播), true = 死代码, r2dec 置 IrInsn.valid=false */
        public boolean[] noreturnSuppressed;

        // ── v4.1: so 分析工具已产出数据的完整接入 ──

        /** 重定位位置 → 符号名 (rOffset→symbolName), 来自 ElfFile.relocations; GOT/数据地址解析用 */
        public java.util.Map<Long, String> relocSymbols;

        /** 依赖库列表 (DT_NEEDED), 来自 ElfFile.neededLibraries */
        public java.util.List<String> neededLibraries;

        /** 指令地址 → 字符串内容 (StringReferenceAnalyzer 反向索引), findString 精确命中优先 */
        public java.util.Map<Long, String> insnStringRefs;

        /** 数据常量地址 → [type, value], 来自 NativeBridge.dataConstants; 全局常量值注释用 */
        public java.util.Map<Long, String[]> dataConstants;

        /** 虚表条目 (含槽位函数名), 来自 NativeBridge.vtableEntries; 间接调用美化用 */
        public com.exbin.app.nativebridge.NativeBridge.VTableEntryNative[] vtableEntries;

        /** 函数地址 → 调用点最大寄存器参数数 (callerMap 简化), 签名缺失时参数个数兜底 */
        public java.util.Map<Long, Integer> callerMaxRegs;

        public PseudoCContext(String functionName, long functionAddress, long functionSize,
                              int machine, boolean isThumb,
                              List<DisassembledInstruction> instructions) {
            this.functionName = functionName;
            this.functionAddress = functionAddress;
            this.functionSize = functionSize;
            this.machine = machine;
            this.isThumb = isThumb;
            this.instructions = instructions;
        }
    }
}

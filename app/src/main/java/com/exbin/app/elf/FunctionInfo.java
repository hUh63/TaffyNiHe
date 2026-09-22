package com.exbin.app.elf;

import java.util.List;

/**
 * 函数信息(带反汇编)
 */
public class FunctionInfo {

    public String name;          // 函数名 (demangled / user-renamed)
    /** 原始符号名 (mangled C++, 或 .dynsym 原始名). v2.0.8 用于 "复制原始符号" 按钮. */
    public String rawName;
    public long address;         // 函数起始地址
    public long size;            // 函数大小(字节)
    public String sectionName;   // 所属节区
    public List<DisassembledInstruction> instructions; // 反汇编结果
    /** 函数来源标记 (v2.0.9 起 native 函数识别后仅作展示; 原 LinearSweepAnalyzer 已删). */
    public static final int SOURCE_SYMTAB = 0;
    public static final int SOURCE_LINEARSWEEP = 1;
    public int source = 0;       // 0 = 符号表, 1 = 线性扫描
    public boolean isThumb;      // ARM32 模式下指示是否为 Thumb 函数
    /** v2.0.8: 标记此函数是否为导入函数 (PLT / .dynsym) — UI 层会显示不同标签. */
    public boolean isImport;
    /** v2.7.4: 批量还原的函数签名缓存 (null = 未分析, "" = 分析失败, 其他 = 签名字符串) */
    public String restoredSignature;
    // ── v3.3.1: 结构化还原结果 (native 层结构化通道直接产出, 供 r2dec 消费, 不再重复解析字符串) ──
    /** native 分析返回类型 (null = 未提供) */
    public String restoredRetType;
    /** native 分析参数类型列表 (与 restoredParamFloat/Wide/StackOffsets 下标对齐) */
    public List<String> restoredParamTypes;
    /** native 分析参数名 (可能为 null/空) */
    public List<String> restoredParamNames;
    /** 参数是否浮点 (与 restoredParamTypes 对齐, null = 未提供) */
    public boolean[] restoredParamFloat;
    /** 参数是否宽类型 double/long long (null = 未提供) */
    public boolean[] restoredParamWide;
    /** 栈参数偏移 (寄存器参数为 -1; null = 未提供) */
    public int[] restoredParamStackOffsets;
    /** 是否 noreturn (native 交叉引用分析) */
    public boolean restoredNoreturn;
    /** 是否可变参数 (...), 如 printf 族 */
    public boolean restoredVariadic;
    /** 是否静态 (无 this 指针) */
    public boolean restoredIsStatic;
    // ── v3.3: aflj 数据模型 (r2 aflj 等价字段) ──
    /** 基本块数量 (详情页反汇编后由 bbLeader 计数填充; 列表页为 0) */
    public int bbCount;
    /** 被调用次数 (call/jump xref 指向本函数, native 统计) */
    public int callRefs;
    /** 数据引用次数 (ref xref 指向本函数, native 统计) */
    public int dataRefs;

    public FunctionInfo(String name, long address, long size, String sectionName) {
        this.name = name;
        this.rawName = name;
        this.address = address;
        this.size = size;
        this.sectionName = sectionName;
    }
}
package com.exbin.app.elf;

import java.util.List;

/**
 * 保存完整 ELF 解析结果的数据类
 */
public class ElfFile {

    public String filePath;
    /** v2.9.16: 原始文件 URI (用于写回修改到源 SO 文件, 而非只写缓存副本) */
    public String sourceUri;
    /** v2.6.4: 是否为轻量模式解析（跳过了字符串/匿名函数/xref） */
    public boolean lightMode;
    /** v2.6.5: native 解析器 handle，供分页查询使用；Activity 销毁时释放 */
    public long nativeHandle;
    public ElfHeader header;
    public List<SectionHeader> sectionHeaders;
    public List<ProgramHeader> programHeaders;
    public List<SymbolEntry> symtabEntries;
    public List<SymbolEntry> dynsymEntries;
    public List<DynamicEntry> dynamicEntries;
    public List<String> neededLibraries;
    public List<ExtractedString> strings;
    public List<RelocationEntry> relocations;
    public List<FunctionInfo> functions;
    public List<ImportedFunction> imports;
    /** v3.3.2: 字符串引用分析缓存 (r2 aar 等价) — 字符串地址 → 引用列表; 由 DetailListTabFragment 首次查询时后台构建 */
    public transient java.util.Map<Long, java.util.List<com.exbin.app.elf.StringReferenceAnalyzer.StringReference>> stringRefs;
    /** v2.8.58: 数据段分析结果 — 常量 (预加载, native 原始结果) */
    public com.exbin.app.nativebridge.NativeBridge.ConstantEntryNative[] dataConstants;
    /** v2.8.58: 数据段分析结果 — 全局变量 (预加载, native 原始结果) */
    public com.exbin.app.nativebridge.NativeBridge.GlobalVarEntryNative[] dataGlobals;
    /** v2.8.58: 签名还原是否已完成 */
    public boolean signaturesRestored;

    /** v3.2.8: 虚表缓存 — 由 UI 层填充 (native 侧 ensureVTablesScanned 已有缓存,
     *  Java 侧字段用于跨页面复用, 避免重复 JNI) */
    public com.exbin.app.nativebridge.NativeBridge.VTableEntryNative[] vtableEntries;

    /** v3.2.7: 数据段分析是否已执行 (native 内存已有结果, 数据标签页直接分页查询,
     *  不再重复 analyzeSoDataByHandle — 修: 全量分析后打开数据页重复分析漏洞) */
    public boolean dataAnalysisDone;
    /** v2.0.9: caller-side 索引: 被调函数地址 → 调用列表 (供 FunctionSignatureAnalyzer 用) */
    public java.util.Map<Long, java.util.List<com.exbin.app.elf.FunctionSignatureAnalyzer.CallerInfo>> callerMap;

    /** v2.9.31: Native 层数据标签 (地址→名称映射, 来自符号表+重定位+字符串+数据段扫描) */
    public com.exbin.app.nativebridge.NativeBridge.DataLabelNative[] dataLabels;
}
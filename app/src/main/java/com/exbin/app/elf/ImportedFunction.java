package com.exbin.app.elf;

/**
 * 导入函数 (PLT) 信息:来自 .plt / .rela.plt 与 .dynsym/.dynstr/.gnu.hash 解析.
 */
public class ImportedFunction {

    /** 已知库函数签名 (如 "void* malloc(size_t)", 可选). */
    public String signature;

    /** PLT 桩函数 (或 .got.plt 条目) 的运行时地址. */
    public long pltAddress;

    /** PLT 桩函数大小(按节区粒度). */
    public long pltSize;

    /** 解析后的函数名(外部符号名). */
    public String name;

    /** 所属节区 (.plt, .plt.sec, .plt.got 等). */
    public String section;

    /** 重定位表中的原始 offset (.got / .got.plt). */
    public long gotOffset;

    /** .rela.plt / .rel.plt 的原始 r_offset. */
    public long relocOffset;

    /** 重定位类型 (R_xxx_JUMP_SLOT 等). */
    public String relocType;

    /** 来自哪个 .so 依赖 (DT_NEEDED),可空. */
    public String fromLibrary;

    /** PLT 节区原始字节(可选,用于反汇编). */
    public byte[] pltBytes;
}

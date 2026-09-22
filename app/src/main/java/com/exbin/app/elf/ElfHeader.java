package com.exbin.app.elf;

/**
 * ELF 文件头
 * 支持 32 位和 64 位 ELF 格式
 */
public class ElfHeader {

    public int eiClass;       // ELFCLASS32 或 ELFCLASS64
    public int eiData;        // 编码方式 (LSB/MSB)
    public int eiVersion;     // ELF 版本
    public int eiOsabi;       // 目标操作系统 ABI
    public int eiAbiVersion; // ABI 版本
    public int eType;         // 文件类型
    public int eMachine;      // 目标架构
    public int eVersion;      // 文件版本
    public long eEntry;       // 程序入口地址
    public long ePhoff;       // 程序头表偏移
    public long eShoff;       // 节区头表偏移
    public int eFlags;        // 处理器特定标志
    public int eEhsize;       // ELF 头大小
    public int ePhentsize;    // 程序头表项大小
    public int ePhnum;        // 程序头表项数量
    public int eShentsize;    // 节区头表项大小
    public int eShnum;        // 节区头表项数量
    public int eShstrndx;     // 节区名称字符串表索引

    public boolean is64Bit() {
        return eiClass == ElfConstants.ELFCLASS64;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("========== ELF Header ==========\n");
        sb.append(String.format("%-24s: %s\n", "类别", is64Bit() ? "ELF64 (64位)" : "ELF32 (32位)"));
        sb.append(String.format("%-24s: %s\n", "编码", eiData == ElfConstants.ELFDATA2LSB ? "小端 (LSB)" : "大端 (MSB)"));
        sb.append(String.format("%-24s: %d\n", "EI 版本", eiVersion));
        sb.append(String.format("%-24s: %d\n", "ABI", eiOsabi));
        sb.append(String.format("%-24s: %d\n", "ABI 版本", eiAbiVersion));
        sb.append(String.format("%-24s: %s\n", "文件类型", ElfConstants.getFileTypeName(eType)));
        sb.append(String.format("%-24s: %s\n", "目标架构", ElfConstants.getMachineName(eMachine)));
        sb.append(String.format("%-24s: %d\n", "文件版本", eVersion));
        sb.append(String.format("%-24s: 0x%x\n", "入口地址", eEntry));
        sb.append(String.format("%-24s: %d (0x%x)\n", "程序头偏移", ePhoff, ePhoff));
        sb.append(String.format("%-24s: %d (0x%x)\n", "节区头偏移", eShoff, eShoff));
        sb.append(String.format("%-24s: 0x%x\n", "处理器标志", eFlags));
        sb.append(String.format("%-24s: %d bytes\n", "ELF 头大小", eEhsize));
        sb.append(String.format("%-24s: %d bytes\n", "程序头项大小", ePhentsize));
        sb.append(String.format("%-24s: %d\n", "程序头项数量", ePhnum));
        sb.append(String.format("%-24s: %d bytes\n", "节区头项大小", eShentsize));
        sb.append(String.format("%-24s: %d\n", "节区头项数量", eShnum));
        sb.append(String.format("%-24s: %d\n", "节名字符串表索引", eShstrndx));
        sb.append("================================\n");
        return sb.toString();
    }
}
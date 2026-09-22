package com.exbin.app.elf;

/**
 * ELF 程序头 (Program Header / Segment)
 */
public class ProgramHeader {

    public int pType;        // 段类型
    public long pFlags;      // 段标志 (64位文件时为int)
    public long pOffset;     // 在文件中的偏移
    public long pVaddr;      // 虚拟地址
    public long pPaddr;      // 物理地址
    public long pFilesz;     // 文件中的大小
    public long pMemsz;      // 内存中的大小
    public long pAlign;      // 对齐要求

    /**
     * 获取 flags 的可读描述
     */
    public String getFlagsDescription() {
        StringBuilder sb = new StringBuilder();
        if ((pFlags & 0x4) != 0) sb.append("R");
        else sb.append("-");
        if ((pFlags & 0x2) != 0) sb.append("W");
        else sb.append("-");
        if ((pFlags & 0x1) != 0) sb.append("X");
        else sb.append("-");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("%-16s 0x%06x 0x%08x 0x%08x  %6d  %6d  %s",
                ElfConstants.getProgramTypeName(pType),
                pOffset,
                pVaddr,
                pPaddr,
                pFilesz,
                pMemsz,
                getFlagsDescription());
    }
}
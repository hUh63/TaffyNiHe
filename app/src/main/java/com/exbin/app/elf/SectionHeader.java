package com.exbin.app.elf;

/**
 * ELF 节区头 (Section Header)
 */
public class SectionHeader {

    public int shName;       // 节区名称(字符串表偏移)
    public int shType;       // 节区类型
    public long shFlags;     // 节区标志
    public long shAddr;      // 内存中的虚拟地址
    public long shOffset;    // 文件中的偏移
    public long shSize;      // 节区大小
    public int shLink;       // 关联的节区索引
    public int shInfo;       // 额外信息
    public long shAddralign; // 对齐要求
    public long shEntsize;   // 固定项大小时的项大小

    public String name;     // 由解析时从字符串表回填

    /**
     * 获取 flags 的可读描述
     */
    public String getFlagsDescription() {
        StringBuilder sb = new StringBuilder("0x");
        sb.append(String.format("%x", shFlags));
        if (shFlags == 0) return sb.toString();

        sb.append(" (");
        boolean first = true;
        if ((shFlags & 0x1) != 0) { sb.append("WRITE"); first = false; }
        if ((shFlags & 0x2) != 0) { if (!first) sb.append(","); sb.append("ALLOC"); first = false; }
        if ((shFlags & 0x4) != 0) { if (!first) sb.append(","); sb.append("EXEC"); }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("[%02d] %-18s %-16s %8d 0x%06x 0x%06x  %s",
                shName,
                name != null ? name : "(unnamed)",
                ElfConstants.getSectionTypeName(shType),
                shSize,
                shAddr,
                shOffset,
                getFlagsDescription());
    }
}
package com.exbin.app.elf;

/**
 * 重定位条目(.rel / .rela)
 */
public class RelocationEntry {

    public long rOffset;   // 重定位位置(虚拟地址)
    public long rInfo;     // 符号索引和重定位类型
    public long rAddend;   // 附加常量(仅 .rela 格式)

    // 由解析时回填
    public String typeName;
    public String symbolName;
    public int symbolIndex;

    /**
     * 从 rInfo 提取重定位类型(低 8 位,64 位文件为低 32 位)
     */
    public int getType(boolean is64Bit) {
        if (is64Bit) {
            return (int) (rInfo & 0xffffffffL);
        } else {
            return (int) (rInfo & 0xff);
        }
    }

    /**
     * 从 rInfo 提取符号表索引
     */
    public int getSymbolIndex(boolean is64Bit) {
        if (is64Bit) {
            return (int) ((rInfo >>> 32) & 0xffffffffL);
        } else {
            return (int) ((rInfo >>> 8) & 0xffffff);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("0x%08x  %-10s  %s",
                rOffset,
                typeName != null ? typeName : "",
                symbolName != null ? symbolName : ""));
        if (rAddend != 0) {
            sb.append(String.format(" + 0x%x", rAddend));
        }
        return sb.toString();
    }
}
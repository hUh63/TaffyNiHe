package com.exbin.app.elf;

/**
 * 符号表条目
 */
public class SymbolEntry {

    public int stName;       // 符号名称(字符串表偏移)
    public int stInfo;       // 类型和绑定属性
    public int stOther;      // 保留
    public int stShndx;      // 相关的节区索引
    public long stValue;     // 符号值(地址)
    public long stSize;      // 符号大小

    public String name;     // 由解析时从字符串表回填

    public int getBind() {
        return stInfo >> 4;
    }

    public int getType() {
        return stInfo & 0xf;
    }

    public String getBindName() {
        switch (getBind()) {
            case ElfConstants.STB_LOCAL:  return "LOCAL";
            case ElfConstants.STB_GLOBAL: return "GLOBAL";
            case ElfConstants.STB_WEAK:   return "WEAK";
            default:                      return "UNKNOWN";
        }
    }

    public String getTypeName() {
        switch (getType()) {
            case ElfConstants.STT_NOTYPE:  return "NOTYPE";
            case ElfConstants.STT_OBJECT:  return "OBJECT";
            case ElfConstants.STT_FUNC:    return "FUNC";
            case ElfConstants.STT_SECTION: return "SECTION";
            case ElfConstants.STT_FILE:    return "FILE";
            default:                       return "UNKNOWN";
        }
    }

    @Override
    public String toString() {
        return String.format("%-6s %-8s %-40s 0x%08x  %6d",
                getBindName(), getTypeName(),
                name != null ? name : "",
                stValue, stSize);
    }
}
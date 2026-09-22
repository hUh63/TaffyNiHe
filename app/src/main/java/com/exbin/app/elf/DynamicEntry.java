package com.exbin.app.elf;

/**
 * 动态段条目
 */
public class DynamicEntry {

    public long dTag;   // 标记
    public long dVal;   // 值(可能是地址或数值)

    public String valueName; // 如果是指向字符串表的值,回填字符串

    @Override
    public String toString() {
        String tagName = ElfConstants.getDynamicTagName(dTag);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-14s  0x%08x", tagName, dVal));
        if (valueName != null && !valueName.isEmpty()) {
            sb.append("  (").append(valueName).append(")");
        }
        return sb.toString();
    }
}
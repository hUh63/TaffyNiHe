package com.exbin.app.elf;

import java.util.List;

/**
 * 提取出的可打印字符串 (v2.0: 加上原始字节 + 引用列表).
 */
public class ExtractedString {

    public long offset;        // 字符串在文件中的偏移
    public long address;       // 字符串的虚拟地址(如果可知)
    public String value;       // 字符串内容
    public String sectionName; // 所属节区
    public String encoding;    // 推测的编码: ASCII / UTF-8 / GBK / Latin-1 / UTF-16LE / UTF-16BE
    public byte[] bytes;       // 字符串在 ELF 中的原始字节 (含尾部 0)
    public List<String> references;  // 引用此字符串的指令列表 (跨 arm32/arm64 通用)

    public ExtractedString(long offset, long address, String value, String sectionName) {
        this.offset = offset;
        this.address = address;
        this.value = value;
        this.sectionName = sectionName;
    }

    @Override
    public String toString() {
        return String.format("0x%08x: %s", offset, value);
    }
}

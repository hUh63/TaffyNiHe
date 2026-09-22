package com.exbin.app.elf;

/**
 * 单条反汇编指令
 */
public class DisassembledInstruction {

    public long address;          // 指令地址
    /**
     * 原始字节.
     * <p>
     * v2.0.4 修复 OOM 根因: 标记 transient 不进 GSON 缓存.
     * 原因: 大型 SO (例如 libminecraftpe.so 36MB) 解析后,ElfFile 持有上百万条
     * DisassembledInstruction, 所有 bytes 累积就是 .text 段大小. Gson 一次性
     * 序列化整棵对象图到 String 会在 Java heap 触发 30-40MB 分配,堆紧的设备
     * 直接 OOM: "Failed to allocate a 37748752 byte allocation with 1280...".
     * 修复后: 缓存只存 (address, mnemonic, opStr, category, references),
     * 不存 bytes 原始字节. 需原始字节时由 NativeBridge 重新反汇编当前指令拿到.
     */
    public transient byte[] bytes;          // 原始字节 (不参与 GSON 缓存)
    public String mnemonic;       // 助记符
    public String opStr;          // 操作数

    /** 引用的字符串内容(如果这条指令是 LDR/ADR/ADRP + ADD 等加载字符串指针的指令) */
    public String referencedString;

    /** 引用字符串在文件中的虚地址 */
    public long referencedStringAddress;

    /** 类别: BRANCH / CALL / RETURN / LDR / STR / ARITH / LOGIC / MOV / CMP / SYS / OTHER */
    public String category;

    /** 指令所在 so 文件绝对路径 (用于 ElfPatcher 写入) */
    public String filePath;

    /** 指令在 so 文件中的字节偏移 (用于 ElfPatcher 写入) */
    public long fileOffset;

    /** v2.8.75: 是否为 Thumb 指令 (ARM32) — 用于汇编编辑时正确选择 Keystone 模式 */
    public boolean isThumb;

    /** v2.9.28: 是否为基本块入口 (由 native 层递归下降反汇编时检测) */
    public boolean bbLeader;

    /** v2.9.30: IDA 风格注释 (由 DisasmAnnotator 填充, 如 "; g_datDoc" 或 "; \"money\"") */
    public String annotation;

    /** v2.9.30: 增强操作数 (如 ADRP 目标符号名, 由 DisasmAnnotator 填充) */
    public String enhancedOpStr;

    /** v2.9.30: 栈帧变量名 (如 "var_8", 由 DisasmAnnotator 填充) */
    public String stackVar;

    /** v2.9.33: native 层 loc_XXXX 标签 (分支目标) */
    public String nativeLocLabel;

    /** v2.9.33: native 层寄存器类型注释 (如 "int", "void *", "this") */
    public String nativeRegType;

    /** v2.9.33: native 层增强操作数 (如分支目标显示 loc_XXXX) */
    public String nativeEnhancedOp;

    /** v2.9.33: native 层栈帧变量名 (由 capstone detail 计算, 100%准确) */
    public String nativeStackVar;

    /** v2.9.34: native 层计算的目标地址 (ADRP+ADD/LDR 融合, 或 ARM32 PC相对) */
    public long nativeTargetAddr;

    /** v3.3.2: 数据行标记 — 地址不在可执行节区, 按数据 (.word/.dword) 显示而非指令 */
    public transient boolean isData;

    public DisassembledInstruction(long address, byte[] bytes, String mnemonic, String opStr) {
        this.address = address;
        this.bytes = bytes;
        this.mnemonic = mnemonic;
        this.opStr = opStr;
        this.category = classify(mnemonic, opStr);
    }

    public DisassembledInstruction setStringRef(String content, long stringAddr) {
        this.referencedString = content;
        this.referencedStringAddress = stringAddr;
        return this;
    }

    /** 写入上下文 (文件路径 + 文件偏移) */
    public DisassembledInstruction setFileLocation(String path, long offset) {
        this.filePath = path;
        this.fileOffset = offset;
        return this;
    }

    public String getBytesHex() {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b & 0xff));
        }
        return sb.toString().trim();
    }

    /** 助记符小写 */
    public String mnemonicLower() {
        return mnemonic == null ? "" : mnemonic.toLowerCase();
    }

    /** 是否为分支跳转 */
    public boolean isBranch() {
        String m = mnemonicLower();
        if (m.isEmpty()) return false;
        if (m.equals("b") || m.equals("b.eq") || m.equals("b.ne")
                || m.equals("b.gt") || m.equals("b.ge") || m.equals("b.lt") || m.equals("b.le")
                || m.equals("b.hi") || m.equals("b.ls") || m.equals("b.hs") || m.equals("b.lo")
                || m.equals("bcc") || m.equals("bcs") || m.equals("beq") || m.equals("bne")
                || m.equals("bpl") || m.equals("bmi") || m.equals("bvc") || m.equals("bvs")
                || m.equals("cbz") || m.equals("cbnz") || m.equals("tbz") || m.equals("tbnz")
                || m.equals("bl") || m.equals("blx") || m.equals("bx") || m.equals("br") || m.equals("blr")
                || m.equals("ret")) return true;
        return false;
    }

    /** 是否为函数调用 */
    public boolean isCall() {
        String m = mnemonicLower();
        return m.equals("bl") || m.equals("blr") || m.equals("blx");
    }

    /** 是否为函数返回 */
    public boolean isReturn() {
        String m = mnemonicLower();
        return m.equals("ret") || m.equals("bx") && opStr != null && opStr.contains("lr");
    }

    /** 类别标签 (返回 category 字段, 兼容老代码) */
    public String category() {
        if (category == null) {
            category = classify(mnemonic, opStr);
        }
        return category;
    }

    /** 内部: 根据助记符+操作数分类 */
    private static String classify(String mnemonic, String opStr) {
        if (mnemonic == null) return "OTHER";
        String m = mnemonic.toLowerCase();
        if (m.isEmpty()) return "OTHER";
        if (m.equals("ret") || m.equals("bx") && opStr != null && opStr.contains("lr")) return "RETURN";
        if (m.equals("bl") || m.equals("blr") || m.equals("blx")) return "CALL";
        if (m.equals("b") || m.equals("b.eq") || m.equals("b.ne")
                || m.equals("b.gt") || m.equals("b.ge") || m.equals("b.lt") || m.equals("b.le")
                || m.equals("b.hi") || m.equals("b.ls") || m.equals("b.hs") || m.equals("b.lo")
                || m.equals("bcc") || m.equals("bcs") || m.equals("beq") || m.equals("bne")
                || m.equals("bpl") || m.equals("bmi") || m.equals("bvc") || m.equals("bvs")
                || m.equals("cbz") || m.equals("cbnz") || m.equals("tbz") || m.equals("tbnz")
                || m.equals("br")) return "BRANCH";
        if (m.startsWith("ldr") || m.startsWith("ldur") || m.startsWith("ldp") || m.startsWith("ldnp")
                || m.startsWith("ldrb") || m.startsWith("ldrh") || m.startsWith("ldrsb") || m.startsWith("ldrsh")
                || m.startsWith("ldrsw") || m.startsWith("ldx") || m.startsWith("ldax") || m.equals("ldar")
                || m.equals("pop") || m.equals("adr") || m.equals("adrp") || m.equals("lea")) return "LDR";
        if (m.startsWith("str") || m.startsWith("stur") || m.startsWith("stp") || m.startsWith("stnp")
                || m.startsWith("strb") || m.startsWith("strh") || m.startsWith("stx") || m.equals("stlx")
                || m.equals("push")) return "STR";
        if (m.equals("mov") || m.equals("movz") || m.equals("movk") || m.equals("movn") || m.equals("movw") || m.equals("movt")
                || m.equals("mvn") || m.equals("vmov") || m.equals("xchg")) return "MOV";
        if (m.equals("add") || m.equals("adds") || m.equals("sub") || m.equals("subs")
                || m.equals("mul") || m.equals("muls") || m.equals("div") || m.equals("udiv") || m.equals("sdiv")
                || m.equals("adc") || m.equals("sbc") || m.equals("rsb") || m.equals("mla")
                || m.equals("neg") || m.equals("negs") || m.equals("inc") || m.equals("dec")) return "ARITH";
        if (m.equals("and") || m.equals("orr") || m.equals("eor") || m.equals("bic") || m.equals("orn")
                || m.equals("lsl") || m.equals("lsr") || m.equals("asr") || m.equals("ror")
                || m.equals("xor") || m.equals("shl") || m.equals("shr") || m.equals("mvn")) return "LOGIC";
        if (m.equals("cmp") || m.equals("cmn") || m.equals("tst") || m.equals("teq") || m.equals("test")) return "CMP";
        if (m.equals("nop") || m.equals("svc") || m.equals("bkpt") || m.equals("hvc") || m.equals("smc")
                || m.equals("dmb") || m.equals("dsb") || m.equals("isb") || m.equals("mrs") || m.equals("msr")
                || m.equals("sys") || m.equals("syscall") || m.equals("int") || m.equals("cli") || m.equals("sti")
                || m.equals("hlt") || m.equals("yield") || m.equals("wfe") || m.equals("wfi") || m.equals("sev")
                || m.equals("cps") || m.equals("eret")) return "SYS";
        return "OTHER";
    }

    @Override
    public String toString() {
        return String.format("0x%08x:  %-24s  %-8s %s",
                address, getBytesHex(), mnemonic, opStr != null ? opStr : "");
    }
}

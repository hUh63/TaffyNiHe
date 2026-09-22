package com.exbin.app.elf;

/**
 * ELF 常量定义
 */
public final class ElfConstants {

    private ElfConstants() {}

    // ----- 文件类型 (e_type) -----
    public static final int ET_NONE = 0;
    public static final int ET_REL = 1;
    public static final int ET_EXEC = 2;
    public static final int ET_DYN = 3;
    public static final int ET_CORE = 4;

    // ----- 目标机器 (e_machine) -----
    public static final int EM_NONE = 0;
    public static final int EM_386 = 3;
    public static final int EM_ARM = 40;
    public static final int EM_X86_64 = 62;
    public static final int EM_AARCH64 = 183;
    public static final int EM_RISCV = 243;

    // ----- ELF 类别 -----
    public static final int ELFCLASS32 = 1;
    public static final int ELFCLASS64 = 2;

    // ----- 编码方式 -----
    public static final int ELFDATA2LSB = 1;
    public static final int ELFDATA2MSB = 2;

    // ----- 节区类型 (sh_type) -----
    public static final int SHT_NULL = 0;
    public static final int SHT_PROGBITS = 1;
    public static final int SHT_SYMTAB = 2;
    public static final int SHT_STRTAB = 3;
    public static final int SHT_RELA = 4;
    public static final int SHT_HASH = 5;
    public static final int SHT_DYNAMIC = 6;
    public static final int SHT_NOTE = 7;
    public static final int SHT_NOBITS = 8;
    public static final int SHT_REL = 9;
    public static final int SHT_DYNSYM = 11;
    public static final int SHT_INIT_ARRAY = 14;
    public static final int SHT_FINI_ARRAY = 15;

    // ----- 节区标志 (sh_flags) -----
    public static final long SHF_WRITE = 0x1;
    public static final long SHF_ALLOC = 0x2;
    public static final long SHF_EXECINSTR = 0x4;

    // ----- 段类型 (p_type) -----
    public static final int PT_NULL = 0;
    public static final int PT_LOAD = 1;
    public static final int PT_DYNAMIC = 2;
    public static final int PT_INTERP = 3;
    public static final int PT_NOTE = 4;
    public static final int PT_PHDR = 6;
    public static final int PT_GNU_EH_FRAME = 0x6474e550;
    public static final int PT_GNU_STACK = 0x6474e551;
    public static final int PT_GNU_RELRO = 0x6474e552;

    // ----- 段权限 (p_flags) -----
    public static final long PF_X = 0x1;
    public static final long PF_W = 0x2;
    public static final long PF_R = 0x4;

    // ----- 动态标记 (d_tag) -----
    public static final int DT_NULL = 0;
    public static final int DT_NEEDED = 1;
    public static final int DT_PLTRELSZ = 2;
    public static final int DT_PLTGOT = 3;
    public static final int DT_HASH = 4;
    public static final int DT_STRTAB = 5;
    public static final int DT_SYMTAB = 6;
    public static final int DT_RELA = 7;
    public static final int DT_RELASZ = 8;
    public static final int DT_RELAENT = 9;
    public static final int DT_STRSZ = 10;
    public static final int DT_SYMENT = 11;
    public static final int DT_INIT = 12;
    public static final int DT_FINI = 13;
    public static final int DT_SONAME = 14;
    public static final int DT_RPATH = 15;
    public static final int DT_REL = 17;
    public static final int DT_RELSZ = 18;
    public static final int DT_RELENT = 19;
    public static final int DT_GNU_HASH = 0x6ffffef5;

    // ----- 符号绑定 (st_info bind) -----
    public static final int STB_LOCAL = 0;
    public static final int STB_GLOBAL = 1;
    public static final int STB_WEAK = 2;

    // ----- 符号类型 (st_info type) -----
    public static final int STT_NOTYPE = 0;
    public static final int STT_OBJECT = 1;
    public static final int STT_FUNC = 2;
    public static final int STT_SECTION = 3;
    public static final int STT_FILE = 4;

    // ----- 魔法数字 -----
    public static final byte[] ELF_MAGIC = {0x7f, 'E', 'L', 'F'};

    /**
     * 获取文件类型名称
     */
    public static String getFileTypeName(int type) {
        switch (type) {
            case ET_NONE: return "NONE (无类型)";
            case ET_REL:  return "REL (可重定位文件)";
            case ET_EXEC: return "EXEC (可执行文件)";
            case ET_DYN:  return "DYN (共享目标文件 .so)";
            case ET_CORE: return "CORE (核心转储文件)";
            default:      return String.format("0x%x", type);
        }
    }

    /**
     * 获取机器类型名称
     */
    public static String getMachineName(int machine) {
        switch (machine) {
            case EM_NONE:   return "None";
            case EM_386:    return "Intel 80386 (x86)";
            case EM_ARM:    return "ARM (32-bit)";
            case EM_X86_64: return "AMD x86-64";
            case EM_AARCH64:return "AArch64 (ARM64)";
            case EM_RISCV:  return "RISC-V";
            default:        return String.format("0x%x", machine);
        }
    }

    /**
     * 获取节区类型名称
     */
    public static String getSectionTypeName(int type) {
        switch (type) {
            case SHT_NULL:     return "NULL";
            case SHT_PROGBITS: return "PROGBITS";
            case SHT_SYMTAB:   return "SYMTAB";
            case SHT_STRTAB:   return "STRTAB";
            case SHT_RELA:     return "RELA";
            case SHT_HASH:     return "HASH";
            case SHT_DYNAMIC:  return "DYNAMIC";
            case SHT_NOTE:     return "NOTE";
            case SHT_NOBITS:   return "NOBITS";
            case SHT_REL:      return "REL";
            case SHT_DYNSYM:   return "DYNSYM";
            default:           return String.format("0x%x", type);
        }
    }

    /**
     * 获取段类型名称
     */
    public static String getProgramTypeName(int type) {
        switch (type) {
            case PT_NULL:    return "NULL";
            case PT_LOAD:    return "LOAD";
            case PT_DYNAMIC: return "DYNAMIC";
            case PT_INTERP:  return "INTERP";
            case PT_NOTE:    return "NOTE";
            case PT_PHDR:    return "PHDR";
            default:
                if (type == PT_GNU_EH_FRAME) return "GNU_EH_FRAME";
                if (type == PT_GNU_STACK)    return "GNU_STACK";
                if (type == PT_GNU_RELRO)    return "GNU_RELRO";
                return String.format("0x%x", type);
        }
    }

    /**
     * 获取动态标记名称
     */
    public static String getDynamicTagName(long tag) {
        if (tag == DT_NULL) return "NULL";
        if (tag == DT_NEEDED) return "NEEDED";
        if (tag == DT_PLTRELSZ) return "PLTRELSZ";
        if (tag == DT_PLTGOT) return "PLTGOT";
        if (tag == DT_HASH) return "HASH";
        if (tag == DT_STRTAB) return "STRTAB";
        if (tag == DT_SYMTAB) return "SYMTAB";
        if (tag == DT_RELA) return "RELA";
        if (tag == DT_RELASZ) return "RELASZ";
        if (tag == DT_RELAENT) return "RELAENT";
        if (tag == DT_STRSZ) return "STRSZ";
        if (tag == DT_SYMENT) return "SYMENT";
        if (tag == DT_INIT) return "INIT";
        if (tag == DT_FINI) return "FINI";
        if (tag == DT_SONAME) return "SONAME";
        if (tag == DT_RPATH) return "RPATH";
        if (tag == DT_REL) return "REL";
        if (tag == DT_RELSZ) return "RELSZ";
        if (tag == DT_RELENT) return "RELENT";
        if (tag == DT_GNU_HASH) return "GNU_HASH";
        return String.format("0x%x", tag);
    }
}
package capstone;

import capstone.api.Disassembler;
import capstone.api.Instruction;
import capstone.jni.FastDisassembler;

/**
 * 逆核 fork（capstone 4.0 → 5.0.9）。
 *
 * 上游 zhkl0228 的绑定有两条实现：JNA 版（本类原来的实现，直接读 C 结构体）与 JNI 版
 * （{@link capstone.jni.FastDisassembler} + {@code libdisassembler.so}，在 C 侧包含
 * {@code <capstone/capstone.h>}）。两者在 capstone 4→5 的 ABI 破坏性升级
 * （{@code cs_insn.bytes} 16→24、{@code cs_detail.regs_read} 12→20 且新增 {@code writeback}、
 * {@code cs_arm64_op}/{@code cs_arm} 字段变动）下都必须逐字段重对齐；且 JNA 版
 * 的 {@code mapToUnicornReg} 本来就是恒等空实现（没有寄存器映射表），而 JNI 版由
 * {@code reg_mapping.c} 提供完整映射。
 *
 * 因此本 fork 只保留 **一份结构知识**：把 {@code Capstone} 改为直接转发到 JNI 绑定。
 * 这样 unidbg 的 {@code DisassemblerFactory} 两条路径（FastDisassembler / 本类）行为一致，
 * 也不会再有「JNA 结构体没跟上 C 结构体」的隐患。
 */
public class Capstone implements Disassembler {

    /** arm/arm64 的 OpInfo 继承此标记接口，保持与上游绑定的二进制兼容。 */
    public interface OpInfo {}

    // Capstone API version（与 libcapstone.so 5.0.x 对齐）
    public static final int CS_API_MAJOR = 5;
    public static final int CS_API_MINOR = 0;

    // architectures
    public static final int CS_ARCH_ARM = 0;
    public static final int CS_ARCH_ARM64 = 1;
    public static final int CS_ARCH_MIPS = 2;
    public static final int CS_ARCH_X86 = 3;
    public static final int CS_ARCH_PPC = 4;
    public static final int CS_ARCH_SPARC = 5;
    public static final int CS_ARCH_SYSZ = 6;
    public static final int CS_ARCH_XCORE = 7;
    public static final int CS_ARCH_M68K = 8;
    public static final int CS_ARCH_TMS320C64X = 9;
    public static final int CS_ARCH_M680X = 10;
    public static final int CS_ARCH_MAX = 11;
    public static final int CS_ARCH_ALL = 0xFFFF; // query id for cs_support()

    // disasm mode
    public static final int CS_MODE_LITTLE_ENDIAN = 0;
    public static final int CS_MODE_ARM = 0;              // 32-bit ARM
    public static final int CS_MODE_16 = 1 << 1;          // 16-bit mode for X86
    public static final int CS_MODE_32 = 1 << 2;          // 32-bit mode for X86
    public static final int CS_MODE_64 = 1 << 3;          // 64-bit mode for X86, PPC
    public static final int CS_MODE_THUMB = 1 << 4;       // ARM's Thumb mode, including Thumb-2
    public static final int CS_MODE_MCLASS = 1 << 5;      // ARM's Cortex-M series
    public static final int CS_MODE_V8 = 1 << 6;          // ARMv8 A32 encodings for ARM
    public static final int CS_MODE_MICRO = 1 << 4;       // MicroMips mode (Mips arch)
    public static final int CS_MODE_MIPS3 = 1 << 5;
    public static final int CS_MODE_MIPS32R6 = 1 << 6;
    public static final int CS_MODE_MIPS2 = 1 << 7;
    public static final int CS_MODE_BIG_ENDIAN = 1 << 31;
    public static final int CS_MODE_V9 = 1 << 4;
    public static final int CS_MODE_MIPS32 = CS_MODE_32;
    public static final int CS_MODE_MIPS64 = CS_MODE_64;
    public static final int CS_MODE_QPX = 1 << 4;

    // Capstone error
    public static final int CS_ERR_OK = 0;
    public static final int CS_ERR_MEM = 1;
    public static final int CS_ERR_ARCH = 2;
    public static final int CS_ERR_HANDLE = 3;
    public static final int CS_ERR_CSH = 4;
    public static final int CS_ERR_MODE = 5;
    public static final int CS_ERR_OPTION = 6;
    public static final int CS_ERR_DETAIL = 7;
    public static final int CS_ERR_MEMSETUP = 8;
    public static final int CS_ERR_VERSION = 9;
    public static final int CS_ERR_DIET = 10;
    public static final int CS_ERR_SKIPDATA = 11;
    public static final int CS_ERR_X86_ATT = 12;
    public static final int CS_ERR_X86_INTEL = 13;

    // Capstone option type / value
    public static final int CS_OPT_SYNTAX = 1;
    public static final int CS_OPT_DETAIL = 2;
    public static final int CS_OPT_MODE = 3;
    public static final int CS_OPT_OFF = 0;
    public static final int CS_OPT_SYNTAX_INTEL = 1;
    public static final int CS_OPT_SYNTAX_ATT = 2;
    public static final int CS_OPT_ON = 3;
    public static final int CS_OPT_SYNTAX_NOREGNAME = 3;

    // 通用操作数类型 / 访问类型
    public static final int CS_OP_INVALID = 0;
    public static final int CS_OP_REG = 1;
    public static final int CS_OP_IMM = 2;
    public static final int CS_OP_MEM = 3;
    public static final int CS_OP_FP = 4;
    public static final int CS_AC_INVALID = 0;
    public static final int CS_AC_READ = 1 << 0;
    public static final int CS_AC_WRITE = 1 << 1;

    // 通用指令组
    public static final int CS_GRP_INVALID = 0;
    public static final int CS_GRP_JUMP = 1;
    public static final int CS_GRP_CALL = 2;
    public static final int CS_GRP_RET = 3;
    public static final int CS_GRP_INT = 4;
    public static final int CS_GRP_IRET = 5;
    public static final int CS_GRP_PRIVILEGE = 6;

    // query id for cs_support()
    public static final int CS_SUPPORT_DIET = CS_ARCH_ALL + 1;
    public static final int CS_SUPPORT_X86_REDUCE = CS_ARCH_ALL + 2;

    private final FastDisassembler delegate;

    public Capstone(int arch, int mode) {
        this.delegate = new FastDisassembler(arch, mode);
    }

    /** 与 libcapstone.so 的 cs_version() 一致的组合版本号（major&lt;&lt;8 | minor）。 */
    public int version() {
        return (CS_API_MAJOR << 8) + CS_API_MINOR;
    }

    @Override
    public Instruction[] disasm(byte[] code, long address) {
        return delegate.disasm(code, address);
    }

    @Override
    public Instruction[] disasm(byte[] code, long address, long count) {
        return delegate.disasm(code, address, count);
    }

    @Override
    public void setDetail(boolean on) {
        delegate.setDetail(on);
    }

    @Override
    public void close() {
        delegate.close();
    }
}

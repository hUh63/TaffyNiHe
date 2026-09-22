package com.exbin.app.elf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 字符串引用分析器基类 - v2.1 增强版.
 * <p>
 * 目标:对于一段函数内的反汇编指令,识别哪些指令是 "加载字符串常量地址" 的指令,
 * 并把目标字符串内容关联回指令,方便 UI 高亮.
 * <p>
 * 两种主流 ABI:
 * <ul>
 *   <li>ARM32 (含 Thumb):LDR Rx, =str  +  字面量池 (literal pool)</li>
 *   <li>ARM64 (AArch64):ADRP Xn, page  +  ADD Xn, Xn, #:lo12:str  或 LDR/STR</li>
 * </ul>
 * <p>
 * v2.1 改进:
 * <ul>
 *   <li>添加字符串引用统计和报告功能</li>
 *   <li>支持批量分析多个函数</li>
 *   <li>优化内存使用，支持大文件分析</li>
 *   <li>添加调试日志支持</li>
 * </ul>
 */
public abstract class StringReferenceAnalyzer {

    /**
     * 分析结果统计类
     */
    public static class AnalysisStats {
        public int totalFunctions = 0;
        public int analyzedFunctions = 0;
        public int totalReferences = 0;
        public int uniqueStrings = 0;
        public long analysisTimeMs = 0;
        public Map<String, Integer> methodCounts = new HashMap<>();

        @Override
        public String toString() {
            return String.format(
                "AnalysisStats{functions=%d/%d, refs=%d, unique=%d, time=%dms, methods=%s}",
                analyzedFunctions, totalFunctions, totalReferences, uniqueStrings, 
                analysisTimeMs, methodCounts);
        }
    }

    public enum Architecture { ARM32, ARM64, X86, X86_64, UNKNOWN }

    public static class SectionInfo {
        public final String name;
        public final long addr;        // 虚地址 (sh_addr)
        public final long size;        // sh_size
        public final long fileOff;     // 文件偏移 (sh_offset),0 表示不可读(如 NOBITS)
        public SectionInfo(String name, long addr, long size, long fileOff) {
            this.name = name;
            this.addr = addr;
            this.size = size;
            this.fileOff = fileOff;
        }
        /** 兼容旧 API:默认 fileOff = 0 */
        public SectionInfo(String name, long addr, long size) {
            this(name, addr, size, 0);
        }
    }

    public static class StringReference {
        public long insnAddress;          // 引用指令地址
        public long stringAddress;       // 字符串虚地址
        public String stringContent;     // 字符串内容
        public String functionName;      // 所在函数
        public String instruction;       // 完整汇编
        public String method;            // "LDR+literal" / "ADRP+ADD" / "ADRP+LDR" / "ADR"

        public String referenceType() { return method; }
    }

    protected final SectionInfo[] sections;
    protected final byte[] fileData;          // v2.0.3 兼容: 仍接受 byte[], 但优先用 mmap reader
    protected final ElfFileReader reader;     // v2.0.3: mmap 后端, 优先用, 避免 OOM
    protected final int machineType;
    protected final Architecture arch;
    protected long rodataStart = 0, rodataEnd = 0;

    /** 由指令地址索引:哪些指令被关联了字符串 */
    protected final Map<Long, StringReference> refsByInsnAddr = new HashMap<>();
    /** 由字符串虚地址索引:哪些指令引用了它 */
    protected final Map<Long, List<StringReference>> refsByStringAddr = new HashMap<>();
    /** 调试日志开关 */
    protected boolean debugLogging = false;
    /** 分析统计 */
    protected AnalysisStats stats = new AnalysisStats();

    /** v2.0.3: mmap reader 构造 (推荐, 大文件不会 OOM). */
    protected StringReferenceAnalyzer(SectionInfo[] sections, ElfFileReader reader, int machineType, Architecture arch) {
        this.sections = sections == null ? new SectionInfo[0] : sections;
        this.reader = reader;
        this.fileData = null;
        this.machineType = machineType;
        this.arch = arch;
        initRodataRange();
    }

    /** v2.0.3 兼容: 旧的 byte[] 构造. 不推荐, 大文件会 OOM. */
    protected StringReferenceAnalyzer(SectionInfo[] sections, byte[] fileData, int machineType, Architecture arch) {
        this.sections = sections == null ? new SectionInfo[0] : sections;
        this.fileData = fileData;
        this.reader = null;
        this.machineType = machineType;
        this.arch = arch;
        initRodataRange();
    }

    private void initRodataRange() {
        for (SectionInfo s : this.sections) {
            if (s.name == null) continue;
            // v2.3: 支持更多数据节名
            if (s.name.contains(".rodata") || s.name.contains(".data.rel.ro")
                    || s.name.contains(".data") || s.name.contains(".bss")) {
                if (s.addr < rodataStart || rodataStart == 0) rodataStart = s.addr;
                long end = s.addr + s.size;
                if (end > rodataEnd) rodataEnd = end;
            }
        }
    }

    // v2.0.3: 统一的字节访问 (auto-route)
    protected final int u(int fileOff) {
        if (reader != null) return reader.u(fileOff);
        if (fileData == null || fileOff < 0 || fileOff >= fileData.length) return 0;
        return fileData[fileOff] & 0xff;
    }

    protected final long getDataSize() {
        if (reader != null) return reader.size();
        return fileData == null ? 0 : fileData.length;
    }

    public Architecture getArchitecture() { return arch; }
    public String getArchitectureName() { return arch.name(); }

    public Map<Long, StringReference> refsByInsnAddr() { return refsByInsnAddr; }
    public Map<Long, List<StringReference>> refsByStringAddr() { return refsByStringAddr; }
    public AnalysisStats getStats() { return stats; }

    /** 设置调试日志开关 */
    public void setDebugLogging(boolean enabled) { this.debugLogging = enabled; }

    /** 在函数反汇编结果上做字符串引用分析,把结果回填到 instruction.referencedString */
    public void analyze(FunctionInfo func, List<DisassembledInstruction> insns) {
        if (func == null || insns == null || insns.isEmpty()) return;
        // 先清空
        refsByInsnAddr.clear();
        refsByStringAddr.clear();
        stats = new AnalysisStats();
        long startTime = System.currentTimeMillis();
        // 调用架构特定分析
        analyzeArch(func, insns);
        stats.analysisTimeMs = System.currentTimeMillis() - startTime;
        stats.totalReferences = refsByInsnAddr.size();
        stats.uniqueStrings = refsByStringAddr.size();
    }

    /**
     * 批量分析多个函数
     * @param functions 函数列表
     * @param insnMap 函数对应的指令列表映射
     * @return 合并的字符串引用映射
     */
    public Map<Long, List<StringReference>> analyzeAll(
            FunctionInfo[] functions,
            Map<FunctionInfo, List<DisassembledInstruction>> insnMap) {
        refsByInsnAddr.clear();
        refsByStringAddr.clear();
        stats = new AnalysisStats();
        stats.totalFunctions = functions != null ? functions.length : 0;

        long startTime = System.currentTimeMillis();

        if (functions != null && insnMap != null) {
            for (FunctionInfo func : functions) {
                List<DisassembledInstruction> insns = insnMap.get(func);
                if (insns != null && !insns.isEmpty()) {
                    analyzeArch(func, insns);
                    stats.analyzedFunctions++;
                }
            }
        }

        stats.analysisTimeMs = System.currentTimeMillis() - startTime;
        stats.totalReferences = refsByInsnAddr.size();
        stats.uniqueStrings = refsByStringAddr.size();

        return new HashMap<>(refsByStringAddr);
    }

    protected abstract void analyzeArch(FunctionInfo func, List<DisassembledInstruction> insns);

    /** 调试日志输出 */
    protected void logDebug(String msg) {
        if (debugLogging) {
            System.out.println("[StringRef] " + msg);
        }
    }

    /** 调试日志输出 */
    protected void logDebug(String format, Object... args) {
        if (debugLogging) {
            System.out.printf("[StringRef] " + format + "%n", args);
        }
    }

    // ==================== 公共工具方法 ====================

    protected long vaddrToFileOffset(long vaddr) {
        if (sections == null) return -1;
        for (SectionInfo s : sections) {
            if (vaddr >= s.addr && vaddr < s.addr + s.size) {
                // 实际 fileOff = s.fileOff + (vaddr - s.addr)
                return s.fileOff + (vaddr - s.addr);
            }
        }
        return -1;
    }

    protected String readCString(byte[] data, int fileOff, int maxLen) {
        if (fileOff < 0) return null;
        if (reader != null) {
            // v2.0.3: 走 mmap reader
            if (fileOff >= reader.size()) return null;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            long o = fileOff;
            while (o < reader.size() && n < maxLen) {
                int b = reader.u(o++);
                if (b == 0) break;
                if (b >= 0x20 && b <= 0x7e) {
                    sb.append((char) b);
                } else {
                    if (n == 0) return null;
                    break;
                }
                n++;
            }
            String s = sb.toString();
            return s.length() >= 1 ? s : null;
        }
        if (data == null || fileOff >= data.length) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        int o = fileOff;
        while (o < data.length && n < maxLen) {
            byte b = data[o++];
            if (b == 0) break;
            if (b >= 0x20 && b <= 0x7e) {
                sb.append((char) b);
            } else {
                // 非可打印字符也允许(短串),但开头不能是非可打印
                if (n == 0) return null;
                break;
            }
            n++;
        }
        String s = sb.toString();
        return s.length() >= 1 ? s : null;
    }

    protected int readInt32LE(byte[] data, int fileOff) {
        if (fileOff < 0) return 0;
        if (reader != null) {
            if (fileOff + 4 > reader.size()) return 0;
            return reader.u(fileOff)
                    | (reader.u(fileOff + 1) << 8)
                    | (reader.u(fileOff + 2) << 16)
                    | (reader.u(fileOff + 3) << 24);
        }
        if (data == null || fileOff + 4 > data.length) return 0;
        return (data[fileOff] & 0xff)
                | ((data[fileOff + 1] & 0xff) << 8)
                | ((data[fileOff + 2] & 0xff) << 16)
                | ((data[fileOff + 3] & 0xff) << 24);
    }

    protected long readInt64LE(byte[] data, int fileOff) {
        if (fileOff < 0) return 0;
        if (reader != null) {
            if (fileOff + 8 > reader.size()) return 0;
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v |= ((long) reader.u(fileOff + i)) << (i * 8);
            }
            return v;
        }
        if (data == null || fileOff + 8 > data.length) return 0;
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (data[fileOff + i] & 0xff)) << (i * 8);
        }
        return v;
    }

    protected long signExtend(long v, int bits) {
        long mask = 1L << (bits - 1);
        if ((v & mask) != 0) v |= -1L << bits;
        return v;
    }

    /** 从字符串中提取十六进制值(兼容 0x 前缀, # 前缀, :lo12:) */
    protected long extractHexValue(String s) {
        if (s == null) return 0;
        int loIdx = s.indexOf(":lo12:");
        if (loIdx >= 0) s = s.substring(loIdx + 6);
        int idx = s.indexOf("0x");
        if (idx < 0) idx = s.indexOf("0X");
        if (idx < 0) {
            // 纯数字(十进制)也兼容
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-') digits.append(c);
                else if (digits.length() > 0) break;
            }
            if (digits.length() == 0) return 0;
            try { return Long.parseLong(digits.toString()); } catch (Exception e) { return 0; }
        }
        boolean negative = idx > 0 && s.charAt(idx - 1) == '-';
        int start = idx + 2;
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) end++;
            else break;
        }
        if (end > start) {
            try {
                long v = Long.parseUnsignedLong(s.substring(start, end), 16);
                return negative ? -v : v;
            } catch (Exception e) { return 0; }
        }
        return 0;
    }

    protected String escapeString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\"", "\\\"");
    }

    protected void addReference(StringReference ref) {
        if (ref == null) return;
        refsByInsnAddr.put(ref.insnAddress, ref);
        List<StringReference> list = refsByStringAddr.get(ref.stringAddress);
        if (list == null) {
            list = new ArrayList<>();
            refsByStringAddr.put(ref.stringAddress, list);
        }
        list.add(ref);
        // 更新统计
        stats.methodCounts.merge(ref.method, 1, Integer::sum);
    }

    /**
     * 获取所有引用的字符串内容列表（去重）
     */
    public List<String> getAllStringContents() {
        List<String> result = new ArrayList<>();
        for (List<StringReference> refs : refsByStringAddr.values()) {
            if (!refs.isEmpty()) {
                result.add(refs.get(0).stringContent);
            }
        }
        return result;
    }

    /**
     * 根据字符串内容查找引用
     */
    public List<StringReference> findReferencesByContent(String content) {
        List<StringReference> result = new ArrayList<>();
        for (StringReference ref : refsByInsnAddr.values()) {
            if (ref.stringContent != null && ref.stringContent.equals(content)) {
                result.add(ref);
            }
        }
        return result;
    }

    /**
     * 检查地址是否在 .rodata 范围内.
     * v2.3: 放宽检查, 支持 .rodata.* / .data / .data.rel.ro / .bss 等节中的字符串.
     */
    protected boolean isInRodata(long addr) {
        if (addr <= 0) return false;
        // 如果有明确的 rodata 范围, 优先使用
        if (rodataStart != 0 && addr >= rodataStart && addr < rodataEnd) return true;
        // 否则检查是否在任意已知节中
        if (sections == null) return false;
        for (SectionInfo s : sections) {
            if (s == null) continue;
            if (addr >= s.addr && addr < s.addr + s.size) {
                // 排除代码段和动态段
                if (s.name != null && (s.name.contains(".text") || s.name.contains(".plt")
                        || s.name.contains(".got") || s.name.contains(".dynamic")
                        || s.name.contains(".dynsym") || s.name.contains(".dynstr"))) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }
}

package com.exbin.app.elf.pseudoc.r2dec;

import com.exbin.app.nativebridge.NativeBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.9: 跳转表 (switch jump table) 解析器。
 *
 * <p>对齐 radare2 {@code libr/anal/jmptbl.c} 的语义, 识别三种编译器生成的
 * 跳转表模式, 从 so 文件直读表项并计算每个 case 的目标地址:
 * <ul>
 *   <li><b>Thumb {@code tbb/tbh [pc, rN]}</b> — 表基址 = 指令地址 + 4,
 *       表项 1/2 字节 LE, {@code target = ip + 4 + val * 2};</li>
 *   <li><b>AArch64 {@code adr/adrp → ldr wN,[xBase,xIdx,lsl#2] →
 *       add xN,xBase,wM,uxtw#2 → br xN}</b> — 回溯表基址与 case 基址,
 *       {@code target = caseBase + word * 4};</li>
 *   <li><b>ARM32 {@code add pc,pc,rN,lsl#2} 内联 b 表</b> — 表项即后续连续
 *       {@code b label} 指令, 无需读内存。</li>
 * </ul>
 *
 * <p>安全边界: 目标必须是当前指令流内的地址 (编译器表目标均在函数内),
 * 否则丢弃; 解析任何失败都返回空表, 不改变现有结构化输出。
 */
public final class JumpTableResolver {

    /** 表项读取上限 (与 r2 JMPTBL_MAXSZ 一致)。 */
    private static final int MAX_TABLE_SIZE = 512;

    /** 回溯距离: 找表基址/case 基址赋值时最多回看指令条数。 */
    private static final int BACKTRACK_LIMIT = 12;

    /** 字节读取器 — 默认走 NativeBridge, host 测试可注入假实现。 */
    public interface ByteReader {
        /** 读取 vaddr 处最多 maxLen 字节; 失败返回 null。 */
        byte[] read(long vaddr, int maxLen);
    }

    /** 解析结果: 一条间接跳转指令对应的跳转表。 */
    public static final class JumpTable {
        /** 跳转指令地址 (tbb/tbh/br/add pc)。 */
        public final long jumpAddr;
        /** 索引寄存器名 (如 "r3" / "w8"), 用于生成 if (reg == i) 链。 */
        public final String indexReg;
        /** 目标地址列表, 下标即 case 值 (0, 1, 2, ...)。 */
        public final List<Long> targets;

        public JumpTable(long jumpAddr, String indexReg, List<Long> targets) {
            this.jumpAddr = jumpAddr;
            this.indexReg = indexReg;
            this.targets = targets;
        }
    }

    private JumpTableResolver() {
    }

    /**
     * 解析给定指令流中的全部跳转表。
     *
     * @param insns   指令流 (IrInsn 需含 mnemonic/opStr/addr/origInsn)
     * @param reader  字节读取器 (so 文件访问)
     * @param funcMin 函数起始地址 (目标校验下界)
     * @param funcMax 函数结束地址 (目标校验上界, 含)
     * @return jumpAddr → JumpTable; 无间接跳转或解析失败返回空 map
     */
    public static Map<Long, JumpTable> resolve(
            List<IrInsn> insns, ByteReader reader, long funcMin, long funcMax) {
        if (insns == null || insns.isEmpty() || reader == null) {
            return Collections.emptyMap();
        }
        Map<Long, JumpTable> result = new HashMap<>();
        try {
            for (int i = 0; i < insns.size(); i++) {
                IrInsn insn = insns.get(i);
                JumpTable t = null;
                if (isThumbTable(insn)) {
                    t = resolveThumbTable(insns, i, reader, funcMin, funcMax);
                } else if (isAarch64Br(insn)) {
                    t = resolveAarch64Table(insns, i, reader, funcMin, funcMax);
                } else if (isArm32InlineTable(insn)) {
                    t = resolveArm32InlineTable(insns, i, funcMin, funcMax);
                }
                if (t != null && t.targets.size() >= 2) {
                    result.put(t.jumpAddr, t);
                }
            }
        } catch (Throwable t) {
            // 任何异常都不影响现有流程
        }
        return result;
    }

    /**
     * 便捷入口: 通过 soPath 直接解析 (parseSo/readCode/freeSo 内部管理)。
     * host 无 native 时返回空表。
     */
    public static Map<Long, JumpTable> resolve(
            List<IrInsn> insns, String soPath, long funcMin, long funcMax) {
        if (soPath == null || soPath.isEmpty() || !hasIndirectJump(insns)) {
            return Collections.emptyMap();
        }
        long handle = NativeBridge.parseSo(soPath, true);
        if (handle == 0) {
            return Collections.emptyMap();
        }
        try {
            return resolve(insns,
                    (vaddr, maxLen) -> NativeBridge.readCode(handle, vaddr, maxLen),
                    funcMin, funcMax);
        } finally {
            NativeBridge.freeSo(handle);
        }
    }

    // ── 模式判定 ──

    /** tbb/tbh [pc, rN] — Thumb 表跳转。 */
    private static boolean isThumbTable(IrInsn insn) {
        String m = insn.mnemonic;
        return (m.equals("tbb") || m.equals("tbh"))
                && insn.opStr != null && insn.opStr.contains("pc");
    }

    /** br xN (N≠30/lr) — AArch64 间接跳转。 */
    private static boolean isAarch64Br(IrInsn insn) {
        if (!insn.mnemonic.equals("br") || insn.opStr == null) return false;
        String op = insn.opStr.trim();
        return !op.equals("x30") && !op.equals("lr");
    }

    /** add{cond} pc, pc, rN, lsl #2 — ARM32 内联跳转表。 */
    private static boolean isArm32InlineTable(IrInsn insn) {
        String m = insn.mnemonic;
        if (!m.startsWith("add")) return false;
        if (insn.opStr == null) return false;
        return insn.opStr.matches("(?i)pc\\s*,\\s*pc\\s*,\\s*r\\d+\\s*,\\s*lsl\\s*#?2");
    }

    private static boolean hasIndirectJump(List<IrInsn> insns) {
        for (IrInsn insn : insns) {
            if (isThumbTable(insn) || isAarch64Br(insn) || isArm32InlineTable(insn)) {
                return true;
            }
        }
        return false;
    }

    // ── Thumb tbb/tbh ──

    private static final Pattern THUMB_OP = Pattern.compile(
            "(?i)\\[\\s*pc\\s*,\\s*(r\\d+)(?:\\s*,\\s*lsl\\s*#?1)?\\s*\\]");

    private static JumpTable resolveThumbTable(List<IrInsn> insns, int idx,
            ByteReader reader, long funcMin, long funcMax) {
        IrInsn insn = insns.get(idx);
        Matcher m = THUMB_OP.matcher(insn.opStr);
        if (!m.find()) return null;
        String indexReg = m.group(1);
        int entrySize = insn.mnemonic.equals("tbb") ? 1 : 2;
        long pc = insn.addr + 4; // Thumb PC
        byte[] tbl = reader.read(pc, MAX_TABLE_SIZE * entrySize);
        if (tbl == null || tbl.length == 0) return null;
        List<Long> targets = new ArrayList<>();
        int count = Math.min(tbl.length / entrySize, MAX_TABLE_SIZE);
        for (int i = 0; i < count; i++) {
            long val = entrySize == 1 ? (tbl[i] & 0xFF)
                    : ((tbl[i * 2] & 0xFF) | ((tbl[i * 2 + 1] & 0xFF) << 8));
            long target = pc + val * 2;
            if (!inRange(target, funcMin, funcMax)) break;
            if (i > 0 && val == 0) break; // 哨兵: 后续空项
            targets.add(target);
        }
        return targets.isEmpty() ? null : new JumpTable(insn.addr, indexReg, targets);
    }

    // ── AArch64 br 表 ──

    private static final Pattern LDR_MEM = Pattern.compile(
            "(?i)\\[\\s*([xw]\\d+)\\s*,\\s*([xw]\\d+)\\s*,\\s*lsl\\s*#?(\\d+)\\s*\\]");
    private static final Pattern ADD_EXT = Pattern.compile(
            "(?i)([xw]\\d+)\\s*,\\s*([xw]\\d+)\\s*,\\s*([xw]\\d+)\\s*,\\s*uxtw(?:\\s*#?(\\d+))?");
    private static final Pattern ADR_IMM = Pattern.compile("(?i)(?:#|0x)?(0x[0-9a-f]+|\\d+)");

    /**
     * AArch64 clang 标准表:
     * <pre>
     *   adrp x17, .Lswitch.table
     *   add  x17, x17, :lo12:.Lswitch.table
     *   ldr  w8, [x17, wIdx, lsl #2]    ; w8 = 表项, wIdx = 索引
     *   adrp x17, .Lswitch.case_base
     *   add  x17, x17, :lo12:.Lswitch.case_base
     *   add  x17, x17, w8, uxtw #2      ; x17 = caseBase + w8*4
     *   br   x17
     * </pre>
     */
    private static JumpTable resolveAarch64Table(List<IrInsn> insns, int idx,
            ByteReader reader, long funcMin, long funcMax) {
        IrInsn br = insns.get(idx);
        String brReg = br.opStr.trim();

        // 第一步: 回溯找 add xN, xBase, xM, uxtw #2 (dst == brReg)
        String caseBaseReg = null;
        String indexReg = null;
        int addIdx = -1;
        for (int j = idx - 1; j >= 0 && j >= idx - BACKTRACK_LIMIT; j--) {
            IrInsn insn = insns.get(j);
            if (!insn.mnemonic.equals("add") || insn.opStr == null) continue;
            Matcher am = ADD_EXT.matcher(insn.opStr);
            if (am.matches() && am.group(1).equals(brReg)) {
                caseBaseReg = am.group(2);
                indexReg = am.group(3);
                addIdx = j;
                break;
            }
        }
        if (indexReg == null) return null;

        // 第二步: case 基址 = add 的 base 寄存器来源 (adr/adrp+add)
        Long caseBase = backtrackAddr(insns, addIdx, caseBaseReg);

        // 第三步: 继续往前找 ldr wK, [xBase, xIdx, lsl #2] (dst == indexReg)
        String tableBaseReg = null;
        for (int j = addIdx - 1; j >= 0 && j >= addIdx - BACKTRACK_LIMIT; j--) {
            IrInsn insn = insns.get(j);
            if (!insn.mnemonic.equals("ldr") || insn.opStr == null) continue;
            String dst = insn.opStr.trim().split(",", 2)[0].trim();
            if (!dst.equals(indexReg)) continue;
            Matcher lm = LDR_MEM.matcher(insn.opStr);
            if (lm.find()) {
                tableBaseReg = lm.group(1);
                break;
            }
        }
        if (tableBaseReg == null) return null;

        // 第四步: 表基址 = ldr 的 base 寄存器来源
        Long tableBase = backtrackAddr(insns, idx, tableBaseReg);
        if (tableBase == null) return null;

        byte[] tbl = reader.read(tableBase, MAX_TABLE_SIZE * 4);
        if (tbl == null || tbl.length == 0) return null;
        List<Long> targets = new ArrayList<>();
        int count = Math.min(tbl.length / 4, MAX_TABLE_SIZE);
        for (int i = 0; i < count; i++) {
            long word = le32(tbl, i * 4);
            if (word == 0xFFFFFFFFL || word == 0xFFFFFFFEL) break;
            if (i > 0 && word == 0) break; // 哨兵: 后续空项
            long target;
            if (caseBase != null) {
                target = caseBase + word * 4;
            } else {
                // 无 case 基址: 表项为绝对地址或相对 ip 偏移 (r2 近似)
                target = word > 0x10000 ? word : br.addr + word;
            }
            if (!inRange(target, funcMin, funcMax)) break;
            targets.add(target);
        }
        return targets.isEmpty() ? null : new JumpTable(br.addr, indexReg, targets);
    }

    /** 回溯寄存器最近一次 adr/adrp(+add) 赋值的目标地址。 */
    private static Long backtrackAddr(List<IrInsn> insns, int fromIdx, String reg) {
        for (int k = fromIdx; k >= 0 && k >= fromIdx - BACKTRACK_LIMIT; k--) {
            IrInsn insn = insns.get(k);
            String m = insn.mnemonic;
            if (!m.equals("adr") && !m.equals("adrp")) continue;
            if (insn.opStr == null) continue;
            String dst = insn.opStr.trim().split(",", 2)[0].trim();
            if (!dst.equals(reg)) continue;

            // 优先 native 已解析的 PC 相对目标
            if (insn.origInsn != null && insn.origInsn.nativeTargetAddr > 0) {
                long addr = insn.origInsn.nativeTargetAddr;
                // adrp+add 合并
                if (m.equals("adrp") && k + 1 < insns.size()) {
                    IrInsn next = insns.get(k + 1);
                    if (next.mnemonic.equals("add") && next.opStr != null) {
                        String[] ops = next.opStr.trim().split(",");
                        if (ops.length >= 3 && ops[0].trim().equals(reg)
                                && ops[1].trim().equals(reg)) {
                            long off = parseImm(ops[2].trim());
                            addr += off;
                        }
                    }
                }
                return addr;
            }
            // 兜底: 从 opStr 解析立即数 (adr x17, 0x1234)
            Matcher im = ADR_IMM.matcher(insn.opStr);
            if (im.find()) {
                return parseImm(im.group(1));
            }
            return null;
        }
        return null;
    }

    // ── ARM32 内联 b 表 ──

    private static JumpTable resolveArm32InlineTable(List<IrInsn> insns, int idx,
            long funcMin, long funcMax) {
        IrInsn insn = insns.get(idx);
        Matcher m = Pattern.compile("(?i)pc\\s*,\\s*pc\\s*,\\s*(r\\d+)").matcher(insn.opStr);
        if (!m.find()) return null;
        String indexReg = m.group(1);
        List<Long> targets = new ArrayList<>();
        for (int k = idx + 1; k < insns.size() && k < idx + 1 + MAX_TABLE_SIZE; k++) {
            IrInsn b = insns.get(k);
            if (!b.mnemonic.equals("b") && !b.mnemonic.startsWith("b.")) break;
            Long t = parseImm(b.opStr);
            if (t == null || !inRange(t, funcMin, funcMax)) break;
            targets.add(t);
        }
        return targets.isEmpty() ? null : new JumpTable(insn.addr, indexReg, targets);
    }

    // ── 工具 ──

    private static boolean inRange(long addr, long min, long max) {
        return addr >= min && addr <= max;
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static Long parseImm(String s) {
        if (s == null) return null;
        s = s.trim().replace("#", "").replace(";", "");
        if (s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

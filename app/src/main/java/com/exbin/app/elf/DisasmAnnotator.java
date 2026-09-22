package com.exbin.app.elf;

import com.exbin.app.util.Demangler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.9.30: 反汇编属性翻译层 (Disassembly Annotation Layer).
 * <p>
 * 参考 IDA Pro 的反汇编增强能力, 在反汇编完成后对指令列表进行后处理,
 * 为每条指令填充语义注释. 包括:
 * <ul>
 *   <li>全局变量/数据对象引用注释 (从 .dynsym/.symtab 的 STT_OBJECT 符号构建地址→名称表)</li>
 *   <li>ADRP+ADD/LDR 融合 — 计算最终地址并标注符号名</li>
 *   <li>字符串引用注释 (从 .rodata 扫描的字符串地址映射)</li>
 *   <li>C++ 函数名解修饰 (BL 目标的 __cxa_demangle)</li>
 *   <li>栈帧变量名 (var_XX) — 跟踪 SUB SP 并映射 SP 偏移</li>
 *   <li>代码标签 (loc_XXXX) — 在分支目标处生成标签</li>
 *   <li>增强操作数 — ADRP 目标显示符号名而非纯地址</li>
 * </ul>
 * <p>
 * 使用方式: 在反汇编完成后调用 {@link #annotate(List, ElfFile, int)}.
 * 支持开关切换: 当用户关闭"反汇编增强"时, 只需不显示 annotation/enhancedOpStr 字段即可.
 */
public class DisasmAnnotator {

    private static final String TAG = "DisasmAnnotator";
    private static final int MAX_INSNS = 2048; // 性能保护

    // 正则: 提取 0x 开头的十六进制地址
    private static final Pattern HEX_PATTERN = Pattern.compile("0x([0-9a-fA-F]+)");
    // 正则: 提取寄存器名 (x0-x30, w0-w30, r0-r15)
    private static final Pattern REG_PATTERN = Pattern.compile("\\b([xw]\\d{1,2}|r\\d{1,2}|sp|fp|lr)\\b",
            Pattern.CASE_INSENSITIVE);
    // 正则: ADRP 目标
    private static final Pattern ADRP_PATTERN = Pattern.compile("#(0x[0-9a-fA-F]+|\\d+)");
    // 正则: ADD/LDR 中的立即数偏移
    private static final Pattern IMM_OFFSET_PATTERN = Pattern.compile("#(?:0x)?([0-9a-fA-F]+)");
    // v2.9.38: 匹配内存操作数 [Rn, #imm] 或 [Rn]
    private static final Pattern MEM_PATTERN = Pattern.compile(
            "\\[\\s*([xw]\\d{1,2}|r\\d{1,2}|sp|fp)\\s*(?:,\\s*#?(0x[0-9a-fA-F]+|\\d+))?\\s*\\]",
            Pattern.CASE_INSENSITIVE);

    // ========== 地址→标签 映射表 ==========
    private final Map<Long, String> dataLabels = new HashMap<>();   // 全局变量/数据对象
    private final Map<Long, String> funcLabels = new HashMap<>();   // 函数符号
    private final Map<Long, String> importLabels = new HashMap<>(); // 导入函数 (PLT)
    private final Map<Long, String> stringLabels = new HashMap<>(); // 字符串地址→内容
    private final Map<Long, String> relocLabels = new HashMap<>();  // 重定位地址→符号名

    // v2.9.40: 结构体偏移收集器 — 类名 → 偏移集合
    // v2.9.41: 升级为结构体成员表 — 类名 → (偏移 → 成员信息)
    private final Map<String, Map<Long, StructMember>> structMembers = new HashMap<>();

    /**
     * v2.9.41: 结构体成员信息 (偏移 + 类型).
     * v2.9.42: 去除名称推断, 只保留类型.
     */
    private static class StructMember {
        long offset;
        String type;   // 类型, 如 "int", "float", "void *", 无推断时为 null

        StructMember(long offset) {
            this.offset = offset;
        }
    }

    // ========== 构建方法 ==========

    /**
     * 主入口: 对反汇编指令列表进行语义注解.
     *
     * @param insns   反汇编指令列表
     * @param elf     ELF 文件对象 (提供符号表/字符串/重定位数据)
     * @param machine 机器类型 (ElfConstants.EM_AARCH64 等)
     */
    public void annotate(List<DisassembledInstruction> insns, ElfFile elf, int machine) {
        if (insns == null || insns.isEmpty() || elf == null) return;

        // 1. 构建地址→标签映射表
        buildLabelMaps(elf);

        // 3. 机器类型
        boolean isArm64 = (machine == ElfConstants.EM_AARCH64);
        boolean isArm32 = (machine == ElfConstants.EM_ARM);

        // v2.9.40: 提取当前函数的类名 (从第一条指令的地址查符号表)
        String currentClassName = extractClassName(insns, elf);
        // v2.9.40: 预扫描当前函数, 收集所有 this 指针偏移
        if (currentClassName != null) {
            collectOffsetsFromFunction(insns, currentClassName, isArm64);
        }

        // 2. 收集分支目标, 生成 loc_ 标签
        Set<Long> branchTargets = collectBranchTargets(insns);

        // 逐条注解

        // 寄存器跟踪状态
        Map<String, Long> regPageAddr = new HashMap<>();  // ADRP 页地址
        Map<String, Long> regFullAddr = new HashMap<>();  // 完整地址 (ADRP+ADD 后)
        // v2.9.37: 寄存器类型映射表 (Type Recovery)
        // 0=unknown, 1=ptr, 2=void*, 3=int, 4=char_ptr, 5=this, 6=float
        Map<String, Integer> regType = new HashMap<>();
        // v2.9.37: 记录每条 MOV 指令的源寄存器, 用于 BL 反向约束
        // key = 目标寄存器, value = [源寄存器, MOV指令的index]
        Map<String, String[]> movSource = new HashMap<>();
        int frameSize = 0; // 栈帧大小

        int limit = Math.min(insns.size(), MAX_INSNS);
        for (int i = 0; i < limit; i++) {
            DisassembledInstruction ins = insns.get(i);
            if (ins.mnemonic == null) continue;
            String mn = ins.mnemonic.toLowerCase();
            String op = ins.opStr != null ? ins.opStr : "";

            StringBuilder annot = new StringBuilder();
            String enhanced = null;
            String stackVar = null;

            // v2.9.36: 字符串引用不再放入 annotation, 由 AsmAdapter.tvStrRef 统一显示
            // (避免 ref: "string" 和 ; "string" 重复显示)

            // ---- 0b. 使用 native 层计算的目标地址查找符号 ----
            // native 层通过 capstone detail 跟踪 ADRP+ADD/LDR, 计算出最终地址
            // Java 层用它查找符号表 (字符串已由 StringReferenceAnalyzer 处理)
            if (ins.nativeTargetAddr != 0) {
                long targetAddr = ins.nativeTargetAddr;
                String label = findLabelAt(targetAddr);
                if (label != null) {
                    if (annot.length() > 0) annot.append("  ");
                    annot.append(label);
                }
            }

            // ---- A. ADRP 跟踪 (ARM64) — 只做符号标签增强, 不再查字符串 ----
            // 字符串引用已由 StringReferenceAnalyzer 处理, 这里只做符号名替换
            if (isArm64 && mn.equals("adrp")) {
                String dstReg = extractFirstReg(op);
                Long target = extractHexAddr(op);
                if (dstReg != null && target != null) {
                    regPageAddr.put(dstReg.toLowerCase(), target);
                    // 查找符号
                    String label = findLabelAt(target);
                    if (label != null) {
                        enhanced = op.replaceFirst("(?i)#0x[0-9a-fA-F]+", "#" + label + "@PAGE");
                    } else {
                        // 尝试在页内查找 (某些符号在页偏移处)
                        String pageLabel = findLabelInPage(target);
                        if (pageLabel != null) {
                            enhanced = op.replaceFirst("(?i)#0x[0-9a-fA-F]+", "#" + pageLabel + "@PAGE");
                        }
                    }
                }
            }

            // ---- B. ADD xN, xN, #imm (ARM64 ADRP+ADD 融合) — 只做符号标签增强 ----
            if (isArm64 && (mn.equals("add") || mn.equals("adds"))) {
                String[] parts = op.split(",");
                if (parts.length >= 3) {
                    String dst = parts[0].trim().toLowerCase();
                    String src = parts[1].trim().toLowerCase();
                    Long page = regPageAddr.get(src);
                    if (page != null) {
                        Long imm = extractImm(parts[2]);
                        if (imm != null) {
                            long finalAddr = page + imm;
                            regFullAddr.put(dst, finalAddr);
                            String label = findLabelAt(finalAddr);
                            if (label != null) {
                                // 增强操作数: 显示 #symbol@PAGEOFF
                                enhanced = replaceLastImm(op, "#" + label + "@PAGEOFF");
                                if (annot.length() == 0) annot.append(label);
                            }
                            // 传播: dst 也持有页地址
                            regPageAddr.put(dst, page);
                        }
                    }
                }
            }

            // ---- C. LDR/LDUR xN, [xM, #imm] (ARM64 ADRP+LDR 融合) — 只做符号标签增强 ----
            if (isArm64 && (mn.startsWith("ldr") || mn.startsWith("ldur"))) {
                Long baseAddr = resolveBaseAddr(op, regPageAddr, regFullAddr);
                if (baseAddr != null) {
                    String label = findLabelAt(baseAddr);
                    if (label != null) {
                        enhanced = annotateMemOperand(op, label + "@PAGEOFF");
                    }
                    // 如果 LDR 的目标寄存器也跟踪
                    String dstReg = extractFirstReg(op);
                    if (dstReg != null && label != null) {
                        // LDR 从 GOT 加载, 目标寄存器持有符号指向的地址
                        regFullAddr.put(dstReg.toLowerCase(), baseAddr);
                    }
                }
            }

            // ---- E. BL/BLX 解修饰 + 操作数增强 ----
            // v2.9.38: 同时增强操作数显示 demangled 函数名
            if (mn.equals("bl") || mn.equals("blx") || mn.equals("blr")) {
                Long target = extractHexAddr(op);
                if (target != null) {
                    // 查函数标签
                    String funcName = funcLabels.get(target);
                    if (funcName == null) funcName = importLabels.get(target);
                    if (funcName != null) {
                        // C++ 解修饰
                        Demangler.Result dr = Demangler.demangle(funcName);
                        String displayName = dr.supported ? dr.demangled : funcName;
                        if (!displayName.equals(funcName)) {
                            annot.append(displayName);
                        } else {
                            annot.append(funcName);
                        }
                        // v2.9.38: 增强操作数: 将 0xXXXX 替换为函数名
                        if (enhanced == null) enhanced = op;
                        enhanced = enhanced.replaceFirst("(?i)0x[0-9a-fA-F]+", displayName);
                    }
                }
            }

            // ---- E2. 分支指令目标替换为 loc_XXXX 或函数名 ----
            // v2.9.38: 如果目标地址是已知函数, 显示 demangled 函数名 (而非 loc_)
            if (mn.equals("b") || mn.startsWith("b.") || mn.startsWith("cb") || mn.startsWith("tb")) {
                Long target = extractHexAddr(op);
                if (target != null) {
                    // 1. 先检查是否是函数地址
                    String funcName = funcLabels.get(target);
                    if (funcName == null) funcName = importLabels.get(target);
                    if (funcName != null) {
                        // 目标是函数 → 显示 demangled 函数名
                        Demangler.Result dr = Demangler.demangle(funcName);
                        String displayName = dr.supported ? dr.demangled : funcName;
                        enhanced = op.replaceFirst("(?i)0x[0-9a-fA-F]+", displayName);
                    } else {
                        // 2. 不是函数 → 显示 loc_XXXX
                        String locLabel = "loc_" + Long.toHexString(target);
                        enhanced = op.replaceFirst("(?i)0x[0-9a-fA-F]+", locLabel);
                    }
                }
            }

            // ---- F. 栈帧分析 ----
            if (mn.equals("sub") && op.toLowerCase().contains("sp, sp, #")) {
                Long imm = extractImmFromSubSp(op);
                if (imm != null && imm > 0 && imm < 0x10000) {
                    frameSize = imm.intValue();
                }
            }
            if (mn.equals("add") && op.toLowerCase().contains("sp, sp, #")) {
                Long imm = extractImmFromSubSp(op);
                if (imm != null) {
                    frameSize = Math.max(0, frameSize - imm.intValue());
                }
            }

            // SP 相对寻址 → var_XX
            if (frameSize > 0 && op.toLowerCase().contains("[sp") || op.toLowerCase().contains("[sp,")) {
                Long spOff = extractSpOffset(op);
                if (spOff != null) {
                    long varOff = spOff - frameSize;
                    if (varOff < 0) {
                        stackVar = "var_" + Long.toHexString(-varOff);
                        // v2.9.35: 增强操作数显示 IDA 风格 #0x30+var_XX
                        if (enhanced == null) enhanced = op;
                        String spOffStr = String.format("0x%x", spOff);
                        String varExpr = String.format("0x%x+var_%x", frameSize, -varOff);
                        enhanced = enhanced.replace(spOffStr, varExpr);
                    } else if (varOff > 0) {
                        stackVar = "arg_" + Long.toHexString(varOff);
                        if (enhanced == null) enhanced = op;
                        String spOffStr = String.format("0x%x", spOff);
                        String argExpr = String.format("0x%x+arg_%x", frameSize, varOff);
                        enhanced = enhanced.replace(spOffStr, argExpr);
                    }
                }
            }

            // ---- G. loc_ 标签 (分支目标) ----
            if (branchTargets.contains(ins.address)) {
                // 在 annotation 前面加 loc_ 标记
                String locLabel = "loc_" + Long.toHexString(ins.address);
                if (annot.length() > 0) {
                    annot.insert(0, locLabel + "  ");
                }
                // 不单独显示, 让 AsmAdapter 在行首显示
            }

            // ---- H. STP/LDP 栈帧变量 ----
            if (frameSize > 0 && (mn.equals("stp") || mn.equals("ldp") || mn.equals("str") || mn.equals("ldr"))) {
                if (op.toLowerCase().contains("[sp")) {
                    Long spOff = extractSpOffset(op);
                    if (spOff != null) {
                        long varOff = spOff - frameSize;
                        if (varOff < 0) {
                            stackVar = "var_" + Long.toHexString(-varOff);
                            // v2.9.35: 增强操作数显示 IDA 风格 #0x30+var_XX
                            if (enhanced == null) enhanced = op;
                            String spOffStr = String.format("0x%x", spOff);
                            String varExpr = String.format("0x%x+var_%x", frameSize, -varOff);
                            enhanced = enhanced.replace(spOffStr, varExpr);
                        } else if (varOff > 0) {
                            stackVar = "arg_" + Long.toHexString(varOff);
                        }
                    }
                }
            }

            // ---- I. MOV 寄存器别名传播 + 类型传播 (v2.9.37) ----
            if (mn.equals("mov") || mn.equals("movz")) {
                String[] parts = op.split(",");
                if (parts.length >= 2) {
                    String dst = parts[0].trim().toLowerCase();
                    String src = parts[1].trim().toLowerCase();
                    // 1. 传播页地址 (值传播)
                    Long page = regPageAddr.get(src);
                    if (page != null) regPageAddr.put(dst, page);
                    // 2. 传播完整地址 (值传播)
                    Long full = regFullAddr.get(src);
                    if (full != null) regFullAddr.put(dst, full);
                    // 3. v2.9.37: 传播类型标签 (类型传播)
                    Integer srcType = regType.get(src);
                    if (srcType != null && srcType != 0) {
                        regType.put(dst, srcType);
                    }
                    // 4. v2.9.37: 记录 MOV 的源寄存器, 供 BL 反向约束使用
                    movSource.put(dst, new String[]{src, String.valueOf(i)});

                    // 5. v2.9.37: 如果源寄存器有完整地址且指向字符串/数据, 推断类型
                    if (full != null) {
                        String label = findLabelAt(full);
                        if (label != null && annot.length() == 0) {
                            annot.append(label);
                        }
                        // 如果指向字符串, 类型为 char_ptr
                        if (stringLabels.get(full) != null) {
                            regType.put(dst, 4); // char_ptr
                        }
                    }

                    // 6. v2.9.37: 输出寄存器类型注释
                    // 优先使用 Java 层的类型推断 (BL 反向约束), 如果没有则用 native 层的
                    Integer dstType = regType.get(dst);
                    if (dstType != null && dstType != 0) {
                        // Java 层推断结果优先 (除非 native 层已有更好的结果)
                        if (ins.nativeRegType == null || ins.nativeRegType.isEmpty()
                                || "int".equals(ins.nativeRegType)) {
                            // int 是 native 层的默认推断, 可以被 Java 层覆盖
                            String typeStr = typeToString(dstType);
                            if (typeStr != null) ins.nativeRegType = typeStr;
                        }
                    }
                }
            }

            // ---- J. BL 反向约束传播 + 调用后清除寄存器 (v2.9.37) ----
            if (mn.equals("bl") || mn.equals("blx") || mn.equals("blr")) {
                // v2.9.37: 第三层 — 调用约定反向约束求解
                // AAPCS64: X0=arg0(this/ret), X1=arg1, X2=arg2, ...
                // 根据被调函数名推断参数类型, 反向传播给寄存器
                Long callTarget = extractHexAddr(op);
                if (callTarget != null) {
                    String funcName = funcLabels.get(callTarget);
                    if (funcName == null) funcName = importLabels.get(callTarget);

                    if (funcName != null) {
                        // 解修饰获取函数签名
                        Demangler.Result dr = Demangler.demangle(funcName);
                        String sig = dr.supported ? dr.demangled : funcName;

                        // v2.9.37: 根据函数签名推断参数类型
                        // X0 = 第一个参数 (通常是 this 指针或返回值)
                        // X1 = 第二个参数
                        inferArgTypesFromSignature(sig, regType, movSource, insns, isArm64);
                    }
                }

                // v2.9.37: BL 后 X0/R0 为返回值 (默认 int), X1-X18/R1-R3 可能被修改
                // v2.9.39b: 修复 ARM32 寄存器名 (r0-r3 而非 x0-x7)
                if (isArm64) {
                    regType.put("x0", 3); // int (返回值)
                    for (int r = 1; r <= 18; r++) {
                        regType.remove("x" + r);
                        regType.remove("w" + r);
                    }
                    // 清除地址跟踪
                    regPageAddr.keySet().removeIf(r -> r.matches("[xw](0|1|2|3|4|5|6|7|8|9|1[0-8])"));
                    regFullAddr.keySet().removeIf(r -> r.matches("[xw](0|1|2|3|4|5|6|7|8|9|1[0-8])"));
                    movSource.keySet().removeIf(r -> r.matches("[xw](0|1|2|3|4|5|6|7|8|9|1[0-8])"));
                } else {
                    // ARM32: r0=返回值, r1-r3 可能被修改
                    regType.put("r0", 3); // int (返回值)
                    for (int r = 1; r <= 3; r++) {
                        regType.remove("r" + r);
                    }
                    regPageAddr.keySet().removeIf(r -> r.matches("r[0-3]"));
                    regFullAddr.keySet().removeIf(r -> r.matches("r[0-3]"));
                    movSource.keySet().removeIf(r -> r.matches("r[0-3]"));
                }
            }

            // ---- K. 第五组: 数据段 dword_ 标签生成 ----
            // 如果 LDR 的地址在数据段但没有符号名, 自动生成 dword_XXXX
            if (isArm64 && (mn.startsWith("ldr") || mn.startsWith("ldur"))) {
                if (annot.length() == 0) {
                    Long baseAddr = resolveBaseAddr(op, regPageAddr, regFullAddr);
                    if (baseAddr != null && findLabelAt(baseAddr) == null && stringLabels.get(baseAddr) == null) {
                        if (isDataAddress(baseAddr, elf)) {
                            String autoLabel = (mn.contains("w") ? "dword_" : "qword_")
                                    + Long.toHexString(baseAddr);
                            annot.append(autoLabel);
                        }
                    }
                }
            }

            // ---- K2. v2.9.38: this 指针追踪 + 结构体成员访问注释 ----
            // v2.9.39: 不用正则, 直接解析助记符和操作数字符串
            // 函数入口: R0/X0 = this 指针 (AAPCS)
            // LDR/STR Rn, [R0, #offset] → 注释 field_XX
            {
                // 入口指令: 标记 R0/X0 为 this
                if (i == 0) {
                    String entryReg = isArm64 ? "x0" : "r0";
                    regType.put(entryReg, 5); // this
                    regType.put(isArm64 ? "w0" : "r0", 5);
                }

                // v2.9.43: 支持所有访问类成员的指令
                // ldr/ldrb/ldrh/ldrsb/ldrsh/ldur/ldurb/ldurh/ldr.w/ldrb.w
                // str/strb/strh/stur/sturb/sturh/str.w/strb.w
                // ldp/stp/ldp.w/stp.w
                // v2.9.44: VFP 指令 vldr/vstr/vldm/vstm
                boolean isMem = mn.startsWith("ldr") || mn.startsWith("ldur")
                        || mn.startsWith("str") || mn.startsWith("stur")
                        || mn.equals("stp") || mn.equals("ldp")
                        || mn.equals("stpd") || mn.equals("ldpd")
                        || mn.startsWith("vldr") || mn.startsWith("vstr")
                        || mn.startsWith("vldm") || mn.startsWith("vstm");
                if (isMem && op.contains("[")) {
                    // 解析操作数中的内存操作数 [Rn, #imm] 或 [Rn]
                    // 直接从 opStr 中提取, 不用正则
                    int bStart = op.indexOf('[');
                    int bEnd = op.indexOf(']', bStart);
                    if (bStart >= 0 && bEnd > bStart) {
                        String bracket = op.substring(bStart + 1, bEnd).trim();
                        // 分割: "r0, #0x14" 或 "r0" 或 "sp, #0x20"
                        String baseReg = null;
                        long offset = 0;
                        int comma = bracket.indexOf(',');
                        if (comma >= 0) {
                            baseReg = bracket.substring(0, comma).trim().toLowerCase();
                            String offPart = bracket.substring(comma + 1).trim();
                            // 移除 # 前缀
                            if (offPart.startsWith("#")) offPart = offPart.substring(1).trim();
                            // 解析立即数
                            if (offPart.startsWith("0x") || offPart.startsWith("0X")) {
                                try { offset = Long.parseUnsignedLong(offPart.substring(2), 16); }
                                catch (NumberFormatException ignored) { offset = -1; }
                            } else {
                                try { offset = Long.parseLong(offPart); }
                                catch (NumberFormatException ignored) { offset = -1; }
                            }
                        } else {
                            baseReg = bracket.trim().toLowerCase();
                            offset = 0;
                        }

                        if (baseReg != null && offset >= 0) {
                            Integer baseType = regType.get(baseReg);
                            if (baseType != null && baseType == 5 && offset < 0x10000) {
                                // v2.9.42: 只在确认存在该成员时才显示 (type)(this+offset)
                                // 必须在 structMembers 中有记录才显示
                                StructMember member = null;
                                if (currentClassName != null) {
                                    Map<Long, StructMember> members = structMembers.get(currentClassName);
                                    if (members != null) {
                                        member = members.get(offset);
                                    }
                                }

                                if (member != null) {
                                    // 确认存在该成员 → 显示 (type)(this+0xoffset)
                                    String typeStr = (member.type != null && !member.type.isEmpty())
                                            ? member.type : "int";
                                    String offsetStr = (offset == 0) ? "0" : "0x" + Long.toHexString(offset);
                                    String displayLabel = "(" + typeStr + ")(this+" + offsetStr + ")";

                                    if (annot.length() == 0) {
                                        annot.append(displayLabel);
                                    }
                                    // 增强操作数: [R0, #0x44] → [R0, #0x44]  (不改操作数, 只加注释)
                                }
                            }
                        }
                    }
                }
            }

            // ---- K3. v2.9.45: ADD/ADDS 指令访问成员 (this+offset) ----
            // adds r0, #0x1c → r0 = this + 0x1c (计算成员地址)
            // add r0, r0, #0x1c → r0 = this + 0x1c
            if (mn.equals("add") || mn.equals("adds") || mn.equals("add.w")) {
                String[] parts = op.split(",");
                // 格式1: add r0, #0x1c (2个操作数, src是隐式的r0)
                // 格式2: add r0, r0, #0x1c (3个操作数)
                if (parts.length >= 2) {
                    String dst = parts[0].trim().toLowerCase();
                    String lastPart = parts[parts.length - 1].trim();
                    // 检查最后一个操作数是否是立即数 (#0x1c 或 #28)
                    if (lastPart.startsWith("#")) {
                        String offPart = lastPart.substring(1).trim();
                        long offset = -1;
                        try {
                            if (offPart.startsWith("0x") || offPart.startsWith("0X")) {
                                offset = Long.parseUnsignedLong(offPart.substring(2), 16);
                            } else {
                                offset = Long.parseLong(offPart);
                            }
                        } catch (NumberFormatException ignored) {}

                        if (offset >= 0 && offset < 0x10000) {
                            // 确定源寄存器
                            String srcReg;
                            if (parts.length >= 3) {
                                srcReg = parts[1].trim().toLowerCase();
                            } else {
                                srcReg = dst; // add r0, #imm → src = r0
                            }

                            // 检查源寄存器是否是 this
                            Integer srcType = regType.get(srcReg);
                            if (srcType != null && srcType == 5) {
                                // 确认存在该成员 → 显示 (type)(this+offset)
                                StructMember member = null;
                                if (currentClassName != null) {
                                    Map<Long, StructMember> members = structMembers.get(currentClassName);
                                    if (members != null) {
                                        member = members.get(offset);
                                    }
                                }

                                if (member != null) {
                                    String typeStr = (member.type != null && !member.type.isEmpty())
                                            ? member.type : "void *";
                                    String offsetStr = (offset == 0) ? "0" : "0x" + Long.toHexString(offset);
                                    String displayLabel = "(" + typeStr + ")(this+" + offsetStr + ")";
                                    if (annot.length() == 0) {
                                        annot.append(displayLabel);
                                    }
                                    // r0 现在指向成员, 仍标记为 ptr (不再算 this)
                                    regType.put(dst, 2); // void *
                                }
                            }
                        }
                    }
                }
            }

            // ---- L. 第七组: __stack_chk_fail 模式匹配 ----
            if (annot.length() == 0 && isStackChkPattern(insns, i, mn)) {
                annot.append("[stack canary check]");
            }

            // v2.9.39b: 在所有注解部分之后统一应用结果到指令
            if (annot.length() > 0) {
                ins.annotation = annot.toString();
            }
            if (enhanced != null) {
                ins.enhancedOpStr = enhanced;
            }
            if (stackVar != null) {
                ins.stackVar = stackVar;
            }
        }
    }

    // ========== 标签表构建 ==========

    private void buildLabelMaps(ElfFile elf) {
        dataLabels.clear();
        funcLabels.clear();
        importLabels.clear();
        stringLabels.clear();
        relocLabels.clear();

        // 0. v2.9.31: Native 层数据标签 (最高优先级, 包含全部4个源头的去重合并结果)
        // 直接用 native 的 dataLabels 填充 dataLabels/relocLabels/stringLabels
        if (elf.dataLabels != null && elf.dataLabels.length > 0) {
            for (com.exbin.app.nativebridge.NativeBridge.DataLabelNative dl : elf.dataLabels) {
                if (dl.name == null || dl.name.isEmpty() || dl.address == 0) continue;
                switch (dl.type) {
                    case 0: // STT_OBJECT 符号表
                        dataLabels.put(dl.address, dl.name);
                        break;
                    case 1: // 重定位 GOT
                        relocLabels.put(dl.address, dl.name);
                        break;
                    case 2: // 字符串
                        // 字符串标签名格式: a<money> 或 str_<addr>, 实际内容在 elf.strings
                        // 不直接存入 stringLabels (那里存的是内容), 而是作为 dataLabel
                        dataLabels.put(dl.address, dl.name);
                        break;
                    case 3: // 自动 dword_/qword_
                        dataLabels.putIfAbsent(dl.address, dl.name);
                        break;
                }
            }
        }

        // 1. Java 层符号表 → 补充 dataLabels + funcLabels (如果 native 未覆盖)
        if (elf.symtabEntries != null) {
            for (SymbolEntry sym : elf.symtabEntries) {
                if (sym.name == null || sym.name.isEmpty() || sym.stValue == 0) continue;
                int type = sym.stInfo & 0xf; // ST_TYPE
                if (type == 1) { // STT_OBJECT
                    dataLabels.putIfAbsent(sym.stValue, sym.name);
                } else if (type == 2) { // STT_FUNC
                    funcLabels.putIfAbsent(sym.stValue, sym.name);
                }
            }
        }

        // 2. 函数列表 → funcLabels
        if (elf.functions != null) {
            for (FunctionInfo fn : elf.functions) {
                if (fn.name != null && !fn.name.isEmpty() && fn.address != 0) {
                    funcLabels.putIfAbsent(fn.address, fn.name);
                }
            }
        }

        // 3. 导入函数 → importLabels
        if (elf.imports != null) {
            for (ImportedFunction imp : elf.imports) {
                if (imp.name != null && imp.pltAddress != 0) {
                    importLabels.put(imp.pltAddress, imp.name);
                }
                if (imp.name != null && imp.gotOffset != 0) {
                    importLabels.put(imp.gotOffset, imp.name);
                }
            }
        }

        // 4. 字符串 → stringLabels (存实际内容, 用于 ; "money" 注释)
        if (elf.strings != null) {
            for (ExtractedString es : elf.strings) {
                if (es.value != null && !es.value.isEmpty() && es.address != 0) {
                    stringLabels.put(es.address, es.value);
                }
            }
        }

        // 5. 重定位 → relocLabels (补充, 如果 native 未覆盖)
        if (elf.relocations != null) {
            for (RelocationEntry rel : elf.relocations) {
                if (rel.symbolName != null && !rel.symbolName.isEmpty() && rel.rOffset != 0) {
                    relocLabels.putIfAbsent(rel.rOffset, rel.symbolName);
                }
            }
        }
    }

    // ========== 分支目标收集 ==========

    private Set<Long> collectBranchTargets(List<DisassembledInstruction> insns) {
        Set<Long> targets = new HashSet<>();
        for (DisassembledInstruction ins : insns) {
            if (ins.mnemonic == null) continue;
            String mn = ins.mnemonic.toLowerCase();
            if (mn.equals("b") || mn.startsWith("b.") || mn.startsWith("cb") || mn.startsWith("tb")
                    || mn.equals("bl") || mn.equals("blx")) {
                Long tgt = extractHexAddr(ins.opStr);
                if (tgt != null) targets.add(tgt);
            }
        }
        return targets;
    }

    // ========== 查找方法 ==========

    /**
     * v2.9.30: 检查地址是否在数据段范围内 (.data/.bss/.rodata).
     */
    private boolean isDataAddress(long addr, ElfFile elf) {
        if (elf == null || elf.sectionHeaders == null) return false;
        for (SectionHeader sec : elf.sectionHeaders) {
            if (sec.shSize <= 0 || sec.shAddr == 0) continue;
            String name = sec.name != null ? sec.name : "";
            if (name.startsWith(".data") || name.startsWith(".bss") || name.startsWith(".rodata")
                    || name.equals(".got") || name.equals(".got.plt")) {
                if (addr >= sec.shAddr && addr < sec.shAddr + sec.shSize) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * v2.9.30: 第七组 — __stack_chk_fail 模式匹配.
     * 检测经典的栈金丝雀检查模式: LDR + CMP + B.NE → __stack_chk_fail
     */
    private boolean isStackChkPattern(List<DisassembledInstruction> insns, int idx, String mn) {
        // BL __stack_chk_fail
        if (mn.equals("bl") || mn.equals("blx")) {
            Long target = extractHexAddr(insns.get(idx).opStr);
            if (target != null) {
                String label = importLabels.get(target);
                if (label != null && label.contains("__stack_chk_fail")) {
                    return true;
                }
                // 也检查 funcLabels
                label = funcLabels.get(target);
                if (label != null && label.contains("__stack_chk_fail")) {
                    return true;
                }
            }
        }
        // B.NE → loc_XXX (跳到 __stack_chk_fail)
        if (mn.equals("b.ne") && idx > 0) {
            // 检查前一条是否 CMP, 前两条是否 LDR
            if (idx >= 2) {
                DisassembledInstruction prev1 = insns.get(idx - 1);
                DisassembledInstruction prev2 = insns.get(idx - 2);
                if (prev1.mnemonic != null && prev1.mnemonic.toLowerCase().equals("cmp")
                        && prev2.mnemonic != null && prev2.mnemonic.toLowerCase().startsWith("ldr")) {
                    // 检查跳转目标后面是否是 BL __stack_chk_fail
                    Long target = extractHexAddr(insns.get(idx).opStr);
                    if (target != null) {
                        // 在后续指令中查找
                        for (int j = idx + 1; j < Math.min(insns.size(), idx + 20); j++) {
                            if (insns.get(j).address == target) {
                                // 检查目标位置附近是否有 BL __stack_chk_fail
                                for (int k = j; k < Math.min(insns.size(), j + 5); k++) {
                                    String kmn = insns.get(k).mnemonic;
                                    if (kmn != null && (kmn.toLowerCase().equals("bl") || kmn.toLowerCase().equals("blx"))) {
                                        Long blTarget = extractHexAddr(insns.get(k).opStr);
                                        if (blTarget != null) {
                                            String blLabel = importLabels.get(blTarget);
                                            if (blLabel == null) blLabel = funcLabels.get(blTarget);
                                            if (blLabel != null && blLabel.contains("__stack_chk_fail")) {
                                                return true;
                                            }
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private String findLabelAt(long addr) {
        // 优先级: 数据符号 > 重定位符号 > 函数符号
        String label = dataLabels.get(addr);
        if (label != null) return label;
        label = relocLabels.get(addr);
        if (label != null) return label;
        label = funcLabels.get(addr);
        if (label != null) return label;
        label = importLabels.get(addr);
        return label;
    }

    private String findLabelInPage(long pageAddr) {
        // 在页范围内 (4KB) 查找符号
        for (Map.Entry<Long, String> e : dataLabels.entrySet()) {
            if ((e.getKey() & ~0xFFFL) == pageAddr) {
                return e.getValue();
            }
        }
        return null;
    }

    // ========== 解析辅助方法 ==========

    private String extractFirstReg(String opStr) {
        if (opStr == null) return null;
        Matcher m = REG_PATTERN.matcher(opStr);
        return m.find() ? m.group(1) : null;
    }

    private Long extractHexAddr(String opStr) {
        if (opStr == null) return null;
        Matcher m = HEX_PATTERN.matcher(opStr);
        if (m.find()) {
            try {
                return Long.parseUnsignedLong(m.group(1), 16);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Long extractImm(String str) {
        if (str == null) return null;
        str = str.trim();
        // 移除 lsl 等后缀
        int comma = str.indexOf(',');
        if (comma >= 0) str = str.substring(0, comma);
        str = str.trim();
        if (str.startsWith("#")) str = str.substring(1);
        try {
            if (str.startsWith("0x") || str.startsWith("0X")) {
                return Long.parseUnsignedLong(str.substring(2), 16);
            }
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long extractImmFromSubSp(String opStr) {
        if (opStr == null) return null;
        Matcher m = Pattern.compile("#(0x[0-9a-fA-F]+|\\d+)").matcher(opStr);
        if (m.find()) {
            String s = m.group(1);
            try {
                if (s.startsWith("0x")) return Long.parseUnsignedLong(s.substring(2), 16);
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Long extractSpOffset(String opStr) {
        if (opStr == null) return null;
        // 匹配 [sp, #0xNN] 或 [sp, #NN] 或 [sp, #0xNN]!
        Matcher m = Pattern.compile("\\[sp\\s*,\\s*#?(0x[0-9a-fA-F]+|\\d+)", Pattern.CASE_INSENSITIVE).matcher(opStr);
        if (m.find()) {
            String s = m.group(1);
            try {
                if (s.startsWith("0x") || s.startsWith("0X")) return Long.parseUnsignedLong(s.substring(2), 16);
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // 匹配 [sp] (偏移 0)
        if (opStr.toLowerCase().contains("[sp]")) return 0L;
        return null;
    }

    private Long resolveBaseAddr(String opStr, Map<String, Long> regPage, Map<String, Long> regFull) {
        if (opStr == null) return null;
        // 提取 [xN, #imm] 中的 xN
        Matcher m = Pattern.compile("\\[\\s*(x\\d{1,2}|w\\d{1,2})\\s*,\\s*#(0x[0-9a-fA-F]+|\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(opStr);
        if (m.find()) {
            String reg = m.group(1).toLowerCase();
            Long imm;
            String immStr = m.group(2);
            try {
                if (immStr.startsWith("0x")) imm = Long.parseUnsignedLong(immStr.substring(2), 16);
                else imm = Long.parseLong(immStr);
            } catch (NumberFormatException e) {
                return null;
            }
            // 先查完整地址
            Long full = regFull.get(reg);
            if (full != null) return full + imm;
            // 再查页地址
            Long page = regPage.get(reg);
            if (page != null) return page + imm;
        }
        // [xN] 无偏移
        m = Pattern.compile("\\[\\s*(x\\d{1,2}|w\\d{1,2})\\s*\\]", Pattern.CASE_INSENSITIVE).matcher(opStr);
        if (m.find()) {
            String reg = m.group(1).toLowerCase();
            Long full = regFull.get(reg);
            if (full != null) return full;
            Long page = regPage.get(reg);
            if (page != null) return page;
        }
        return null;
    }

    private String replaceLastImm(String opStr, String replacement) {
        if (opStr == null) return null;
        // 替换最后一个 #0xNN 或 #NN
        return opStr.replaceAll("(?i)#(0x[0-9a-fA-F]+|\\d+)(?!.*#.*)", replacement);
    }

    private String annotateMemOperand(String opStr, String annotation) {
        if (opStr == null) return null;
        // 替换 [xN, #imm] 中的 #imm 为符号
        return opStr.replaceAll("(?i)#(0x[0-9a-fA-F]+|\\d+)(\\s*\\])", "#" + annotation + "$2");
    }

    private String formatAddr(long addr) {
        return String.format("0x%x", addr);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ========== v2.9.37: 类型推断辅助方法 ==========

    /**
     * 类型编号转字符串.
     * 0=unknown, 1=ptr, 2=void*, 3=int, 4=char_ptr, 5=this, 6=float
     */
    private static String typeToString(int type) {
        switch (type) {
            case 1: return "ptr";
            case 2: return "void *";
            case 3: return "int";
            case 4: return "char *";
            case 5: return "this";
            case 6: return "float";
            default: return null;
        }
    }

    /**
     * v2.9.37: 根据函数签名推断参数类型, 并反向传播给寄存器.
     * AAPCS64: X0=arg0(通常this), X1=arg1, X2=arg2, ...
     * AAPCS32: R0=arg0(通常this), R1=arg1, R2=arg2, R3=arg3
     *
     * 反向约束逻辑:
     * 1. 根据签名中的参数类型, 给 X0-X7/R0-R3 打上类型标签
     * 2. 如果 X0/X1/R0/R1 是通过 MOV 从其他寄存器赋值的, 把类型反向传播给源寄存器
     */
    private void inferArgTypesFromSignature(String sig,
                                             Map<String, Integer> regType,
                                             Map<String, String[]> movSource,
                                             List<DisassembledInstruction> insns,
                                             boolean isArm64) {
        if (sig == null || sig.isEmpty()) return;

        // 提取参数列表 (括号内的部分)
        int parenStart = sig.indexOf('(');
        int parenEnd = sig.lastIndexOf(')');
        if (parenStart < 0 || parenEnd < 0) return;

        String args = sig.substring(parenStart + 1, parenEnd).trim();
        if (args.isEmpty() || args.equals("void")) return;

        // 分割参数
        String[] params = splitParams(args);
        int maxArgs = isArm64 ? 8 : 4;
        String regPrefix = isArm64 ? "x" : "r";
        for (int i = 0; i < params.length && i < maxArgs; i++) {
            String param = params[i].trim();
            int type = classifyParamType(param);
            if (type == 0) continue;

            String regName = regPrefix + i;
            // 给当前寄存器打上类型标签
            regType.put(regName, type);

            // v2.9.37: 反向传播 — 如果这个寄存器是通过 MOV 从其他寄存器赋值的,
            // 把类型也赋给源寄存器
            String[] src = movSource.get(regName);
            if (src != null) {
                String srcReg = src[0];
                int srcIdx = Integer.parseInt(src[1]);
                // 只在源寄存器类型为 unknown 时才覆盖 (不覆盖已确定的类型)
                Integer existingType = regType.get(srcReg);
                if (existingType == null || existingType == 0) {
                    regType.put(srcReg, type);

                    // 更新源 MOV 指令的 regType 注释
                    if (srcIdx >= 0 && srcIdx < insns.size()) {
                        DisassembledInstruction movIns = insns.get(srcIdx);
                        if (movIns.nativeRegType == null || movIns.nativeRegType.isEmpty()) {
                            movIns.nativeRegType = typeToString(type);
                        }
                    }
                }
            }
        }
    }

    /**
     * 分割参数列表 (处理逗号嵌套).
     */
    private String[] splitParams(String args) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                result.add(args.substring(start, i));
                start = i + 1;
            }
        }
        result.add(args.substring(start));
        return result.toArray(new String[0]);
    }

    /**
     * 根据参数类型字符串分类类型编号.
     * 0=unknown, 1=ptr, 2=void*, 3=int, 4=char_ptr, 5=this, 6=float
     */
    private static int classifyParamType(String param) {
        if (param == null || param.isEmpty()) return 0;
        String p = param.toLowerCase().trim();

        // char* / const char*
        if (p.contains("char") && (p.contains("*") || p.contains("&"))) return 4;
        // void*
        if (p.contains("void") && p.contains("*")) return 2;
        // this 指针 (C++ 方法调用)
        if (p.contains("this") || p.equals("a1") /* IDA 风格 */) return 5;
        // float / double
        if (p.contains("float") || p.contains("double")) return 6;
        // int / long / uint32 / size_t
        if (p.contains("int") || p.contains("long") || p.contains("size_t")
                || p.contains("uint") || p.contains("bool") || p.contains("char")) return 3;
        // 其他指针类型 (类名*, struct*, etc.)
        if (p.contains("*") || p.contains("&")) {
            // 如果是 char* 已经在上面处理了, 这里是其他指针
            return 2; // void*
        }
        return 0;
    }

    // ========== v2.9.40: 结构体偏移收集器 ==========

    /**
     * 从函数的指令列表和符号表中提取类名.
     * 例如: _ZNK11MoveControl8getSpeedEv → MoveControl
     *       _ZN5Level20getSpecialMultiplierE11DimensionId → Level
     */
    private String extractClassName(List<DisassembledInstruction> insns, ElfFile elf) {
        if (insns == null || insns.isEmpty()) return null;
        long funcAddr = insns.get(0).address;

        // 查符号表
        String symName = funcLabels.get(funcAddr);
        if (symName == null) symName = importLabels.get(funcAddr);
        if (symName == null && elf.symtabEntries != null) {
            for (SymbolEntry sym : elf.symtabEntries) {
                if (sym.stValue == funcAddr && sym.name != null && !sym.name.isEmpty()) {
                    symName = sym.name;
                    break;
                }
            }
        }
        if (symName == null) return null;

        // 解修饰
        Demangler.Result dr = Demangler.demangle(symName);
        String demangled = dr.supported ? dr.demangled : symName;

        // 提取类名: ClassName::methodName → ClassName
        int scopeIdx = demangled.indexOf("::");
        if (scopeIdx > 0) {
            // 去掉返回类型前缀 (如果有), 如 "struct* MoveControl::getSpeed()"
            String beforeScope = demangled.substring(0, scopeIdx).trim();
            // 取最后一个 token 作为类名 (去掉返回类型)
            String[] tokens = beforeScope.split("\\s+");
            String className = tokens[tokens.length - 1];
            // 去掉可能的指针/引用标记
            className = className.replace("*", "").replace("&", "").trim();
            if (!className.isEmpty()) {
                return className;
            }
        }
        return null;
    }

    /**
     * v2.9.40: 预扫描函数, 收集所有 this 指针偏移.
     * v2.9.42: 只推断类型, 不推断名称. 显示 (type)(this+offset).
     *
     * 策略:
     * 1. 入口 R0/X0 = this
     * 2. MOV Rn, R0 → Rn 也是 this (传播)
     * 3. 扫描所有 LDR/STR [Rn, #offset], 如果 Rn 是 this, 记录 offset + 推断类型
     */
    private void collectOffsetsFromFunction(List<DisassembledInstruction> insns,
                                            String className, boolean isArm64) {
        if (insns == null || insns.isEmpty() || className == null) return;

        // this 指针寄存器集合
        Set<String> thisRegs = new HashSet<>();
        String entryReg = isArm64 ? "x0" : "r0";
        thisRegs.add(entryReg);

        Map<Long, StructMember> members = structMembers.computeIfAbsent(className, k -> new HashMap<>());

        int limit = Math.min(insns.size(), MAX_INSNS);
        for (int i = 0; i < limit; i++) {
            DisassembledInstruction ins = insns.get(i);
            if (ins.mnemonic == null || ins.opStr == null) continue;
            String mn = ins.mnemonic.toLowerCase();
            String op = ins.opStr;

            // MOV Rn, R0 → Rn 也是 this (传播)
            if (mn.equals("mov") || mn.equals("movz")) {
                String[] parts = op.split(",");
                if (parts.length >= 2) {
                    String dst = parts[0].trim().toLowerCase();
                    String src = parts[1].trim().toLowerCase();
                    if (thisRegs.contains(src)) {
                        thisRegs.add(dst);
                    }
                }
            }

            // v2.9.44: 支持所有内存访问指令收集偏移 (含 VFP vldr/vstr)
            boolean isMem = mn.startsWith("ldr") || mn.startsWith("ldur")
                    || mn.startsWith("str") || mn.startsWith("stur")
                    || mn.equals("stp") || mn.equals("ldp")
                    || mn.equals("stpd") || mn.equals("ldpd")
                    || mn.startsWith("vldr") || mn.startsWith("vstr")
                    || mn.startsWith("vldm") || mn.startsWith("vstm");
            if (isMem && op.contains("[")) {
                int bStart = op.indexOf('[');
                int bEnd = op.indexOf(']', bStart);
                if (bStart >= 0 && bEnd > bStart) {
                    String bracket = op.substring(bStart + 1, bEnd).trim();
                    int comma = bracket.indexOf(',');
                    if (comma >= 0) {
                        String baseReg = bracket.substring(0, comma).trim().toLowerCase();
                        if (thisRegs.contains(baseReg)) {
                            String offPart = bracket.substring(comma + 1).trim();
                            if (offPart.startsWith("#")) offPart = offPart.substring(1).trim();
                            try {
                                long offset;
                                if (offPart.startsWith("0x") || offPart.startsWith("0X")) {
                                    offset = Long.parseUnsignedLong(offPart.substring(2), 16);
                                } else {
                                    offset = Long.parseLong(offPart);
                                }
                                if (offset >= 0 && offset < 0x10000) {
                                    // 获取或创建成员信息 (只推断类型)
                                    StructMember member = members.computeIfAbsent(offset, StructMember::new);
                                    if (member.type == null) {
                                        member.type = inferMemberType(insns, i, mn, op, isArm64);
                                    }
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }

            // v2.9.45: ADD/ADDS 指令收集偏移 (adds r0, #0x1c → this+0x1c)
            if (mn.equals("add") || mn.equals("adds") || mn.equals("add.w")) {
                String[] parts = op.split(",");
                if (parts.length >= 2) {
                    String dst = parts[0].trim().toLowerCase();
                    String lastPart = parts[parts.length - 1].trim();
                    if (lastPart.startsWith("#")) {
                        String offPart = lastPart.substring(1).trim();
                        long offset = -1;
                        try {
                            if (offPart.startsWith("0x") || offPart.startsWith("0X")) {
                                offset = Long.parseUnsignedLong(offPart.substring(2), 16);
                            } else {
                                offset = Long.parseLong(offPart);
                            }
                        } catch (NumberFormatException ignored) {}

                        if (offset >= 0 && offset < 0x10000) {
                            String srcReg = (parts.length >= 3) ? parts[1].trim().toLowerCase() : dst;
                            if (thisRegs.contains(srcReg)) {
                                StructMember member = members.computeIfAbsent(offset, StructMember::new);
                                if (member.type == null) {
                                    member.type = inferMemberType(insns, i, mn, op, isArm64);
                                }
                            }
                        }
                    }
                }
            }

            // BL 后 this 寄存器可能被破坏 (R0 变为返回值), 但 R4-R7 保持
            if (mn.equals("bl") || mn.equals("blx") || mn.equals("blr")) {
                if (isArm64) {
                    thisRegs.remove("x0");
                    thisRegs.remove("w0");
                } else {
                    thisRegs.remove("r0");
                }
            }
        }
    }

    /**
     * v2.9.41: 从指令上下文推断成员类型.
     *
     * 规则:
     * - LDR Wn (32位) + 后续 VMOV → float
     * - LDR Xn/Wn (32位) + 后续 BL(指针参数) → void *
     * - LDR Wn + 后续 CMP/ADD → int
     * - STR (写入) + 源是 VMOV → float
     */
    private String inferMemberType(List<DisassembledInstruction> insns, int idx,
                                    String mn, String op, boolean isArm64) {
        // 看后续 5 条指令
        int end = Math.min(insns.size(), idx + 6);
        boolean usedAsFloat = false;
        boolean usedAsPtr = false;
        boolean usedAsInt = false;

        // 提取加载的目标寄存器 (LDR Rn, [...])
        String loadedReg = null;
        if (mn.startsWith("ldr") || mn.startsWith("ldur")) {
            int comma = op.indexOf(',');
            if (comma > 0) {
                loadedReg = op.substring(0, comma).trim().toLowerCase();
            }
        }

        for (int j = idx + 1; j < end; j++) {
            DisassembledInstruction next = insns.get(j);
            if (next.mnemonic == null || next.opStr == null) continue;
            String nmn = next.mnemonic.toLowerCase();
            String nop = next.opStr.toLowerCase();

            // VMOV / VSTR / VLDR → float
            if (nmn.startsWith("vmov") || nmn.startsWith("vldr") || nmn.startsWith("vstr")
                    || nmn.startsWith("vadd") || nmn.startsWith("vmul") || nmn.startsWith("vcmp")) {
                usedAsFloat = true;
            }

            // 检查 loadedReg 是否被用作 BL 的参数 (指针)
            if (loadedReg != null && (nmn.equals("bl") || nmn.equals("blx") || nmn.equals("blr"))) {
                usedAsPtr = true;
            }

            // 检查 loadedReg 是否参与算术 (整数)
            if (loadedReg != null && (nmn.equals("add") || nmn.equals("sub")
                    || nmn.equals("cmp") || nmn.equals("mul"))) {
                if (nop.contains(loadedReg)) {
                    usedAsInt = true;
                }
            }

            // BX LR / B → 函数结束, 停止
            if (nmn.equals("bx") || nmn.equals("b") || nmn.equals("ret")) {
                break;
            }
        }

        // 优先级: float > ptr > int
        if (usedAsFloat) return "float";
        if (usedAsPtr) return "void *";
        if (usedAsInt) return "int";

        // v2.9.43: 根据指令助记符推断类型
        // v2.9.44: VFP 指令直接推断为 float/double
        // vldr s15, [r0, #4] → float (单精度)
        // vldr d0, [r0, #8]  → double (双精度)
        if (mn.startsWith("vldr") || mn.startsWith("vstr")) {
            // vldr Sn → float, vldr Dn → double
            if (op.startsWith("d") || op.startsWith("D")) return "double";
            if (op.startsWith("s") || op.startsWith("S")) return "float";
            return "float"; // 默认 float
        }
        if (mn.startsWith("vldm") || mn.startsWith("vstm")) {
            return "float"; // 批量加载/存储, 默认 float
        }

        // ldrb/ldurb → byte (unsigned char)
        // ldrsb/ldursb → signed char
        // ldrh/ldurh → short
        // ldrsh/ldursh → signed short
        // ldr/ldur (32位 ARM) → int
        // ldr Xn (64位 ARM64) → void *
        // str → 与源寄存器宽度一致
        if (mn.startsWith("ldrb") || mn.startsWith("ldurb")) return "unsigned char";
        if (mn.startsWith("ldrsb") || mn.startsWith("ldursb")) return "char";
        if (mn.startsWith("ldrh") || mn.startsWith("ldurh")) return "unsigned short";
        if (mn.startsWith("ldrsh") || mn.startsWith("ldursh")) return "short";

        if (mn.startsWith("strb") || mn.startsWith("sturb")) return "unsigned char";
        if (mn.startsWith("strsb") || mn.startsWith("stursb")) return "char";
        if (mn.startsWith("strh") || mn.startsWith("sturh")) return "unsigned short";

        if (mn.startsWith("ldr") || mn.startsWith("ldur")) {
            // ARM64: LDR Wn → int, LDR Xn → void *
            if (op.startsWith("w") || op.startsWith("W")) return "int";
            if (op.startsWith("x") || op.startsWith("X")) return "void *";
            // ARM32: LDR Rn → int (32位)
            return "int";
        }

        // STR: 默认根据源寄存器宽度
        if (mn.startsWith("str") || mn.startsWith("stur")) {
            return "int";
        }

        // v2.9.45: ADD/ADDS 计算地址 → 默认 void *
        if (mn.equals("add") || mn.equals("adds") || mn.equals("add.w")) {
            return "void *";
        }

        return null;
    }
}

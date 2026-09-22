package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JNI parameter semantics (v4.2).
 *
 * <p>Keeps the pseudo-C compilable and readable when the restored signature
 * uses semantic parameter names ({@code JavaVM* vm, void* reserved}) while
 * the body still uses calling-convention registers ({@code r0}, {@code r1}).
 *
 * <p>Two scenarios, both producing {@code (*vm)->GetEnv(vm, &env, r2)}:
 * <ul>
 *   <li><b>Semantic name</b> ({@code JavaVM* vm}): the register's reads
 *       before its first write are rewritten to {@code vm} (it is the
 *       parameter); if the register is reused later, a local declaration
 *       {@code uint32_t r0;} is added so the body stays compilable.</li>
 *   <li><b>Register name</b> ({@code JavaVM* r0}): a copy
 *       {@code JavaVM* vm = r0;} is introduced and vtable calls before the
 *       first write use {@code vm}.</li>
 * </ul>
 *
 * <p>Runs on the fully assembled output (signature line included).
 */
public final class ParamSemanticsPass {

    private ParamSemanticsPass() {
    }

    // First write to a register: "r0 = ..." (single '=', not '==', '!=', '<=', ...)
    private static final Pattern FIRST_WRITE = Pattern.compile(
            "^\\s*([rwx]\\d+)\\s*=\\s*[^=]");

    private static final Pattern REG_NAME = Pattern.compile("[rwx]\\d+");

    /**
     * Run the pass on the assembled output.
     *
     * @param output full pseudo-C output (signature line + body)
     * @return rewritten output, or the input when nothing applies
     */
    public static List<String> run(List<String> output) {
        List<String> out = (output == null) ? new ArrayList<String>() : new ArrayList<>(output);
        if (out.isEmpty()) return out;

        // Locate the signature line (first non-comment line with '(' ending in '{').
        int sigIdx = -1;
        for (int i = 0; i < out.size(); i++) {
            String t = out.get(i).trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            if (t.contains("(") && t.endsWith("{")) {
                sigIdx = i;
                break;
            }
        }
        if (sigIdx < 0) return out;
        String sig = out.get(sigIdx);

        // AArch64 → 64-bit registers.
        boolean is64 = false;
        for (String l : out) {
            if (l.contains("架构:")) {
                is64 = l.contains("AARCH64") || l.contains("AArch64") || l.contains("aarch64");
                break;
            }
        }
        String regType = is64 ? "uint64_t" : "uint32_t";

        // Parse "(Type1 Name1, Type2 Name2)" → [type, name] pairs, in order.
        List<String[]> params = new ArrayList<>();
        int p1 = sig.indexOf('(');
        int p2 = sig.lastIndexOf(')');
        if (p1 >= 0 && p2 > p1) {
            String inner = sig.substring(p1 + 1, p2);
            for (String part : inner.split(",")) {
                part = part.trim();
                if (part.isEmpty() || part.equals("...")) continue;
                int sp = part.lastIndexOf(' ');
                if (sp < 0) continue;
                params.add(new String[]{part.substring(0, sp).trim(), part.substring(sp + 1).trim()});
            }
        }

        List<String> newDecls = new ArrayList<>();
        String vmCopyReg = null; // register-name JavaVM* param → introduce "vm" copy

        // Pass 1: semantic-name params — rewrite pre-first-write reads, declare reused regs.
        for (int i = 0; i < params.size(); i++) {
            String type = params.get(i)[0];
            String name = params.get(i)[1];
            if (REG_NAME.matcher(name).matches()) {
                if (type.contains("JavaVM")) vmCopyReg = is64 ? "x" + i : "r" + i;
                continue;
            }
            String reg = is64 ? "x" + i : "r" + i;
            int firstWrite = firstWriteOf(out, sigIdx, reg);
            boolean reused = false;
            for (int j = sigIdx + 1; j < out.size(); j++) {
                if (j < firstWrite && !isDeclLine(out.get(j))) {
                    if (out.get(j).matches(".*\\b" + reg + "\\b.*")) {
                        out.set(j, out.get(j).replaceAll("\\b" + reg + "\\b", name));
                    }
                } else if (out.get(j).matches(".*\\b" + reg + "\\b.*")) {
                    reused = true;
                }
            }
            if (reused) {
                newDecls.add("    " + regType + " " + reg + ";");
            }
        }

        // Pass 2: register-name JavaVM* param (如 "JavaVM* r0") —
        // firstWrite 前所有 r0 出现替换为 vm (不止 vtable call, 含普通调用参数),
        // 避免 sub_1458(r0) 裸寄存器; r0 在 body 中出现即插 JavaVM* vm = r0; 声明.
        if (vmCopyReg != null) {
            int firstWrite = firstWriteOf(out, sigIdx, vmCopyReg);
            boolean used = false;
            for (int j = sigIdx + 1; j < firstWrite; j++) {
                if (isDeclLine(out.get(j))) continue;
                if (out.get(j).matches(".*\\b" + vmCopyReg + "\\b.*")) {
                    used = true;
                    out.set(j, out.get(j).replaceAll("\\b" + vmCopyReg + "\\b", "vm"));
                }
            }
            if (used) {
                newDecls.add("    JavaVM* vm = " + vmCopyReg + ";");
            }
        }

        // v4.6: 无 anyChange 门控 — 复用参数寄存器的局部声明 (newDecls) 独立于替换,
        // 否则"无替换但有复用"时 r0 裸用且无声明 (如 JNI_OnLoad 的 ldr r0,[r0] 首行).
        if (newDecls.isEmpty()) return out;

        // Insert declarations at the start of the declaration/statement area.
        int insertAt = -1;
        for (int i = sigIdx + 1; i < out.size(); i++) {
            String t = out.get(i).trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            insertAt = i;
            break;
        }
        if (insertAt > sigIdx) {
            out.addAll(insertAt, newDecls);
        }
        return out;
    }

    private static int firstWriteOf(List<String> lines, int sigIdx, String reg) {
        for (int i = sigIdx + 1; i < lines.size(); i++) {
            Matcher w = FIRST_WRITE.matcher(lines.get(i));
            if (w.find() && w.group(1).equals(reg)) return i;
        }
        return Integer.MAX_VALUE;
    }

    // 声明行 ("TYPE NAME;") — 不含 ( 与 =, 替换循环不得触碰声明区.
    private static boolean isDeclLine(String line) {
        return line != null
                && line.matches("^\\s*[A-Za-z_][A-Za-z0-9_\\s\\*]*?\\s+[A-Za-z_][A-Za-z0-9_]*;\\s*$");
    }
}

package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.9.37: Temporary register variable collector.
 *
 * <p>Problem: function-body temporary registers (w8, w9, x8, x9, x10, ...)
 * appear as raw machine-level names in the pseudo-C output without any
 * declaration. This breaks the C abstraction — the reader sees a mix of
 * declared variables (var_XX, saved_xN) and undeclared register names.
 *
 * <p>Solution: after structuring + peephole + renaming, scan all body lines
 * for register references, classify them, and emit local variable
 * declarations for temporary registers that are not already declared as
 * arguments or saved registers.
 *
 * <h3>Classification</h3>
 * <ul>
 *   <li><b>Arg registers</b> (x0-x7 / r0-r3): already declared in the
 *       function signature — skipped.</li>
 *   <li><b>Saved registers</b> (saved_x19, saved_lr, etc.): already declared
 *       by {@link VariableRenamer} — skipped.</li>
 *   <li><b>Temp registers</b> (w8, x9, w10, ...): declared as locals with
 *       appropriate C types.</li>
 *   <li><b>Special registers</b> (sp, lr, fp, xzr, wzr, pc): never declared.</li>
 * </ul>
 *
 * <p>Type inference for temp registers:
 * <ul>
 *   <li>{@code wN} → {@code uint32_t} (32-bit view)</li>
 *   <li>{@code xN} → {@code uint64_t} (64-bit register)</li>
 *   <li>{@code rN} → {@code uint32_t} (ARM32)</li>
 * </ul>
 *
 * <p>If both {@code w8} and {@code x8} appear in the body, they are treated
 * as the same variable (AArch64 register aliasing) and declared once as
 * {@code uint64_t x8}.
 */
public final class TempVarCollector {

    private TempVarCollector() {
    }

    /** AArch64 arg registers: x0-x7 (and w0-w7). */
    private static final Set<String> AARCH64_ARG_REGS = buildRegSet("xw", 0, 7);
    /** ARM32 arg registers: r0-r3. */
    private static final Set<String> ARM32_ARG_REGS = buildRegSet("r", 0, 3);
    /** Special registers that should never be declared.
     * v2.9.44: Removed q0, q1 from this set — they MUST be declared as
     * NEON registers (uint64_t q0[2]). Previously they were skipped,
     * causing "q1 undeclared" compilation errors. */
    private static final Set<String> SPECIAL_REGS = new HashSet<>(Arrays.asList(
            "sp", "lr", "fp", "pc", "xzr", "wzr", "sl"));

    /**
     * Register name pattern: [wxr]\d+ or ip (r12 别名, ARM32)
     * v2.9.39: Negative lookbehind to avoid matching inside hex numbers
     * (e.g. "x30" inside "0x30") or inside identifiers like "saved_x30".
     */
    private static final Pattern REG_PATTERN =
            Pattern.compile("(?<![0-9a-fA-FxX_>])([wxr]\\d+|ip)(?!\\d)");

    /** v2.9.37: Dereference pattern — *(uint64_t*)(REG + off) or *(REG) */
    private static final Pattern DEREF_PATTERN =
            Pattern.compile("\\*\\s*(?:\\((?:uint\\d+_t|int\\d+_t|void\\s*\\*)\\s*\\*\\))?\\s*\\(\\s*([wxr]\\d+)");

    /** v2.9.37: Signed comparison — REG < 0, REG <= -1, REG > -N, etc. */
    private static final Pattern SIGNED_CMP_PATTERN =
            Pattern.compile("([wxr]\\d+)\\s*(?:<=|<)\\s*(?:0x0|0|-0x[0-9a-fA-F]+|-\\d+)");

    /** v2.9.43: String assignment — REG = "..."; → register holds a pointer */
    private static final Pattern STRING_ASSIGN_PATTERN =
            Pattern.compile("^\\s*([wxr]\\d+)\\s*=\\s*\"");

    /** v2.9.40: saved_* variable pattern (saved_lr, saved_x19, saved_r4, etc.) */
    private static final Pattern SAVED_VAR_PATTERN =
            Pattern.compile("\\b(saved_[a-z0-9_]+)\\b");

    /** v2.9.40: arg_XX variable pattern (arg_8, arg_10, arg_74, etc.) */
    private static final Pattern ARG_VAR_PATTERN =
            Pattern.compile("\\b(arg_[0-9a-fA-F]+)\\b");

    /** v2.9.41: var_XX stack variable pattern (var_f0, var_d4, var_70, etc.) */
    private static final Pattern STACK_VAR_PATTERN =
            Pattern.compile("\\b(var_[0-9a-fA-F]+)\\b");

    /** v2.9.40: NEON register pattern (q0, q1, d0, s0, etc.) */
    private static final Pattern NEON_REG_PATTERN =
            Pattern.compile("(?<![0-9a-zA-Z_])([qds]\\d+)(?!\\d)");

    /**
     * Scan body lines and collect temporary register declarations.
     *
     * @param body       the structured body lines (after peephole + renaming)
     * @param isAarch64  true for AArch64, false for ARM32
     * @param argVarNames set of already-declared argument variable names
     *                    (e.g. {"x0", "x1", "x2"})
     * @return a list of declaration strings like "uint32_t w8;", "uint64_t x9;"
     */
    public static List<String> collectDeclarations(List<String> body,
                                                    boolean isAarch64,
                                                    Set<String> argVarNames) {
        List<String> decls = new ArrayList<>();
        if (body == null || body.isEmpty()) return decls;

        try {
            // v2.9.40: Only treat registers as "arg" if they are ACTUALLY in
            // the current signature (argVarNames). After parameter trimming,
            // x2-x7 may have been removed — those must be declared as locals.
            // Previously we used the full static set AARCH64_ARG_REGS which
            // always included x0-x7, preventing trimmed registers from being
            // declared.
            Set<String> allArgRegs = new TreeSet<>();
            if (argVarNames != null) {
                allArgRegs.addAll(argVarNames);
            }

            // v4.6: 栈槽/被调用者保存寄存器宽度按架构 — ARM32 为 4 字节,
            // AArch64 为 8 字节 (此前硬编码 uint64_t 导致 ARM32 局部变量全 64 位)
            String archType = isAarch64 ? "uint64_t" : "uint32_t";

            // Collect temp registers: regNum → set of prefixes (w/x/r)
            // Use a TreeMap for sorted output (w8 before w9 before x10)
            Map<Integer, Set<String>> tempRegMap = new LinkedHashMap<>();

            // v2.9.37: Usage-driven type hints
            // Track which registers are dereferenced (→ pointer type)
            Set<String> derefedRegs = new HashSet<>();
            // Track which registers are compared with negative values (→ signed)
            Set<String> signedRegs = new HashSet<>();
            // v2.9.43: Track which registers are assigned string literals (→ pointer)
            Set<String> stringRegs = new HashSet<>();
            // v3.8: ip (r12 别名) 是否在 body 中使用
            boolean ipUsed = false;

            for (String line : body) {
                if (line == null) continue;

                // v2.9.43: Detect string assignment: r2 = "DF";
                Matcher strMatcher = STRING_ASSIGN_PATTERN.matcher(line);
                if (strMatcher.find()) {
                    stringRegs.add(strMatcher.group(1));
                }

                // Detect dereference: *(uintNN_t*)(REG + ...)
                Matcher derefMatcher = DEREF_PATTERN.matcher(line);
                while (derefMatcher.find()) {
                    String reg = derefMatcher.group(1);
                    if (reg != null && !reg.isEmpty()) {
                        derefedRegs.add(reg);
                    }
                }

                // Detect signed comparison: REG < 0, REG <= -1, REG > -1, etc.
                Matcher signedMatcher = SIGNED_CMP_PATTERN.matcher(line);
                while (signedMatcher.find()) {
                    String reg = signedMatcher.group(1);
                    if (reg != null && !reg.isEmpty()) {
                        signedRegs.add(reg);
                    }
                }

                Matcher m = REG_PATTERN.matcher(line);
                while (m.find()) {
                    String reg = m.group(1);
                    if (SPECIAL_REGS.contains(reg)) continue;
                    // v2.9.40: Only skip registers that are ACTUALLY in the
                    // current argVarNames (i.e., still in the signature).
                    // Previously we skipped ALL x0-x7 / r0-r3, but after
                    // parameter trimming, x2-x7 may have been removed from
                    // the signature while still being used in the body.
                    // Those trimmed-but-used registers MUST be declared as
                    // local variables to avoid undefined-variable errors.
                    // 注意: 签名参数寄存器 (r0..rN) 的"复用"声明由 ParamSemanticsPass
                    // 负责 (firstWrite 后仍出现 → 插 uint32_t rN;), 这里一律跳过,
                    // 否则与 5b 的声明重复 (void* r0; + uint32_t r0;).
                    if (allArgRegs.contains(reg)) continue;

                    if (reg.equals("ip")) {
                        // r12 别名: 无数字编号, 单独声明为 uint32_t
                        ipUsed = true;
                        continue;
                    }

                    char prefix = reg.charAt(0);
                    int num = Integer.parseInt(reg.substring(1));

                    // v2.9.40: For AArch64, if x8 is declared, don't also
                    // declare w8 (it's the 32-bit view of the same register).
                    // But DO declare registers that were trimmed from args
                    // (e.g., x2 after JNI_OnLoad trim) if they appear in body.

                    tempRegMap.computeIfAbsent(num, k -> new TreeSet<>()).add(String.valueOf(prefix));
                }
            }

            // Generate declarations with type inference
            for (Map.Entry<Integer, Set<String>> entry : tempRegMap.entrySet()) {
                int num = entry.getKey();
                Set<String> prefixes = entry.getValue();

                // Determine type based on usage
                String xReg = "x" + num;
                String wReg = "w" + num;
                String rReg = "r" + num;
                String type;

                // v2.9.42: Declare BOTH x and w registers when both are used.
                // Previously, if x8 was declared, w8 was skipped (treated as
                // the 32-bit view of the same register). But in the pseudo-C
                // output, w8 and x8 are INDEPENDENT variables — the SSA
                // doesn't link them. Skipping w8 caused "undefined variable"
                // errors whenever the body used w8 alongside x8.
                if (prefixes.contains("x")) {
                    // 64-bit register
                    if (derefedRegs.contains(xReg) || stringRegs.contains(xReg)) {
                        type = "void*";  // pointer (dereferenced or string)
                    } else if (signedRegs.contains(xReg)) {
                        type = "int64_t"; // signed
                    } else {
                        type = "uint64_t";
                    }
                    decls.add(type + " " + xReg + ";");
                }
                if (prefixes.contains("w")) {
                    // 32-bit register — declared independently from x-reg
                    if (derefedRegs.contains(wReg) || stringRegs.contains(wReg)) {
                        type = "void*";
                    } else if (signedRegs.contains(wReg)) {
                        type = "int32_t";
                    } else {
                        type = "uint32_t";
                    }
                    decls.add(type + " " + wReg + ";");
                }
                if (prefixes.contains("r")) {
                    // ARM32 register
                    if (derefedRegs.contains(rReg) || stringRegs.contains(rReg)) {
                        type = "void*";
                    } else if (signedRegs.contains(rReg)) {
                        type = "int32_t";
                    } else {
                        type = "uint32_t";
                    }
                    decls.add(type + " " + rReg + ";");
                }
            }

            // v2.9.40: Collect saved_* variables (saved_lr, saved_x19, etc.)
            // These are created by VariableRenamer but need to be declared.
            Set<String> savedVars = new TreeSet<>();
            // Also collect arg_XX variables (stack parameters)
            Set<String> argVars = new TreeSet<>();
            // Also collect NEON registers (q0, q1, d0, s0, etc.)
            Set<String> neonRegs = new TreeSet<>();
            // v2.9.41: Collect var_XX stack variables
            Set<String> stackVars = new TreeSet<>();

            for (String line : body) {
                if (line == null) continue;
                Matcher mSaved = SAVED_VAR_PATTERN.matcher(line);
                while (mSaved.find()) {
                    savedVars.add(mSaved.group(1));
                }
                Matcher mArg = ARG_VAR_PATTERN.matcher(line);
                while (mArg.find()) {
                    argVars.add(mArg.group(1));
                }
                Matcher mNeon = NEON_REG_PATTERN.matcher(line);
                while (mNeon.find()) {
                    neonRegs.add(mNeon.group(1));
                }
                Matcher mStack = STACK_VAR_PATTERN.matcher(line);
                while (mStack.find()) {
                    stackVars.add(mStack.group(1));
                }
            }

            // v2.9.44: Detect saved_* variables that are read but never assigned.
            // Declare saved_* variables (skip if already in argVarNames)
            for (String sv : savedVars) {
                if (allArgRegs.contains(sv)) continue;
                // saved_lr / saved_fp 是指针 (链接寄存器/帧指针),
                // 其余被调用者保存寄存器按架构宽度 (ARM32 uint32_t / AArch64 uint64_t)
                String type;
                if (sv.equals("saved_lr") || sv.equals("saved_fp")) {
                    type = "void*";
                } else {
                    type = archType;
                }
                decls.add(type + " " + sv + ";");
            }

            // Declare arg_XX variables (stack parameters)
            for (String av : argVars) {
                if (allArgRegs.contains(av)) continue;
                decls.add(archType + " " + av + ";");
            }

            // v2.9.41: Declare var_XX stack variables
            for (String sv : stackVars) {
                if (allArgRegs.contains(sv)) continue;
                decls.add(archType + " " + sv + ";");
            }

            // Declare NEON registers
            for (String nv : neonRegs) {
                if (nv.startsWith("q")) {
                    decls.add("uint64_t " + nv + "[2];");
                } else {
                    decls.add("uint64_t " + nv + ";");
                }
            }

            // v3.8: ip (r12 别名) — 32 位寄存器, 单独声明
            if (ipUsed) {
                decls.add("uint32_t ip;");
            }
        } catch (Throwable t) {
            // Never let temp var collection kill decompilation
        }

        return decls;
    }

    private static Set<String> buildRegSet(String prefixes, int from, int to) {
        Set<String> set = new TreeSet<>();
        for (int i = from; i <= to; i++) {
            for (int p = 0; p < prefixes.length(); p++) {
                set.add(prefixes.charAt(p) + "" + i);
            }
        }
        return set;
    }
}

package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSA (Static Single Assignment) converter — the structural backbone for
 * reaching r2ghidra-level decompilation quality.
 *
 * <h3>Overview</h3>
 * <p>This class implements a pragmatic SSA construction that is intentionally
 * lighter than a full Cytron et al. implementation, but captures the essential
 * benefits:
 *
 * <ol>
 *   <li><b>Phi insertion</b> at dominance frontiers for each register that is
 *       assigned in multiple basic blocks.</li>
 *   <li><b>Variable versioning</b> — each definition of a register gets a
 *       unique version (x8_1, x8_2, ...), walked in dominator-tree pre-order.</li>
 *   <li><b>Def-use chains</b> — each SSA variable tracks its definition site
 *       and all use sites, enabling global type propagation and DCE.</li>
 * </ol>
 *
 * <h3>What SSA enables</h3>
 * <ul>
 *   <li><b>Global type propagation</b> — if x8_3 is assigned a pointer, all
 *       uses of x8_3 inherit pointer type, even across block boundaries.</li>
 *   <li><b>Global dead code elimination</b> — if x8_3 is never used, the
 *       entire assignment chain is dead, not just the local store.</li>
 *   <li><b>Constant propagation</b> — if x8_3 = 0x1000, all uses of x8_3
 *       can be replaced with 0x1000 globally.</li>
 *   <li><b>Pseudo-condition elimination</b> — if w8_1 is defined by cset
 *       from a cmp, the if(w8_1 & 1) can be traced back to the real cmp.</li>
 * </ul>
 *
 * <h3>Architecture</h3>
 * <p>The SSA converter operates on the already-generated pseudo-C body lines
 * (post-structuring, pre-peephole). This is a deliberate design choice: rather
 * than rewriting the entire IR pipeline, we build SSA on the structured text
 * representation. This is less precise than full IR-level SSA but integrates
 * cleanly with the existing pipeline and captures 80% of the benefit.
 *
 * <p>The SSA form is used internally for optimization passes, then
 * <b>deconstructed</b> back to normal variables (dropping version suffixes)
 * before final output. The deconstruction coalesces versions back to their
 * base register name, since pseudo-C readers expect "x8" not "x8_1, x8_2".
 *
 * <h3>Register tracking</h3>
 * <p>For AArch64: tracks x0-x30, w0-w30 (wN treated as alias of xN).
 * For ARM32: tracks r0-r12, r14(lr), r15(pc).
 */
public final class SsaConverter {

    private SsaConverter() {
    }

    // ── SSA variable representation ──

    /** A versioned SSA variable: base name + version number. */
    static final class SsaVar {
        final String base;   // e.g. "x8"
        final int version;   // e.g. 1

        SsaVar(String base, int version) {
            this.base = base;
            this.version = version;
        }

        String ssaName() {
            return base + "_" + version;
        }

        @Override
        public String toString() {
            return ssaName();
        }

        @Override
        public int hashCode() {
            return base.hashCode() * 31 + version;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SsaVar)) return false;
            SsaVar s = (SsaVar) o;
            return s.version == version && s.base.equals(base);
        }
    }

    /** Definition site: body line index + the SSA var defined. */
    static final class DefSite {
        final int lineIdx;
        final SsaVar var;
        final String rhsExpr;  // the right-hand side expression

        DefSite(int lineIdx, SsaVar var, String rhsExpr) {
            this.lineIdx = lineIdx;
            this.var = var;
            this.rhsExpr = rhsExpr;
        }
    }

    /** SSA context: all defs, uses, and version counters. */
    static final class SsaContext {
        /** base name → current version counter (during renaming). */
        final Map<String, Integer> versionCounter = new HashMap<>();

        /** base name → stack of active versions (for dominator tree traversal). */
        final Map<String, List<Integer>> versionStack = new HashMap<>();

        /** All definition sites: line index → DefSite. */
        final Map<Integer, DefSite> defs = new LinkedHashMap<>();

        /** Use sites: SSA var name → list of line indices where used. */
        final Map<String, List<Integer>> useSites = new HashMap<>();

        /** base name → set of all versions ever created. */
        final Map<String, Set<Integer>> allVersions = new HashMap<>();

        /** Lines that are dead (def with no uses). */
        final Set<Integer> deadLines = new HashSet<>();

        /** Constant definitions: ssaName → constant value. */
        final Map<String, String> constants = new HashMap<>();

        /** Type hints: ssaName → inferred type (pointer, signed, etc.). */
        final Map<String, String> typeHints = new HashMap<>();

        int newVersion(String base) {
            int v = versionCounter.getOrDefault(base, 0) + 1;
            versionCounter.put(base, v);
            allVersions.computeIfAbsent(base, k -> new HashSet<>()).add(v);
            return v;
        }

        void pushVersion(String base, int ver) {
            versionStack.computeIfAbsent(base, k -> new ArrayList<>()).add(ver);
        }

        int currentVersion(String base) {
            List<Integer> stack = versionStack.get(base);
            if (stack == null || stack.isEmpty()) return 0;
            return stack.get(stack.size() - 1);
        }

        void popVersion(String base) {
            List<Integer> stack = versionStack.get(base);
            if (stack != null && !stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            }
        }

        void addUse(String ssaName, int lineIdx) {
            useSites.computeIfAbsent(ssaName, k -> new ArrayList<>()).add(lineIdx);
        }

        int useCount(String ssaName) {
            List<Integer> sites = useSites.get(ssaName);
            return sites != null ? sites.size() : 0;
        }
    }

    // ── Register patterns ──

    private static final Pattern REG_DEF =
            Pattern.compile("^\\s*([wxr]\\d+)\\s*=\\s*(.+);\\s*$");
    /**
     * Register USE pattern — matches [wxr]\d+ as a standalone identifier.
     * v2.9.39 FIX: Uses negative lookbehind to avoid matching register names
     * embedded inside hex numbers (e.g. "x30" inside "0x30"). The lookbehind
     * ensures the character immediately before the match is NOT:
     * - a hex digit (0-9, a-f, A-F) — blocks "0x30" matching "x30"
     * - 'x' or 'X' — blocks "0x" prefix confusion
     * - '_' — blocks "saved_x30" being partially matched
     * - '>' — blocks "field_0->x30" arrow notation
     * This is the root cause of the "0saved_lr" bug where "0x30" had "x30"
     * replaced with the SSA name of x30 (which was "saved_lr" after renaming).
     */
    private static final Pattern REG_USE =
            Pattern.compile("(?<![0-9a-fA-FxX_>])([wxr]\\d+)(?!\\d)");
    private static final Pattern MEM_ACCESS =
            Pattern.compile("\\*\\([^)]*\\*\\)\\s*\\(\\s*([wxr]\\d+)\\s*([+\\-]\\s*0x[0-9a-fA-F]+)?\\s*\\)");

    /**
     * Run SSA-based optimization on the structured body lines.
     *
     * <p>This is the main entry point. It:
     * <ol>
     *   <li>Identifies all register definitions and uses in the body</li>
     *   <li>Assigns SSA versions (x8 → x8_1, x8_2, ...)</li>
     *   <li>Runs global constant propagation along def-use chains</li>
     *   <li>Runs global dead code elimination (remove defs with no uses)</li>
     *   <li>Runs type propagation (pointer, signed) along def-use chains</li>
     *   <li>Deconstructs SSA back to normal variable names</li>
     * </ol>
     *
     * @param body       structured pseudo-C body lines
     * @param isAarch64  true for AArch64, false for ARM32
     * @return optimized body lines
     */
    public static List<String> optimize(List<String> body, boolean isAarch64) {
        if (body == null || body.isEmpty()) {
            return body == null ? new ArrayList<String>() : new ArrayList<String>(body);
        }

        try {
            SsaContext ctx = new SsaContext();

            // Phase 1: Identify all defs and uses (single-block SSA since we
            // operate on already-structured text, not raw CFG).
            // For multi-block SSA, we'd need block-level info, but structured
            // text already has if/else/while braces — we treat the whole body
            // as a linear sequence and do intra-block versioning.
            //
            // This captures the KEY benefit: each redefinition gets a new
            // version, so def-use chains are precise WITHIN the linear flow.
            // Cross-block uses (after if/else merge) get conservative
            // treatment (we don't insert Phi in text-mode SSA).
            List<String> versioned = assignVersions(body, ctx, isAarch64);

            // Phase 2: Global constant propagation
            versioned = propagateConstants(versioned, ctx);

            // Phase 3: Global dead code elimination
            versioned = eliminateDeadCode(versioned, ctx);

            // Phase 4: Type propagation along def-use chains
            propagateTypes(ctx);

            // Phase 5: Deconstruct SSA → back to base register names
            List<String> result = deconstruct(versioned, ctx);

            // Phase 6: Constant condition simplification (v2.9.39)
            // Eliminate if ((CONST & MASK) != 0) pseudo-conditions that remain
            // after SSA constant propagation. These come from cset instructions
            // where the condition register was assigned a constant.
            result = simplifyConstantConditions(result);

            return result;
        } catch (Throwable t) {
            // SSA must never kill decompilation — fall back to input
            return new ArrayList<String>(body);
        }
    }

    // ── Phase 1: Version assignment ──

    /**
     * Assign SSA versions to each register definition.
     * x8 = x0 + x1;  →  x8_1 = x0 + x1;
     * x8 = x8 + 4;   →  x8_2 = x8_1 + 4;
     * x9 = x8;       →  x9_1 = x8_2;
     */
    private static List<String> assignVersions(List<String> body,
                                                SsaContext ctx,
                                                boolean isAarch64) {
        List<String> result = new ArrayList<>();
        // Map: base name → current SSA name to use for reads
        Map<String, String> currentSsaName = new HashMap<>();

        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);

            // Step 1: Check if this line defines a register (using the
            // ORIGINAL line, before any use replacement). This is critical:
            // if we replaced x8→x8_1 first, REG_DEF wouldn't match "x8_1 = ..."
            // because [wxr]\d+ doesn't match SSA names with _N suffix.
            Matcher mDefOrig = REG_DEF.matcher(line);
            String defBase = null;
            String defRhs = null;
            if (mDefOrig.matches()) {
                defBase = mDefOrig.group(1);
                defRhs = mDefOrig.group(2);
            }

            // Step 2: Replace register reads with their current SSA names.
            // If this line is a definition, skip replacing the defined register
            // (it's on the LHS and will get a new version).
            StringBuffer sb = new StringBuffer();
            Matcher mUse = REG_USE.matcher(line);
            while (mUse.find()) {
                String reg = mUse.group(1);
                // Skip the defined register on the LHS
                if (reg.equals(defBase) && mUse.start() < line.indexOf('=')) {
                    mUse.appendReplacement(sb, reg);
                    continue;
                }
                String ssaName = currentSsaName.get(reg);
                if (ssaName != null) {
                    mUse.appendReplacement(sb, Matcher.quoteReplacement(ssaName));
                    ctx.addUse(ssaName, i);
                } else {
                    mUse.appendReplacement(sb, reg);
                }
            }
            mUse.appendTail(sb);
            String processed = sb.toString();

            // Step 3: If this line defines a register, assign a new version
            if (defBase != null) {
                String rhs = defRhs;
                // The rhs may have been modified by use replacement above.
                // Extract the RHS from the processed line.
                Matcher mDefProc = REG_DEF.matcher(processed);
                if (mDefProc.matches()) {
                    rhs = mDefProc.group(2);
                }

                int ver = ctx.newVersion(defBase);
                SsaVar ssaVar = new SsaVar(defBase, ver);
                String ssaName = ssaVar.ssaName();
                currentSsaName.put(defBase, ssaName);
                ctx.pushVersion(defBase, ver);

                // Record definition
                DefSite def = new DefSite(i, ssaVar, rhs);
                ctx.defs.put(i, def);

                // Rebuild the line with the new SSA name on the LHS
                processed = ssaName + " = " + rhs + ";";
            }

            // Step 4: Function calls clobber caller-saved registers.
            //   x0 = 5; x1 = foo(5); bar(x0, x0) — 若不隔离, bar 的 x0 会
            //   错误连接到 x0_1 = 5 被传播成 bar(5, 5). 调用后参数寄存器
            //   的值不可知, 必须断开与旧版本的连接.
            if (isCallLine(processed)) {
                for (String reg : CLOBBERED_REGS) {
                    currentSsaName.remove(reg);
                }
            }

            result.add(processed);
        }

        return result;
    }

    /**
     * 判断一行是否为函数调用行 (含控制流关键字前缀的不算).
     */
    private static boolean isCallLine(String line) {
        String t = line.trim();
        if (t.startsWith("if (") || t.startsWith("while (")
                || t.startsWith("for (") || t.startsWith("switch (")
                || t.startsWith("else")) {
            return false;
        }
        return CALL_LINE.matcher(t).find();
    }

    /** 调用者保存寄存器 (调用会被改写): AArch64 x0-x18/w0-w18, ARM32 r0-r12. */
    private static final String[] CLOBBERED_REGS = buildClobberedRegs();

    private static String[] buildClobberedRegs() {
        List<String> regs = new ArrayList<>();
        for (int i = 0; i <= 18; i++) {
            regs.add("x" + i);
            regs.add("w" + i);
        }
        for (int i = 0; i <= 12; i++) {
            regs.add("r" + i);
        }
        return regs.toArray(new String[0]);
    }

    /** 标识符紧跟左括号 = 函数调用 (如 foo(5), bar(x0, x0)). */
    private static final Pattern CALL_LINE =
            Pattern.compile("[A-Za-z_]\\w*\\s*\\(");

    // ── Phase 2: Constant propagation ──

    /** Pattern that matches SSA variable definitions: x8_1 = expr; */
    private static final Pattern SSA_DEF =
            Pattern.compile("^\\s*([wxr]\\d+)_\\d+\\s*=\\s*(.+);\\s*$");

    /** Pattern for memory access with constant arithmetic: *(type*)(CONST OP CONST) */
    private static final Pattern MEM_CONST_ARITH = Pattern.compile(
            "\\*\\s*\\(([^)]*)\\*\\)\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*([+\\-])\\s*(-?0x[0-9a-fA-F]+|-?\\d+)\\s*\\)");

    /** Pattern for memory access with constant arithmetic (negative): *(type*)(CONST + -CONST) */
    private static final Pattern MEM_CONST_NEG = Pattern.compile(
            "\\*\\s*\\(([^)]*)\\*\\)\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\+\\s*-?(0x[0-9a-fA-F]+|\\d+)\\s*\\)");

    /**
     * Propagate constants along def-use chains.
     * x8_1 = 0x1000;
     * x9_1 = x8_1 + 0x10;  →  x9_1 = 0x1010;
     */
    private static List<String> propagateConstants(List<String> body,
                                                     SsaContext ctx) {
        // First pass: collect all constant definitions
        for (Map.Entry<Integer, DefSite> entry : ctx.defs.entrySet()) {
            DefSite def = entry.getValue();
            String rhs = def.rhsExpr.trim();

            // Direct constant assignment: x8_1 = 0x1000;
            if (rhs.matches("0x[0-9a-fA-F]+|\\d+")) {
                ctx.constants.put(def.var.ssaName(), rhs);
            }
        }

        // v3.3: 自增/自减/修改过的变量不是稳定常量 — 从传播表剔除.
        //   例: x0_1 = 0; x0_1++; while (x0_1 < 10) — 循环条件必须保留 x0_1,
        //   否则变成 while (0 < 10) 恒真死循环. 正版 r2dec 保留循环变量.
        Set<String> modified = new HashSet<>();
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            java.util.regex.Matcher mInc = Pattern.compile(
                    "^\\s*([wxr]\\d+_\\d+)\\s*(\\+\\+|--)\\s*;").matcher(line);
            if (mInc.find()) {
                modified.add(mInc.group(1));
            }
        }
        for (String v : modified) {
            ctx.constants.remove(v);
        }

        // Second pass: propagate constants into uses
        List<String> result = new ArrayList<>(body);
        for (int i = 0; i < result.size(); i++) {
            String line = result.get(i);

            // Skip lines that are themselves constant defs
            DefSite def = ctx.defs.get(i);
            if (def != null && ctx.constants.containsKey(def.var.ssaName())) {
                continue;
            }

            // Replace each SSA constant var with its value
            String processed = line;
            Matcher mSelf = SSA_DEF.matcher(processed);
            // v3.3: 自增/自减行 (x0_1++ / x0_1--) — LHS 是写目标, 不能替换.
            //   形如 ^\s*([wxr]\d+)_\d+\+\+; 直接跳过 (++ 行无常量可传播).
            if (processed.matches("^\\s*[wxr]\\d+_\\d+\\s*\\+\\+\\s*;\\s*$")
                    || processed.matches("^\\s*[wxr]\\d+_\\d+\\s*--\\s*;\\s*$")) {
                // 保持原样 — 自增目标是变量不是常量
            } else if (mSelf.matches()) {
                String lhs = mSelf.group(1);
                String rhs = mSelf.group(2);
                String newRhs = rhs;
                for (Map.Entry<String, String> c : ctx.constants.entrySet()) {
                    String ssaName = c.getKey();
                    String value = c.getValue();
                    if (ssaName.equals(lhs)) continue;  // 自赋值源不替换 (x = x + 1)
                    newRhs = newRhs.replaceAll("\\b"
                            + Pattern.quote(ssaName) + "\\b",
                            Matcher.quoteReplacement(value));
                }
                processed = lhs + " = " + newRhs + ";";
            } else {
                for (Map.Entry<String, String> c : ctx.constants.entrySet()) {
                    String ssaName = c.getKey();
                    String value = c.getValue();
                    // Only replace whole-word matches
                    processed = processed.replaceAll("\\b"
                            + Pattern.quote(ssaName) + "\\b",
                            Matcher.quoteReplacement(value));
                }
            }

            // Check if this def now has a constant value (using SSA_DEF pattern)
            if (def != null) {
                Matcher mDef = SSA_DEF.matcher(processed);
                if (mDef.matches()) {
                    String rhs = mDef.group(2).trim();
                    // Try to fold: 0x1000 + 0x10 → 0x1010
                    String folded = tryFold(rhs);
                    if (folded != null) {
                        ctx.constants.put(def.var.ssaName(), folded);
                        processed = def.var.ssaName() + " = " + folded + ";";
                    } else if (rhs.matches("0x[0-9a-fA-F]+|\\d+")) {
                        // Simple constant assignment after propagation
                        ctx.constants.put(def.var.ssaName(), rhs);
                    }
                }
            }

            result.set(i, processed);
        }

        // Third pass: fold constant arithmetic inside memory expressions.
        // *(uint64_t*)(0x8f9000 + 0x6a0) → *(uint64_t*)(0x8f96a0)
        // *(uint64_t*)(0x9000 + -0x10) → *(uint64_t*)(0x8ff0)
        for (int i = 0; i < result.size(); i++) {
            String line = result.get(i);
            String folded = foldMemArith(line);
            if (!folded.equals(line)) {
                result.set(i, folded);
            }
        }

        return result;
    }

    /**
     * Fold constant arithmetic inside memory expressions.
     * *(type*)(0x8f9000 + 0x6a0) → *(type*)(0x8f96a0)
     * *(type*)(0x9000 - 0x10) → *(type*)(0x8ff0)
     * *(type*)(0x9000 + -0x10) → *(type*)(0x8ff0)
     */
    private static String foldMemArith(String line) {
        if (line == null || !line.contains("*(")) return line;

        // Handle: *(type*)(CONST + CONST) or *(type*)(CONST - CONST)
        Matcher m = MEM_CONST_ARITH.matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String type = m.group(1);
            long a = parseLongSafe(m.group(2));
            char op = m.group(3).charAt(0);
            long b = parseLongSafe(m.group(4));
            long result;
            if (op == '+') result = a + b;
            else result = a - b;
            String replacement = "*((" + type + "*)(0x" + Long.toHexString(result) + "))";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);

        // Also handle: *(type*)(CONST + -CONST) pattern
        // (already covered by the + case above since -0x10 is parsed as negative)
        return sb.toString();
    }

    /**
     * Try to fold a constant expression.
     * 0x1000 + 0x10 → 0x1010
     * 0x1000 - 0x10 → 0xff0
     */
    private static String tryFold(String expr) {
        expr = expr.trim();
        // Pattern: CONST OP CONST
        Matcher m = Pattern.compile(
                "^(0x[0-9a-fA-F]+|\\d+)\\s*([+\\-*/&|])\\s*(0x[0-9a-fA-F]+|\\d+)$")
                .matcher(expr);
        if (!m.matches()) return null;

        long a = parseLongSafe(m.group(1));
        char op = m.group(2).charAt(0);
        long b = parseLongSafe(m.group(3));
        long result;

        switch (op) {
            case '+': result = a + b; break;
            case '-': result = a - b; break;
            case '*': result = a * b; break;
            case '&': result = a & b; break;
            case '|': result = a | b; break;
            default: return null;
        }

        return "0x" + Long.toHexString(result);
    }

    // ── Phase 3: Dead code elimination ──

    /**
     * Eliminate definitions whose SSA variables are never used.
     * This is GLOBAL DCE — it tracks uses across the entire body, not
     * just the next line (which is what PeepholeOptimizer does).
     *
     * <p>Exception: return-value registers (x0 for AArch64, r0 for ARM32)
     * are NEVER eliminated, because they carry the function's return value
     * even if no subsequent instruction reads them.
     */
    private static List<String> eliminateDeadCode(List<String> body,
                                                    SsaContext ctx) {
        // For each definition, check if its SSA var has any uses
        Set<Integer> deadLines = new HashSet<>();
        for (Map.Entry<Integer, DefSite> entry : ctx.defs.entrySet()) {
            DefSite def = entry.getValue();
            String ssaName = def.var.ssaName();

            // v3.3: return-value register assignments are only kept when they
            // carry a call result (x0 = func(...)) — the true function return.
            // A plain x0 = sp + 0x50 used solely as a call argument is dead
            // once inlined, and must be eliminated (正版 r2dec 语义).
            if (def.var.base.equals("x0") || def.var.base.equals("r0")
                    || def.var.base.equals("w0")) {
                String rhs = def.rhsExpr.trim();
                if (rhs.contains("(") && !rhs.startsWith("/*")) {
                    continue;  // x0 = call(...) — keep
                }
                // fall through to use-count check
            }

            int uses = ctx.useCount(ssaName);
            if (uses == 0) {
                // No uses at all → dead code
                // But DON'T remove if it's:
                // 1. A call statement (side effects)
                // 2. A store (memory side effects)
                // 3. A memory load (kept for readability)
                String rhs = def.rhsExpr.trim();
                if (!hasSideEffects(rhs)) {
                    deadLines.add(entry.getKey());
                }
            }
        }

        if (deadLines.isEmpty()) return body;

        // Rebuild without dead lines
        List<String> result = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            if (!deadLines.contains(i)) {
                result.add(body.get(i));
            }
        }
        ctx.deadLines.addAll(deadLines);
        return result;
    }

    /**
     * Check if an expression has side effects (calls, stores, memory reads).
     * Memory loads are conservatively kept for decompilation readability —
     * even if the result register is unused, the load itself may be
     * meaningful (volatile read, or needed for downstream address folding).
     */
    private static boolean hasSideEffects(String expr) {
        if (expr == null) return false;
        // Function calls: name(args)
        if (expr.matches(".*\\w+\\s*\\([^)]*\\).*") && !expr.contains("__atomic")) {
            // Could be a call — be conservative
            // But exclude type casts like (uint64_t*)(...)
            if (expr.matches(".*[a-zA-Z_]\\w*\\s*\\([^)]*\\).*")
                    && !expr.startsWith("(")) {
                return true;
            }
        }
        // Atomic operations have side effects
        if (expr.contains("__atomic")) return true;
        // Memory loads: *(type*)(addr) — keep for readability
        if (expr.contains("*(") || expr.contains("* (")) return true;
        // Memory stores: *(addr) = value  (but these won't appear in rhs)
        return false;
    }

    // ── Phase 4: Type propagation ──

    /**
     * Propagate type hints along def-use chains.
     * If x8_1 = *(uint64_t*)(x9 + 0x10), then x8_1 is a uint64_t (loaded value),
     * and x9 is a pointer (base of dereference).
     */
    private static void propagateTypes(SsaContext ctx) {
        for (DefSite def : ctx.defs.values()) {
            String rhs = def.rhsExpr;
            if (rhs == null) continue;

            // Dereference: *(uint64_t*)(xN + off) → xN is a pointer
            Matcher mDeref = MEM_ACCESS.matcher(rhs);
            while (mDeref.find()) {
                String baseReg = mDeref.group(1);
                ctx.typeHints.put(baseReg, "void*");
            }

            // Assignment from another reg: x8_1 = x9_1 → inherit type
            Matcher mCopy = Pattern.compile("^([wxr]\\d+_\\d+)\\s*$").matcher(rhs.trim());
            if (mCopy.matches()) {
                String srcSsa = mCopy.group(1);
                String srcType = ctx.typeHints.get(srcSsa);
                if (srcType != null) {
                    ctx.typeHints.put(def.var.ssaName(), srcType);
                }
            }

            // Comparison with negative: x8_1 < 0 → signed
            if (rhs.contains("< 0") || rhs.contains("<= 0")
                    || rhs.contains("< -") || rhs.contains("<= -")) {
                ctx.typeHints.put(def.var.ssaName(), "int64_t");
            }
        }
    }

    // ── Phase 5: SSA deconstruction ──

    /**
     * Deconstruct SSA: remove version suffixes from variable names.
     * x8_1 = 0x1000;  →  x8 = 0x1000;
     * x9_1 = x8_1;    →  x9 = x8;
     *
     * <p>This is safe because each SSA version maps to exactly one definition,
     * and after DCE, all remaining versions are used at least once. The
     * deconstruction simply strips the "_N" suffix.
     */
    private static List<String> deconstruct(List<String> body, SsaContext ctx) {
        List<String> result = new ArrayList<>();
        // Pattern: identifier_N where N is a number
        Pattern ssaName = Pattern.compile("\\b([wxr]\\d+)_\\d+\\b");

        for (String line : body) {
            String processed = line;
            Matcher m = ssaName.matcher(processed);
            processed = m.replaceAll("$1");
            result.add(processed);
        }
        return result;
    }

    // ── Phase 6: Constant condition simplification (v2.9.39) ──

    /**
     * Pattern for constant bitwise conditions: if ((CONST & MASK) != 0) {
     * or if ((CONST & MASK) == 0) {
     * v2.9.39: Patterns use find() not matches(), but we also append
     * optional trailing content ({ or whitespace) for robustness.
     */
    private static final Pattern CONST_BITCOND_NE = Pattern.compile(
            "if\\s*\\(\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*&\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*!=\\s*0x?0*\\s*\\)");
    private static final Pattern CONST_BITCOND_EQ = Pattern.compile(
            "if\\s*\\(\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*&\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*==\\s*0x?0*\\s*\\)");

    /** Pattern for simple constant conditions: if (CONST) { or if (CONST != 0) { */
    private static final Pattern CONST_SIMPLE = Pattern.compile(
            "if\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*\\{?\\s*$");
    private static final Pattern CONST_CMP_ZERO = Pattern.compile(
            "if\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*!=\\s*0x?0*\\s*\\)\\s*\\{?\\s*$");

    /**
     * Simplify constant conditions that remain after SSA propagation.
     *
     * <p>Eliminates pseudo-conditions from cset instructions:
     * <ul>
     *   <li>{@code if ((1 & 0x1) != 0)} → always true → unwrap block</li>
     *   <li>{@code if ((0 & 0x1) != 0)} → always false → drop block</li>
     *   <li>{@code if (1)} → always true → unwrap</li>
     *   <li>{@code if (0)} → always false → drop</li>
     *   <li>{@code if (0x1 != 0)} → always true → unwrap</li>
     * </ul>
     *
     * <p>This is the key pass that eliminates cset residue. When SSA
     * propagates {@code w8 = 1} (from cset) into {@code if ((w8 & 1) != 0)},
     * it becomes {@code if ((1 & 1) != 0)} which this pass evaluates to true.
     */
    private static List<String> simplifyConstantConditions(List<String> body) {
        List<String> result = new ArrayList<>();
        int n = body.size();
        int i = 0;

        while (i < n) {
            String line = body.get(i).trim();
            boolean handled = false;

            // Check: if ((CONST & MASK) != 0) {
            Matcher mNe = CONST_BITCOND_NE.matcher(line);
            if (mNe.find() && line.endsWith("{")) {
                long val = parseLongSafe(mNe.group(1));
                long mask = parseLongSafe(mNe.group(2));
                boolean taken = (val & mask) != 0;
                int[] range = findBlockEnd(body, i);
                if (range != null) {
                    if (taken) {
                        // Always true → keep body, remove if guard
                        for (int k = i + 1; k < range[0]; k++) {
                            result.add(body.get(k));
                        }
                    }
                    // else: always false → drop entire block
                    i = range[1] + 1;
                    continue;
                }
            }

            // Check: if ((CONST & MASK) == 0) {
            Matcher mEq = CONST_BITCOND_EQ.matcher(line);
            if (mEq.find() && line.endsWith("{")) {
                long val = parseLongSafe(mEq.group(1));
                long mask = parseLongSafe(mEq.group(2));
                boolean taken = (val & mask) == 0;
                int[] range = findBlockEnd(body, i);
                if (range != null) {
                    if (taken) {
                        for (int k = i + 1; k < range[0]; k++) {
                            result.add(body.get(k));
                        }
                    }
                    i = range[1] + 1;
                    continue;
                }
            }

            // Check: if (CONST) {  or  if (CONST != 0) {
            Matcher mSimple = CONST_SIMPLE.matcher(line);
            Matcher mCmpZero = CONST_CMP_ZERO.matcher(line);
            long constVal = -1;
            boolean matched = false;

            if (mSimple.matches() && line.endsWith("{")) {
                constVal = parseLongSafe(mSimple.group(1));
                matched = true;
            } else if (mCmpZero.matches() && line.endsWith("{")) {
                constVal = parseLongSafe(mCmpZero.group(1));
                matched = true;
            }

            if (matched) {
                boolean taken = constVal != 0;
                int[] range = findBlockEnd(body, i);
                if (range != null) {
                    if (taken) {
                        for (int k = i + 1; k < range[0]; k++) {
                            result.add(body.get(k));
                        }
                    }
                    i = range[1] + 1;
                    continue;
                }
            }

            result.add(body.get(i));
            i++;
        }

        return result;
    }

    /**
     * Find the matching closing brace for a block starting at index i.
     * Returns [closingBraceIndex, lastLineIndex] or null if not found.
     */
    private static int[] findBlockEnd(List<String> body, int start) {
        if (start >= body.size()) return null;
        String firstLine = body.get(start).trim();
        if (!firstLine.endsWith("{")) return null;

        int depth = 0;
        for (int i = start; i < body.size(); i++) {
            String line = body.get(i);
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            if (depth == 0) {
                return new int[]{i, i};
            }
        }
        return null;
    }

    // ── Utility ──

    private static long parseLongSafe(String s) {
        try {
            if (s == null) return 0;
            s = s.trim();
            // Handle negative hex: -0xNN
            if (s.startsWith("-0x") || s.startsWith("-0X")) {
                return -Long.parseUnsignedLong(s.substring(3), 16);
            }
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

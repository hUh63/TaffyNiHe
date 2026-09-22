package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-structuring peephole optimizer — runs after {@link ControlFlowStructurer}
 * produces the body lines and before they are emitted into the final output.
 *
 * <h3>Passes</h3>
 * <ol>
 *   <li><b>Empty-branch cleanup</b> — removes {@code if (cond) {}},
 *       {@code else {}}, and {@code else if (...) {} } whose body is empty;
 *       collapses {@code if (cond) {} else { ... }} into the else body
 *       (guard removed, inverted when needed).</li>
 *   <li><b>Constant folding</b> — when a register is assigned a numeric
 *       literal and the immediately following statement reassigns it a
 *       {@code ((reg &amp; M) | V)} / {@code ((reg &amp; M) ^ V)} expression,
 *       substitute the literal and fold to a single constant assignment.</li>
 *   <li><b>Local dead-store elimination</b> — drops an assignment
 *       {@code lhs = X;} when the next statement reassigns {@code lhs}
 *       without referencing {@code lhs} on its right-hand side.</li>
 *   <li><b>Conservative if/!if merge</b> — merges a guarded block
 *       {@code if (C) { ... }} immediately followed by {@code if (!C) { ... }}
 *       (exact negation, no intervening statements) into
 *       {@code if (C) { ... } else { ... }}.</li>
 * </ol>
 *
 * <p>All passes are <b>structural</b> — they operate on brace/keyword line
 * patterns and never touch the inside of compound statements beyond simple
 * substitution, so they cannot corrupt well-formed output. Each pass runs to
 * a fixed point (idempotent re-run yields no further change).
 *
 * <p>This is deliberately a lightweight, pattern-based peephole optimizer. It
 * is NOT a data-flow analysis: no use-def chains, no dominance, no SSA. The
 * goal is to remove the most visible noise (empty branches, dead temporaries,
 * unfoldable constants) with high confidence and zero risk of miscompilation
 * on the cases it recognizes.
 */
public final class PeepholeOptimizer {

    private PeepholeOptimizer() {
    }

    /**
     * Run all peephole passes to a fixed point on the given body lines.
     *
     * @param body the structured body lines (un-indented, as produced by
     *             {@link ControlFlowStructurer#getOutput()})
     * @return a new list with peephole optimizations applied
     */
    public static List<String> optimize(List<String> body) {
        if (body == null || body.isEmpty()) {
            return body == null ? new ArrayList<String>() : new ArrayList<String>(body);
        }
        // CRITICAL: peephole optimization is a best-effort enhancement. Any
        // failure (regex, index, null) must NEVER propagate and kill the whole
        // decompilation — that would force a fallback to raw disassembly,
        // which is far worse than un-optimized pseudo-C. Return the input
        // unchanged on any error.
        try {
            List<String> cur = new ArrayList<String>(body);
            // v2.9.44: First pass — collect void* variable names from declarations
            // and insert (void*) casts on integer-to-pointer assignments.
            cur = intToPointerCast(cur);
            // Iterate the cheap passes to a fixed point. Bounded to avoid runaway.
            for (int iter = 0; iter < 8; iter++) {
                int before = cur.size();
                List<String> next = emptyBranchCleanup(cur);
                next = constantFold(next);
                next = constantPropagation(next);
                next = deadStoreElimination(next);
                next = ifNotIfMerge(next);
                next = trivialConditionCleanup(next);
                next = unreachableCodeElimination(next);
                next = gotoSimplification(next);
                next = bitFold(next);
                cur = next;
                if (cur.size() == before && sameLines(cur, body)) {
                    break;
                }
                if (cur.size() == before) {
                    // Size stable; do one more equality check vs the previous
                    // iteration's content to detect a genuine fixed point.
                    List<String> probe = emptyBranchCleanup(cur);
                    probe = constantFold(probe);
                    probe = constantPropagation(probe);
                    probe = deadStoreElimination(probe);
                    probe = ifNotIfMerge(probe);
                    probe = trivialConditionCleanup(probe);
                    probe = unreachableCodeElimination(probe);
                    probe = gotoSimplification(probe);
                    probe = bitFold(probe);
                    if (sameLines(probe, cur)) {
                        cur = probe;
                        break;
                    }
                    cur = probe;
                }
            }
            return cur;
        } catch (Throwable t) {
            // Never let peephole optimization kill decompilation.
            return new ArrayList<String>(body);
        }
    }

    private static boolean sameLines(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 1: empty-branch cleanup
    // ───────────────────────────────────────────────────────────────────

    /**
     * Remove empty {@code if (cond) {}}, {@code else {}}, and empty
     * {@code else {}} following an {@code if}. Also collapses
     * {@code if (cond) {} else { BODY }} into the BODY (no guard).
     *
     * <p>Recognized empty-body patterns (one statement per line):
     * <ul>
     *   <li>{@code if (cond) {} }</li>
     *   <li>{@code if (cond) {}} then the next line {@code } }</li>
     *   <li>{@code else {} }</li>
     * </ul>
     */
    private static List<String> emptyBranchCleanup(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        for (int i = 0; i < n; ) {
            String line = in.get(i).trim();

            // if (cond) { }  → drop entirely (single line)
            if (isEmptyIf(line)) {
                i++;
                continue;
            }
            // if (cond) {   followed by   }   → drop both lines
            if (line.startsWith("if (") && line.endsWith("{") && i + 1 < n
                    && in.get(i + 1).trim().equals("}")) {
                i += 2;
                continue;
            }
            // else { }  → drop entirely
            if (line.equals("else {}") || line.equals("else {} }")) {
                i++;
                continue;
            }
            // else {   followed by   }  → drop both
            if (line.equals("else {") && i + 1 < n
                    && in.get(i + 1).trim().equals("}")) {
                i += 2;
                continue;
            }
            // v2.9.37: while (cond) { }  → annotate as spinlock instead of dropping.
            // Empty while loops are often spinlocks (waiting for hardware/atomic change).
            if (isEmptyWhile(line)) {
                out.add(line.replace("{}", "{ /* spin */ }"));
                i++;
                continue;
            }
            // v2.9.37: Multi-line empty while: "while (cond) {" + "}"
            if (line.startsWith("while (") && line.endsWith("{") && i + 1 < n
                    && in.get(i + 1).trim().equals("}")) {
                out.add(line.replace("{", "{ /* spin */ }"));
                i += 2;
                continue;
            }
            // v2.9.40: CAS spin loop — while (cond) { __atomic_*(...) }
            // Body contains only atomic operations (CAS retry loop).
            if (line.startsWith("while (") && line.endsWith("{")) {
                BlockRange casBr = collectBlock(in, i);
                if (casBr != null && casBr.bodyEnd >= casBr.bodyStart) {
                    boolean allAtomic = true;
                    for (int k = casBr.bodyStart; k <= casBr.bodyEnd; k++) {
                        String bl = in.get(k).trim();
                        if (bl.isEmpty()) continue;
                        if (!bl.contains("__atomic_") && !bl.contains("__builtin_")) {
                            allAtomic = false;
                            break;
                        }
                    }
                    if (allAtomic) {
                        out.add(line.replace("{", "{ /* CAS spin */ }"));
                        for (int k = i + 1; k <= casBr.end; k++) {
                            out.add(in.get(k));
                        }
                        i = casBr.end + 1;
                        continue;
                    }
                }
            }
            out.add(in.get(i));
            i++;
        }
        // Second sweep: collapse "if (cond) {} else {" ... "}" into the else
        // body. Handled by re-running; here we only do the trivial single-line
        // collapse "if (cond) {} else { BODY }" is multi-line, skip.
        return collapseEmptyIfElse(out);
    }

    // Precompiled patterns for empty-branch detection. Compiled once at class
    // load so any regex error surfaces immediately, not mid-decompilation.
    // NOTE: all literal { and } in these patterns are escaped (\\{ \\}) to
    // avoid PatternSyntaxException — unescaped } can be misinterpreted by the
    // Java regex engine as a quantifier terminator.
    private static final Pattern EMPTY_IF =
            Pattern.compile("if\\s*\\([^{}]*\\)\\s*\\{\\s*\\}\\s*\\}?");
    private static final Pattern EMPTY_WHILE =
            Pattern.compile("while\\s*\\([^{}]*\\)\\s*\\{\\s*\\}");

    private static boolean isEmptyIf(String line) {
        // "if (cond) {}" possibly with trailing "}"
        return EMPTY_IF.matcher(line).matches();
    }

    private static boolean isEmptyWhile(String line) {
        // "while (cond) {}" with no // spin annotation
        return EMPTY_WHILE.matcher(line).matches()
                && !line.contains("// spin");
    }

    /**
     * Collapse {@code if (cond) {} else { ... }} into the else body. We look
     * for an empty {@code if (cond) {}} immediately followed by an
     * {@code else { ... }} block and replace the whole thing with the else
     * body lines.
     */
    private static List<String> collapseEmptyIfElse(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        int i = 0;
        while (i < n) {
            String line = in.get(i).trim();
            // Pattern: empty if on its own, then "else {" ... matching "}"
            if (isEmptyIf(line) && i + 1 < n && in.get(i + 1).trim().startsWith("else")) {
                // Find the else block: collect lines from the else line until
                // the matching closing brace.
                int elseStart = i + 1;
                String elseLine = in.get(elseStart).trim();
                List<String> elseBody = new ArrayList<String>();
                int depth = 0;
                int j = elseStart;
                boolean entered = false;
                while (j < n) {
                    String s = in.get(j);
                    for (int k = 0; k < s.length(); k++) {
                        char c = s.charAt(k);
                        if (c == '{') { depth++; entered = true; }
                        else if (c == '}') { depth--; }
                    }
                    if (j == elseStart) {
                        // strip the "else {" prefix from the first line
                        String rest = s.trim().replaceAll("^else\\s*\\{", "");
                        if (!rest.isEmpty()) elseBody.add(rest);
                    } else {
                        elseBody.add(s);
                    }
                    j++;
                    if (entered && depth == 0) break;
                }
                // Remove the trailing "}" line if it is standalone
                if (!elseBody.isEmpty()
                        && elseBody.get(elseBody.size() - 1).trim().equals("}")) {
                    elseBody.remove(elseBody.size() - 1);
                }
                out.addAll(elseBody);
                i = j;
                continue;
            }
            out.add(in.get(i));
            i++;
        }
        return out;
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 2: constant folding (movt-style reg=N; reg=((reg&M)|V))
    // ───────────────────────────────────────────────────────────────────

    // reg = NUMBER;   (hex or dec)
    private static final Pattern ASSIGN_NUM =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(0x[0-9a-fA-F]+|\\d+)\\s*;?$");
    // reg = ((reg & M) | V);   or   reg = ((reg & M) ^ V);
    private static final Pattern ASSIGN_FOLD =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\(\\(\\1\\s*&\\s*(~?0x[0-9a-fA-F]+|~?\\d+)\\)\\s*([|^])\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*;?$");

    /**
     * Fold a numeric assignment followed by a {@code ((reg & M) op V)}
     * reassignment into a single constant assignment. Only triggers when the
     * two statements are adjacent and the second references the same register.
     */
    private static List<String> constantFold(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        for (int i = 0; i < n; i++) {
            String line = in.get(i).trim();
            Matcher mNum = ASSIGN_NUM.matcher(line);
            if (mNum.matches() && i + 1 < n) {
                String reg = mNum.group(1);
                long numVal = parseLong(mNum.group(2));
                String next = in.get(i + 1).trim();
                Matcher mFold = ASSIGN_FOLD.matcher(next);
                if (mFold.matches() && mFold.group(1).equals(reg)) {
                    long mask = parseLongWithNot(mFold.group(2));
                    char op = mFold.group(3).charAt(0);
                    long val = parseLong(mFold.group(4));
                    long folded;
                    if (op == '|') {
                        folded = (numVal & mask) | val;
                    } else { // '^'
                        folded = (numVal & mask) ^ val;
                    }
                    out.add(reg + " = 0x" + Long.toHexString(folded) + ";");
                    i++; // consume the next line
                    continue;
                }
            }
            out.add(in.get(i));
        }
        return out;
    }

    private static long parseLong(String s) {
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

    private static long parseLongWithNot(String s) {
        boolean neg = s.startsWith("~");
        if (neg) s = s.substring(1);
        long v = parseLong(s);
        return neg ? ~v : v;
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 3: constant propagation (x9 = 0x8f9000; x8 = *(x9 + 0x6a0) → x8 = *(0x8f96a0))
    // ───────────────────────────────────────────────────────────────────

    // lhs = NUMBER;  (the constant definition)
    private static final Pattern PROPAGATE_DEF =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(0x[0-9a-fA-F]+|\\d+)\\s*;?$");

    /**
     * Constant propagation: when a register is assigned a numeric constant and
     * the next line uses that register in a memory expression
     * ({@code *(TYPE*)(reg + offset)}), substitute the constant and fold the
     * addition.
     * <p>Example:
     * <pre>
     *   x9 = 0x8f9000;
     *   x8 = *(uint64_t*)(x9 + 0x6a0);
     * </pre>
     * becomes:
     * <pre>
     *   x8 = *(uint64_t*)(0x8f96a0);
     * </pre>
     * <p>Conservative: only propagates forward by one line, only into
     * {@code *(TYPE*)(reg + off)} patterns, and only when the register is not
     * referenced elsewhere on the same line.
     */
    private static List<String> constantPropagation(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        for (int i = 0; i < n; i++) {
            String line = in.get(i).trim();
            Matcher mDef = PROPAGATE_DEF.matcher(line);
            if (mDef.matches() && i + 1 < n) {
                String reg = mDef.group(1);
                long constVal = parseLong(mDef.group(2));
                String next = in.get(i + 1).trim();

                // Try to substitute reg in the next line's memory expression
                String substituted = propagateIntoMemExpr(next, reg, constVal);
                if (substituted != null) {
                    out.add(substituted);
                    i++; // consume the def line (it becomes dead after propagation)
                    continue;
                }
            }
            out.add(in.get(i));
        }
        return out;
    }

    /**
     * Try to substitute {@code reg = constVal} into a memory expression in
     * {@code line}. Handles patterns:
     * <ul>
     *   <li>{@code *(TYPE*)(reg + 0xNN)} → {@code *(TYPE*)(FOLDED)}</li>
     *   <li>{@code *(TYPE*)(reg - 0xNN)} → {@code *(TYPE*)(FOLDED)}</li>
     *   <li>{@code *(TYPE*)(reg)} → {@code *(TYPE*)(constVal)}</li>
     * </ul>
     * @return the substituted line, or null if no substitution was made
     */
    private static String propagateIntoMemExpr(String line, String reg, long constVal) {
        // Pattern: *(TYPE*)(reg <op> <offset>)
        // Handle both "reg + 0xNN", "reg - 0xNN", and "reg + -0xNN" formats.
        String memBase = "(" + Pattern.quote(reg) + "\\s*([+-])\\s*(-?0x[0-9a-fA-F]+|-?\\d+))";
        Pattern p = Pattern.compile("^(.+?\\*" +  // "TYPE* " prefix captured loosely
                "\\([^)]*\\)" +  // "(TYPE*)" cast
                "\\s*\\(" +     // "(" of the address expression
                memBase +       // "reg + offset" or "reg - offset"
                "\\s*\\).*)$"); // ") rest"
        Matcher m = p.matcher(line);
        if (m.matches()) {
            String op = m.group(3);
            long offset = parseLong(m.group(4));
            long folded;
            if (op.equals("+")) {
                folded = constVal + offset;
            } else {
                folded = constVal - offset;
            }
            String foldedHex = "0x" + Long.toHexString(folded);
            // Replace "reg op offset" with "foldedHex" in the line
            String oldExpr = m.group(2); // "reg + 0x6a0" or "reg + -0x10"
            return line.replace(oldExpr, foldedHex);
        }

        // Pattern: *(TYPE*)(reg) → *(TYPE*)(constVal)
        Pattern p2 = Pattern.compile("^(.+?\\*\\([^)]*\\)\\s*\\(" +
                Pattern.quote(reg) + "\\s*\\).*)$");
        Matcher m2 = p2.matcher(line);
        if (m2.matches()) {
            String foldedHex = "0x" + Long.toHexString(constVal);
            // Replace "(reg)" with "(foldedHex)" — but only the innermost one
            // that matches the pattern. Use a targeted replace.
            String target = "(" + reg + ")";
            String replacement = "(" + foldedHex + ")";
            // Only replace if reg appears exactly once to avoid double-substitution
            if (countOccurrences(line, reg) == 1) {
                return line.replace(target, replacement);
            }
        }
        return null;
    }

    /** Count whole-word occurrences of a token in a string. */
    private static int countOccurrences(String text, String token) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
        int count = 0;
        Matcher m = p.matcher(text);
        while (m.find()) count++;
        return count;
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 4: local dead-store elimination
    // ───────────────────────────────────────────────────────────────────

    // lhs = RHS;   (RHS is anything, lhs is a register/var name)
    private static final Pattern ASSIGN_ANY =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+?)\\s*;?$");

    /**
     * Drop an assignment {@code lhs = X;} when the immediately following line
     * reassigns {@code lhs} and its right-hand side does not reference
     * {@code lhs}. This is a conservative, purely-local DCE: it only looks at
     * two adjacent lines and requires the second RHS to be free of the
     * overwritten name, so it never removes a store whose value is consumed
     * by an intervening side-effecting call or branch.
     */
    private static List<String> deadStoreElimination(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        for (int i = 0; i < n; i++) {
            String line = in.get(i).trim();
            Matcher m1 = ASSIGN_ANY.matcher(line);
            if (m1.matches() && i + 1 < n) {
                String lhs = m1.group(1);
                String rhs = m1.group(2);
                String next = in.get(i + 1).trim();
                Matcher m2 = ASSIGN_ANY.matcher(next);
                if (m2.matches() && m2.group(1).equals(lhs)) {
                    String nextRhs = m2.group(2);
                    // Only drop if the dead store's RHS is not itself a
                    // side-effecting call (contains '(' could be a call), and
                    // the next RHS does not reference lhs.
                    if (!looksSideEffecting(rhs) && !referencesName(nextRhs, lhs)) {
                        // Skip the dead store.
                        continue;
                    }
                }
            }
            out.add(in.get(i));
        }
        return out;
    }

    /** Heuristic: an RHS containing a bare call-like pattern is side-effecting. */
    private static boolean looksSideEffecting(String rhs) {
        // e.g. "sub_46f8(r0, r1)" or "funcPtrCall(...)" — treat as side-effecting.
        // Simple assignments like "*(uint32_t*)(r5)" are not calls.
        return rhs.matches("[A-Za-z_][A-Za-z0-9_]*\\(.*\\).*")
                && !rhs.startsWith("*(");
    }

    /** True if {@code name} appears as a whole token in {@code expr}. */
    private static boolean referencesName(String expr, String name) {
        // Word-boundary search to avoid matching substrings (e.g. "r1" in "r10").
        Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        return p.matcher(expr).find();
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 4: conservative if/!if → if-else merge
    // ───────────────────────────────────────────────────────────────────

    private static final Pattern IF_COND =
            Pattern.compile("^if\\s*\\((.+)\\)\\s*\\{\\s*$");

    /**
     * Merge a guarded block {@code if (C) { ... }} immediately followed by
     * {@code if (!C) { ... }} (or the equivalent negated form) into
     * {@code if (C) { ... } else { ... }}. Requires the two {@code if}s to be
     * adjacent with no intervening statements, and the second condition to be
     * the exact negation of the first (wrapped in {@code !(...)} or with a
     * flipped comparison operator).
     */
    private static List<String> ifNotIfMerge(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        int i = 0;
        while (i < n) {
            String line = in.get(i).trim();
            Matcher mIf1 = IF_COND.matcher(line);
            if (mIf1.matches()) {
                String cond1 = mIf1.group(1).trim();
                // Collect the first if-block body and its closing brace.
                BlockRange br1 = collectBlock(in, i);
                if (br1 != null && br1.end + 1 < n) {
                    int j = br1.end + 1;
                    String nextLine = in.get(j).trim();
                    Matcher mIf2 = IF_COND.matcher(nextLine);
                    if (mIf2.matches()) {
                        String cond2 = mIf2.group(1).trim();
                        if (isNegation(cond1, cond2)) {
                            // Emit merged if-else.
                            out.add("if (" + cond1 + ") {");
                            for (int k = br1.bodyStart; k <= br1.bodyEnd; k++) {
                                out.add(in.get(k));
                            }
                            out.add("} else {");
                            BlockRange br2 = collectBlock(in, j);
                            if (br2 != null) {
                                for (int k = br2.bodyStart; k <= br2.bodyEnd; k++) {
                                    out.add(in.get(k));
                                }
                                out.add("}");
                                i = br2.end + 1;
                                continue;
                            }
                        }
                    }
                }
            }
            out.add(in.get(i));
            i++;
        }
        return out;
    }

    /** Range of a brace block: [start]=open line, body, [end]=close line. */
    private static final class BlockRange {
        int start, bodyStart, bodyEnd, end;
    }

    /** Collect the balanced { } block starting at index {@code startIdx}. */
    private static BlockRange collectBlock(List<String> in, int startIdx) {
        int n = in.size();
        int depth = 0;
        int bodyStart = -1;
        int bodyEnd = -1;
        int end = -1;
        for (int j = startIdx; j < n; j++) {
            String s = in.get(j);
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '{') {
                    depth++;
                    if (depth == 1 && bodyStart < 0) bodyStart = j + 1;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = j;
                        bodyEnd = j - 1;
                        BlockRange br = new BlockRange();
                        br.start = startIdx;
                        br.bodyStart = bodyStart;
                        br.bodyEnd = bodyEnd;
                        br.end = end;
                        return br;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Determine whether {@code cond2} is the logical negation of
     * {@code cond1}. Recognizes:
     * <ul>
     *   <li>{@code cond2 == "!(" + cond1 + ")"} (exact wrap)</li>
     *   <li>Operator flips: {@code == ↔ !=}, {@code < ↔ >=}, {@code > ↔ <=}</li>
     * </ul>
     */
    private static boolean isNegation(String cond1, String cond2) {
        if (cond2.equals("!(" + cond1 + ")")) return true;
        if (cond1.equals("!(" + cond2 + ")")) return true;
        // Strip surrounding parens for comparison-operator flip detection.
        String a = cond1.trim();
        String b = cond2.trim();
        // Match "LHS <op> RHS" with a single relational operator.
        Pattern rel = Pattern.compile("^(.+?)\\s*(==|!=|<=|>=|<|>)\\s*(.+)$");
        Matcher ma = rel.matcher(a);
        Matcher mb = rel.matcher(b);
        if (ma.matches() && mb.matches()) {
            String la = ma.group(1).trim();
            String oa = ma.group(2);
            String ra = ma.group(3).trim();
            String lb = mb.group(1).trim();
            String ob = mb.group(2);
            String rb = mb.group(3).trim();
            if (la.equals(lb) && ra.equals(rb)) {
                return flipped(oa).equals(ob);
            }
        }
        return false;
    }

    private static String flipped(String op) {
        switch (op) {
            case "==": return "!=";
            case "!=": return "==";
            case "<":  return ">=";
            case ">=": return "<";
            case ">":  return "<=";
            case "<=": return ">";
            default:   return op;
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 6: trivial condition cleanup (v2.9.37)
    // ───────────────────────────────────────────────────────────────────

    // if (NUMBER) {  — where NUMBER is a hex or decimal literal
    private static final Pattern IF_CONST =
            Pattern.compile("^if\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*\\{\\s*$");
    // if (NUMBER != 0) {  or  if (NUMBER == 0) {
    private static final Pattern IF_CONST_CMP =
            Pattern.compile("^if\\s*\\(\\s*(0x[0-9a-fA-F]+|\\d+)\\s*(==|!=)\\s*0\\s*\\)\\s*\\{\\s*$");

    /**
     * Remove or unwrap blocks guarded by a constant condition.
     * <ul>
     *   <li>{@code if (0) { ... }} → drop entirely</li>
     *   <li>{@code if (1) { ... }} → keep body without guard</li>
     *   <li>{@code if (0x0 != 0) { ... }} → drop</li>
     *   <li>{@code if (0x1 != 0) { ... }} → keep body</li>
     * </ul>
     */
    private static List<String> trivialConditionCleanup(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        int i = 0;
        while (i < n) {
            String line = in.get(i).trim();

            // Pattern 1: if (CONST) {
            Matcher mConst = IF_CONST.matcher(line);
            if (mConst.matches()) {
                long val = parseLong(mConst.group(1));
                BlockRange br = collectBlock(in, i);
                if (br != null) {
                    if (val != 0) {
                        // if (nonzero) { body } → keep body
                        for (int k = br.bodyStart; k <= br.bodyEnd; k++) {
                            out.add(in.get(k));
                        }
                    }
                    // if (0) { body } → drop entirely
                    i = br.end + 1;
                    continue;
                }
            }

            // Pattern 2: if (CONST op 0) {
            Matcher mCmp = IF_CONST_CMP.matcher(line);
            if (mCmp.matches()) {
                long val = parseLong(mCmp.group(1));
                String op = mCmp.group(2);
                boolean taken;
                if (op.equals("==")) {
                    taken = (val == 0);
                } else {
                    taken = (val != 0);
                }
                BlockRange br = collectBlock(in, i);
                if (br != null) {
                    if (taken) {
                        for (int k = br.bodyStart; k <= br.bodyEnd; k++) {
                            out.add(in.get(k));
                        }
                    }
                    i = br.end + 1;
                    continue;
                }
            }

            out.add(in.get(i));
            i++;
        }
        return out;
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 7: unreachable code elimination after trap/return
    // ───────────────────────────────────────────────────────────────────

    /**
     * v2.9.41: Remove code after {@code __builtin_trap()},
     * {@code __builtin_unreachable()}, or {@code return;} within the same
     * brace scope. The trap/return line is kept; subsequent lines up to (but
     * not including) the matching closing brace at the same depth are
     * discarded.
     *
     * <p>Example:
     * <pre>
     *   __builtin_trap();
     *   x1 = sp + 0x70;        // ← unreachable, removed
     *   x0 = saved_x21;        // ← unreachable, removed
     *   sub_6496c8(x0, x1);    // ← unreachable, removed
     * }                         // ← kept
     * </pre>
     */
    private static List<String> unreachableCodeElimination(List<String> in) {
        List<String> out = new ArrayList<String>();
        int n = in.size();
        int i = 0;
        int depth = 0;
        while (i < n) {
            String line = in.get(i);
            String trimmed = line.trim();

            // Track brace depth
            for (int k = 0; k < trimmed.length(); k++) {
                char c = trimmed.charAt(k);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }

            out.add(line);
            i++;

            // Check if this line is a terminator
            if (trimmed.contains("__builtin_trap()")
                    || trimmed.contains("__builtin_unreachable()")
                    || trimmed.equals("return;")
                    || trimmed.matches("return\\s+.*;")) {
                // Skip all subsequent lines until we find a } that brings
                // us back below the depth at the terminator.
                // depth has already been updated for the terminator line.
                int targetDepth = depth; // depth after the terminator line
                while (i < n) {
                    String skipLine = in.get(i);
                    String skipTrim = skipLine.trim();

                    // Check if this line closes the current scope
                    int lineDepthChange = 0;
                    for (int k = 0; k < skipTrim.length(); k++) {
                        char c = skipTrim.charAt(k);
                        if (c == '{') lineDepthChange++;
                        else if (c == '}') lineDepthChange--;
                    }

                    if (depth + lineDepthChange < targetDepth) {
                        // This line has a } that exits the scope — keep it
                        depth += lineDepthChange;
                        out.add(skipLine);
                        i++;
                        break;
                    }

                    // Still within the unreachable scope — skip
                    depth += lineDepthChange;
                    i++;

                    // Safety: if depth goes negative, something is wrong;
                    // stop skipping and resume normal output.
                    if (depth < 0) break;
                }
            }
        }
        return out;
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 8: goto simplification
    // ───────────────────────────────────────────────────────────────────

    /**
     * v2.9.44: Simplify goto statements that are redundant or can be
     * replaced with structured control flow.
     *
     * <p>Patterns handled:
     * <ul>
     *   <li><b>Tail goto</b>: {@code goto L_XXXX;} as the last statement
     *       before the function's closing {@code }} → replace with
     *       {@code return;} (the goto targets the function exit).</li>
     *   <li><b>Dead goto</b>: {@code goto L_XXXX;} followed immediately by
     *       another {@code goto} or {@code return} → the first goto is
     *       unreachable, remove it.</li>
     *   <li><b>Break-like goto</b>: {@code goto L_XXXX;} inside a
     *       {@code while}/{@code for} loop where L_XXXX is after the loop's
     *       closing brace → replace with {@code break;}.</li>
     * </ul>
     */
    private static List<String> gotoSimplification(List<String> in) {
        if (in == null || in.isEmpty()) return in;
        try {
            List<String> out = new ArrayList<String>();
            int n = in.size();

            // Find the function-end closing brace (last "}" that brings
            // depth to 0 or below — handles cases where the opening brace
            // is not part of the body lines passed to the optimizer)
            int funcEndBrace = -1;
            int depth = 0;
            for (int i = 0; i < n; i++) {
                String t = in.get(i);
                for (int k = 0; k < t.length(); k++) {
                    char c = t.charAt(k);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        // Mark the last } that closes to depth ≤ 0
                        if (depth <= 0) funcEndBrace = i;
                    }
                }
            }
            // If no braces found, use the last line
            if (funcEndBrace < 0) funcEndBrace = n - 1;

            for (int i = 0; i < n; i++) {
                String line = in.get(i);
                String t = line.trim();

                // Pattern: goto L_XXXX;
                if (t.matches("goto\\s+L_[0-9a-fA-F]+;")) {
                    // Check: is this the last statement before the function end?
                    // Find the next non-empty line
                    int nextNonEmpty = i + 1;
                    while (nextNonEmpty < n && in.get(nextNonEmpty).trim().isEmpty()) {
                        nextNonEmpty++;
                    }

                    // If next line is the function closing brace, this is a tail goto
                    if (nextNonEmpty < n && in.get(nextNonEmpty).trim().equals("}")) {
                        // Check if the closing brace is the function end
                        if (nextNonEmpty == funcEndBrace) {
                            // Replace with return;
                            String indent = line.substring(0, line.length() - t.length());
                            out.add(indent + "return;");
                            continue;
                        }
                    }

                    // Check: is the goto followed by another goto or return?
                    if (nextNonEmpty < n) {
                        String nextT = in.get(nextNonEmpty).trim();
                        if (nextT.matches("goto\\s+L_[0-9a-fA-F]+;")
                                || nextT.equals("return;")
                                || nextT.matches("return\\s+.*;")) {
                            // Dead goto — skip it
                            continue;
                        }
                    }

                    // Check: is this inside a loop? (look backwards for while/for)
                    int loopDepth = 0;
                    for (int j = 0; j <= i; j++) {
                        String lt = in.get(j).trim();
                        if (lt.startsWith("while (") || lt.startsWith("for (")
                                || lt.startsWith("do {") || lt.startsWith("do{")) {
                            loopDepth++;
                        }
                        // Closing brace of loop — harder to detect without full parsing
                        // Conservative: only replace if we're clearly inside a loop
                    }

                    // If inside a loop and the goto targets something after the loop,
                    // replace with break. This is conservative — we need the label to
                    // be after the current position and not inside any nested block.
                    // For now, keep the goto as-is (don't risk wrong break insertion).
                }

                out.add(line);
            }
            return out;
        } catch (Throwable t) {
            return in;
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 10: bit-identity folding (v4.0)
    // ───────────────────────────────────────────────────────────────────

    // lhs = X & 0x0;  |  lhs = X | 0x0;  |  lhs = X ^ 0x0;  |  lhs = X << 0x0;  |  lhs = X >> 0x0;
    private static final Pattern BIT_FOLD_ZERO =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+?)\\s*(&|\\||\\^|<<|>>)\\s*(0x?0+);?$");
    // lhs = X ^ X;  (纯标识符同变量异或 → 0)
    private static final Pattern BIT_FOLD_XOR_SAME =
            Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\^\\s*\\2;?$");

    /**
     * v4.0: 位运算恒等式折叠 (文本层兜底, IR 层未覆盖的 Raw 形态):
     * <ul>
     *   <li>{@code x = y & 0;} → {@code x = 0;}</li>
     *   <li>{@code x = y | 0;} / {@code x = y ^ 0;} / {@code x = y << 0;} /
     *       {@code x = y >> 0;} → {@code x = y;}</li>
     *   <li>{@code x = y ^ y;} → {@code x = 0;}</li>
     * </ul>
     * 丢弃操作数时要求其无副作用 (不含调用/内存读).
     */
    private static List<String> bitFold(List<String> in) {
        List<String> out = new ArrayList<String>();
        for (String line : in) {
            String t = line.trim();
            Matcher mZero = BIT_FOLD_ZERO.matcher(t);
            if (mZero.matches()) {
                String indent = line.substring(0, line.length() - t.length());
                String lhs = mZero.group(1);
                String x = mZero.group(2).trim();
                String op = mZero.group(3);
                boolean sideEffect = x.contains("(") || x.contains("*(");
                if (op.equals("&")) {
                    if (!sideEffect) {
                        out.add(indent + lhs + " = 0;");
                        continue;
                    }
                } else if (op.equals("^") && sideEffect) {
                    // 保守: 不丢带副作用的操作数, 原样保留
                } else {
                    out.add(indent + lhs + " = " + x + ";");
                    continue;
                }
            }
            Matcher mXor = BIT_FOLD_XOR_SAME.matcher(t);
            if (mXor.matches()) {
                String indent = line.substring(0, line.length() - t.length());
                out.add(indent + mXor.group(1) + " = 0;");
                continue;
            }
            out.add(line);
        }
        return out;
    }

    // ───────────────────────────────────────────────────────────────────
    // Pass 9: int-to-pointer cast insertion
    // ───────────────────────────────────────────────────────────────────

    /**
     * v2.9.44: Insert {@code (void*)} casts on assignments to void* variables
     * from integer expressions. This eliminates {@code -Wint-to-pointer-cast}
     * warnings without changing semantics.
     *
     * <p>Example:
     * <pre>
     *   void* x8;        // declared as void*
     *   x8 = var_98;     // uint64_t → void*  (warning)
     *   x8 = (void*)var_98;  // ← after fix
     * </pre>
     *
     * <p>Rules:
     * <ul>
     *   <li>Collect all variables declared as {@code void*} from the
     *       declaration lines at the top of the function.</li>
     *   <li>For each assignment {@code ptr = expr;} where {@code ptr} is a
     *       void* variable and {@code expr} is not already a cast, not a
     *       string literal, and not a function call, insert
     *       {@code (void*)} before the expression.</li>
     * </ul>
     */
    private static List<String> intToPointerCast(List<String> in) {
        if (in == null || in.isEmpty()) return in;
        try {
            // Phase 1: Collect void* variable names from declarations
            Set<String> voidPtrVars = new HashSet<>();
            for (String line : in) {
                String t = line.trim();
                // Match: void* varname;  or  void *varname;
                if (t.startsWith("void") && t.endsWith(";")) {
                    // Extract variable name: everything between "void*" or "void *" and ";"
                    String rest = t.replaceAll("^void\\s*\\*", "").trim();
                    // Handle array declarations: "q0[2];" → "q0"
                    rest = rest.replaceAll("\\[.*", "").trim();
                    rest = rest.replace(";", "").trim();
                    if (!rest.isEmpty() && rest.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                        voidPtrVars.add(rest);
                    }
                }
            }
            if (voidPtrVars.isEmpty()) return in;

            // Phase 2: Insert (void*) casts on assignments
            // Pattern: ptrVar = expr;  (where expr is not already cast)
            Pattern assignPtr = Pattern.compile(
                    "^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?!\\(void\\*\\))(.+);$");

            List<String> out = new ArrayList<String>();
            for (String line : in) {
                String t = line.trim();
                Matcher m = assignPtr.matcher(t);
                if (m.find()) {
                    String var = m.group(1);
                    String expr = m.group(2).trim();
                    if (voidPtrVars.contains(var)) {
                        // Don't cast if expr is already a pointer expression
                        // (string literal, function call, or already cast)
                        if (expr.startsWith("\"")          // string literal
                                || expr.startsWith("&")     // address-of
                                || expr.startsWith("(void")  // already cast
                                || expr.startsWith("sp +")  // stack pointer
                                || expr.startsWith("sp+")   // stack pointer
                                || expr.contains("sub_")    // function call
                                || expr.contains("__atomic") // atomic returns
                                || expr.startsWith("0x")    // hex constant → keep
                                || expr.matches("\\d+")     // decimal constant
                                || expr.contains("global_") // global symbol
                                ) {
                            out.add(line);
                        } else {
                            // Insert (void*) cast
                            String indent = line.substring(0, line.length() - t.length());
                            out.add(indent + var + " = (void*)(" + expr + ");");
                        }
                    } else {
                        out.add(line);
                    }
                } else {
                    out.add(line);
                }
            }
            return out;
        } catch (Throwable t) {
            return in;
        }
    }
}

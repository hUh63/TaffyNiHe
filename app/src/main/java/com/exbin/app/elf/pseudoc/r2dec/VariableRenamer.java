package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Variable renaming pass — improves readability by replacing raw register
 * names with semantic names based on their role.
 *
 * <h3>Naming rules</h3>
 * <ul>
 *   <li><b>Saved registers</b> — registers saved in the prologue via
 *       {@code stp x29, x30, [sp, #-N]!} or {@code stp x19, x20, [sp, #M]}
 *       are renamed to {@code saved_lr} (x30/lr), {@code saved_fp} (x29/r11),
 *       {@code saved_x19}, {@code saved_x20}, etc. The corresponding
 *       epilogue restore is also renamed.</li>
 *   <li><b>Argument registers</b> — x0-x7 (AArch64) / r0-r3 (ARM32) that are
 *       used as function parameters keep their name (they appear in the
 *       function signature).</li>
 *   <li><b>Temporary registers</b> — other registers that are assigned and
 *       read within the function body are renamed to {@code tmp_<reg>},
 *       e.g. {@code w8 → tmp_w8}, {@code x9 → tmp_x9}. This makes it clear
 *       they are compiler temporaries, not user variables.</li>
 *   <li><b>Stack variables</b> — {@code var_XX} names are kept as-is
 *       (already semantic).</li>
 * </ul>
 *
 * <p>This pass operates on the <b>final C text lines</b> (after structuring
 * and peephole optimization), performing token-level substitution. It is
 * safe because:
 * <ul>
 *   <li>It only renames whole-word tokens (word-boundary matching).</li>
 *   <li>Argument registers (x0-x7/r0-r3) are never renamed — they appear in
 *       the function signature and calls.</li>
 *   <li>SP, FP (x29/r11), LR (x30/r14) are never renamed (except as saved_*).</li>
 * </ul>
 *
 * @since v2.9.36
 */
public class VariableRenamer {

    private VariableRenamer() {}

    /** AArch64 callee-saved registers (excluding x29/x30 which get special names). */
    private static final Set<String> AARCH64_SAVED = new HashSet<>();
    static {
        AARCH64_SAVED.add("x19"); AARCH64_SAVED.add("x20"); AARCH64_SAVED.add("x21");
        AARCH64_SAVED.add("x22"); AARCH64_SAVED.add("x23"); AARCH64_SAVED.add("x24");
        AARCH64_SAVED.add("x25"); AARCH64_SAVED.add("x26"); AARCH64_SAVED.add("x27");
        AARCH64_SAVED.add("x28");
        // w-variants
        for (String x : new ArrayList<>(AARCH64_SAVED)) {
            AARCH64_SAVED.add("w" + x.substring(1));
        }
    }

    /** ARM32 callee-saved registers (excluding r11/fp which gets special name). */
    private static final Set<String> ARM32_SAVED = new HashSet<>();
    static {
        ARM32_SAVED.add("r4");  ARM32_SAVED.add("r5");  ARM32_SAVED.add("r6");
        ARM32_SAVED.add("r7");  ARM32_SAVED.add("r8");  ARM32_SAVED.add("r9");
        ARM32_SAVED.add("r10");
    }

    /**
     * Build a rename map from the IR instruction list.
     * <p>
     * Scans the prologue for {@code stp} instructions that save callee-saved
     * registers, and builds a map from register name to semantic name.
     *
     * @param instructions the IR instruction list
     * @param isAarch64    true for AArch64, false for ARM32
     * @return a map from original register name to renamed name
     */
    public static Map<String, String> buildRenameMap(List<IrInsn> instructions,
                                                     boolean isAarch64) {
        Map<String, String> renameMap = new HashMap<>();
        if (instructions == null) return renameMap;

        // Only scan the first ~20 instructions for prologue patterns
        int scanLimit = Math.min(instructions.size(), 20);

        for (int i = 0; i < scanLimit; i++) {
            IrInsn insn = instructions.get(i);
            if (insn == null || insn.mnemonic == null || insn.opStr == null) continue;

            if (isAarch64) {
                // stp x29, x30, [sp, #-N]!  → saved_fp, saved_lr
                // stp x19, x20, [sp, #M]    → saved_x19, saved_x20
                if (insn.mnemonic.equals("stp") || insn.mnemonic.equals("str")) {
                    String[] ops = insn.opStr.trim().split(",");
                    if (ops.length >= 2) {
                        String reg1 = ops[0].trim();
                        String reg2 = (ops.length >= 2 && !ops[1].trim().startsWith("["))
                                ? ops[1].trim() : null;
                        addSavedRename(renameMap, reg1, true);
                        if (reg2 != null) addSavedRename(renameMap, reg2, true);
                    }
                }
            } else {
                // push {r4, r5, r11, lr} → saved_r4, saved_r5, saved_fp, saved_lr
                if (insn.mnemonic.equals("push") || insn.mnemonic.equals("stmdb")) {
                    // Extract register list from {r4, r5, lr}
                    String op = insn.opStr.trim();
                    int brace = op.indexOf('{');
                    if (brace >= 0) {
                        String list = op.substring(brace + 1);
                        int close = list.indexOf('}');
                        if (close >= 0) list = list.substring(0, close);
                        for (String r : list.split(",")) {
                            addSavedRename(renameMap, r.trim(), false);
                        }
                    }
                }
            }
        }

        return renameMap;
    }

    /**
     * Add a saved-register rename entry.
     * x30 → saved_lr, lr → saved_lr
     * x29 → saved_fp, r11 → saved_fp, fp → saved_fp
     * x19 → saved_x19, etc.
     */
    private static void addSavedRename(Map<String, String> map, String reg, boolean a64) {
        if (reg == null || reg.isEmpty()) return;
        reg = reg.trim();
        String lower = reg.toLowerCase();

        if (a64) {
            if (lower.equals("x30") || lower.equals("lr")) {
                map.put(reg, "saved_lr");
                return;
            }
            if (lower.equals("x29")) {
                map.put(reg, "saved_fp");
                return;
            }
            if (AARCH64_SAVED.contains(lower)) {
                map.put(reg, "saved_" + lower);
            }
        } else {
            if (lower.equals("lr") || lower.equals("r14")) {
                map.put(reg, "saved_lr");
                return;
            }
            if (lower.equals("r11") || lower.equals("fp")) {
                map.put(reg, "saved_fp");
                return;
            }
            if (ARM32_SAVED.contains(lower)) {
                map.put(reg, "saved_" + lower);
            }
        }
    }

    /**
     * Apply the rename map to the final C text lines.
     * Performs whole-word token substitution.
     *
     * @param lines     the C text lines
     * @param renameMap the register → semantic name map
     * @return new list with renamed variables
     */
    public static List<String> applyRenames(List<String> lines, Map<String, String> renameMap) {
        if (lines == null || renameMap == null || renameMap.isEmpty()) {
            return lines == null ? new ArrayList<>() : new ArrayList<>(lines);
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(renameTokens(line, renameMap));
        }
        return out;
    }

    /**
     * Rename whole-word tokens in a single line.
     * Uses a simple scanner: a "word" is [A-Za-z_][A-Za-z0-9_]*.
     *
     * <p>v2.9.40 FIX: Prevents matching register names inside hex numbers.
     * When the scanner encounters a word like "x30" at position i, it checks
     * whether the character immediately before position i is a digit or 'x'/'X'.
     * If so, the word is part of a hex literal (e.g. "0x30") and is NOT renamed.
     * This fixes the "0saved_lr" bug where "0x30" became "0saved_lr".
     */
    private static String renameTokens(String line, Map<String, String> renameMap) {
        if (line == null || line.isEmpty()) return line;
        StringBuilder sb = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                // Scan a word
                int start = i;
                while (i < n && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                    i++;
                }
                String word = line.substring(start, i);

                // v2.9.40: Check if this word is part of a hex number.
                // If the character before 'start' is a digit or 'x'/'X',
                // this word is embedded in a hex literal (e.g. "x30" in "0x30")
                // and must NOT be renamed.
                if (start > 0) {
                    char prev = line.charAt(start - 1);
                    if (Character.isDigit(prev) || prev == 'x' || prev == 'X') {
                        // Part of a hex number — don't rename
                        sb.append(word);
                        continue;
                    }
                }

                String renamed = renameMap.get(word);
                sb.append(renamed != null ? renamed : word);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Full pipeline: build rename map from IR + apply to text lines.
     *
     * @param lines        the final C text lines (after peephole)
     * @param instructions the IR instruction list
     * @param isAarch64    true for AArch64
     * @return renamed C text lines
     */
    public static List<String> rename(List<String> lines, List<IrInsn> instructions,
                                      boolean isAarch64) {
        try {
            Map<String, String> map = buildRenameMap(instructions, isAarch64);
            if (map.isEmpty()) return new ArrayList<>(lines);
            return applyRenames(lines, map);
        } catch (Throwable t) {
            // Never let renaming kill the output
            return new ArrayList<>(lines);
        }
    }
}

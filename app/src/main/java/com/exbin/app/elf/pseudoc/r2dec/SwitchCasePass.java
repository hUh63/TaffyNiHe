package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Switch-case detection pass — identifies jump table patterns in the
 * pseudo-C body and rewrites them as switch statements.
 *
 * <h3>AArch64 jump table pattern</h3>
 * <pre>
 *   // Assembly:
 *   adr x17, .Ljt
 *   ldr wN, [x17, xIdx, lsl #2]
 *   adr x17, .Lcase_base
 *   add x17, x17, wN, uxtw #2    // or lsl #2
 *   br x17
 * </pre>
 *
 * <p>In the structured pseudo-C, this appears as a series of conditional
 * branches or gotos that form a jump table dispatch. Since the control-flow
 * structurer already processes these into gotos, we look for the pattern:
 *
 * <pre>
 *   if (x8 == 0x0) goto L_1000;
 *   if (x8 == 0x1) goto L_1004;
 *   if (x8 == 0x2) goto L_1008;
 *   ...
 *   goto L_default;
 * </pre>
 *
 * <p>And rewrite it as:
 * <pre>
 *   switch (x8) {
 *     case 0x0: goto L_1000;
 *     case 0x1: goto L_1004;
 *     case 0x2: goto L_1008;
 *     default: goto L_default;
 *   }
 * </pre>
 *
 * <h3>Detection criteria</h3>
 * <ul>
 *   <li>≥ 3 consecutive lines matching {@code if (REG == CONST) goto LABEL;}</li>
 *   <li>All comparisons use the same register and == operator</li>
 *   <li>Constants are sequential (0, 1, 2, ...) or near-sequential</li>
 *   <li>Followed by a default goto or break</li>
 * </ul>
 */
public final class SwitchCasePass {

    private SwitchCasePass() {
    }

    // Pattern: if (REG == CONST) goto LABEL;
    private static final Pattern CMP_GOTO = Pattern.compile(
            "if\\s*\\(\\s*([wxr]\\d+)\\s*==\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)\\s*" +
            "(?:goto\\s+(\\w+)\\s*;|break\\s*;)");

    // Minimum number of consecutive comparisons to qualify as a switch
    private static final int MIN_CASES = 3;

    /**
     * Detect and transform switch-case patterns in the body.
     *
     * @param body the structured body lines
     * @return body with switch-cases recovered
     */
    public static List<String> detect(List<String> body) {
        if (body == null || body.isEmpty()) {
            return body == null ? new ArrayList<String>() : new ArrayList<String>(body);
        }

        try {
            List<String> result = new ArrayList<>();
            int i = 0;
            int n = body.size();

            while (i < n) {
                // Try to match a switch-case starting at position i
                SwitchMatch sw = tryMatchSwitch(body, i);
                if (sw != null) {
                    // Emit switch statement
                    result.add("switch (" + sw.switchReg + ") {");
                    for (CaseEntry ce : sw.cases) {
                        result.add("    case " + ce.value + ": goto " + ce.label + ";");
                    }
                    if (sw.defaultLabel != null) {
                        result.add("    default: goto " + sw.defaultLabel + ";");
                    }
                    result.add("}");
                    i = sw.endIndex;
                } else {
                    result.add(body.get(i));
                    i++;
                }
            }

            return result;
        } catch (Throwable t) {
            return new ArrayList<String>(body);
        }
    }

    /**
     * Try to match a switch-case pattern starting at index {@code start}.
     * Returns null if no pattern matches.
     */
    private static SwitchMatch tryMatchSwitch(List<String> body, int start) {
        int n = body.size();
        int i = start;

        // Collect consecutive if(reg == const) goto label; lines
        List<CaseEntry> cases = new ArrayList<>();
        String switchReg = null;

        while (i < n) {
            String line = body.get(i).trim();
            Matcher m = CMP_GOTO.matcher(line);
            if (!m.matches()) break;

            String reg = m.group(1);
            String value = m.group(2);
            String label = m.group(3);

            if (switchReg == null) {
                switchReg = reg;
            } else if (!switchReg.equals(reg)) {
                break; // different register, stop
            }

            cases.add(new CaseEntry(value, label));
            i++;
        }

        // Need at least MIN_CASES consecutive comparisons
        if (cases.size() < MIN_CASES) {
            return null;
        }

        // Check if there's a default goto after the cases
        String defaultLabel = null;
        if (i < n) {
            String line = body.get(i).trim();
            Matcher mDefault = Pattern.compile("goto\\s+(\\w+)\\s*;").matcher(line);
            if (mDefault.matches()) {
                defaultLabel = mDefault.group(1);
                i++;
            }
        }

        SwitchMatch sw = new SwitchMatch();
        sw.switchReg = switchReg;
        sw.cases = cases;
        sw.defaultLabel = defaultLabel;
        sw.endIndex = i;
        return sw;
    }

    // ── Internal types ──

    private static final class CaseEntry {
        final String value;  // e.g. "0x0", "0x1"
        final String label;  // e.g. "L_1000"

        CaseEntry(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    private static final class SwitchMatch {
        String switchReg;
        List<CaseEntry> cases;
        String defaultLabel;
        int endIndex;
    }
}

package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ARM/AArch64 operand tokenizer, ported from r2dec's arm.js {@code parse()}.
 * <p>
 * Splits a raw assembly line into a normalized mnemonic plus a heterogeneous
 * operand array. Memory operands enclosed in square brackets are returned as
 * sub-arrays ({@code String[]}); scalar operands as {@code String}. Zero
 * registers ({@code wzr}/{@code xzr}) are mapped to the literal {@code "0"},
 * the {@code .w} width qualifier is stripped (except for {@code str.w}) and
 * ARM32 IT-block mnemonics are split into {@code it} plus a mask operand.
 */
public final class ArmParser {

    private ArmParser() {
        // utility class
    }

    /** ARM/AArch64 condition suffixes (lowercase). */
    private static final Set<String> COND_SUFFIXES = new HashSet<>(Arrays.asList(
            "eq", "ne", "cs", "hs", "cc", "lo", "mi", "pl", "vs", "vc",
            "hi", "ls", "ge", "lt", "gt", "le", "al"));

    /** Zero-register names that map to the literal "0". */
    private static final Set<String> ZERO_REGS = new HashSet<>(Arrays.asList(
            "wzr", "xzr"));

    /**
     * Parse a raw assembly string.
     *
     * @param assembly the full disassembly line (mnemonic + operands)
     * @return a {@link Parsed} holding the normalized mnemonic and operands
     */
    public static Parsed parse(String assembly) {
        if (assembly == null || assembly.trim().isEmpty()) {
            return new Parsed("", new Object[0]);
        }

        // Tokenize: space-out brackets, drop commas / '#' / braces, collapse whitespace.
        String s = assembly;
        s = s.replace("[", " [ ");
        s = s.replace("]", " ] ");
        s = s.replace(",", " ");
        s = s.replace("#", " ");
        s = s.replace("{", " ");
        s = s.replace("}", " ");
        s = s.replaceAll("\\s+", " ").trim();

        if (s.isEmpty()) {
            return new Parsed("", new Object[0]);
        }

        String[] tokens = s.split(" ");
        String mnem = tokens[0].toLowerCase();

        List<Object> opdList = new ArrayList<>();

        // IT-block handling: "ittt" -> mnem="it", opd[0]="ttt".
        if (mnem.length() > 2 && mnem.startsWith("it") && isItBlockSuffix(mnem.substring(2))) {
            opdList.add(mnem.substring(2));
            mnem = "it";
        } else {
            // "str.xxx" (not "str.w") -> "str_xxx" to avoid mnemonic confusion.
            if (mnem.startsWith("str.") && !mnem.equals("str.w")) {
                mnem = mnem.replace('.', '_');
            } else if (mnem.endsWith(".w") && !mnem.equals("str.w")) {
                // Strip the ".w" width qualifier (str.w is a real store, keep it).
                mnem = mnem.substring(0, mnem.length() - 2);
            }
        }

        // Walk remaining tokens, grouping bracket contents into sub-arrays.
        for (int i = 1; i < tokens.length; i++) {
            String tok = tokens[i];
            if (tok.equals("[")) {
                List<String> mem = new ArrayList<>();
                i++;
                while (i < tokens.length && !tokens[i].equals("]")) {
                    mem.add(normalizeToken(tokens[i]));
                    i++;
                }
                opdList.add(mem.toArray(new String[0]));
            } else if (tok.equals("]")) {
                // stray closing bracket; ignore
            } else {
                opdList.add(normalizeToken(tok));
            }
        }

        return new Parsed(mnem, opdList.toArray());
    }

    /** Lowercase a token and map zero registers to "0". */
    private static String normalizeToken(String tok) {
        String t = tok.toLowerCase();
        if (ZERO_REGS.contains(t)) {
            return "0";
        }
        return t;
    }

    /** True when the suffix following "it" consists solely of 't'/'e'. */
    private static boolean isItBlockSuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c != 't' && c != 'e') {
                return false;
            }
        }
        return true;
    }

    // ── Static condition / operand helpers ──

    /**
     * Map an ARM condition suffix to one of {@code EQ/NE/LT/LE/GT/GE}.
     * Unsigned and sign-flag variants collapse to their signed equivalent.
     *
     * @param suffix condition suffix (e.g. "eq", "hi")
     * @return normalized type, or {@code null} when unmappable (vs/vc/al)
     */
    public static String condTypeFromSuffix(String suffix) {
        if (suffix == null) {
            return null;
        }
        switch (suffix.toLowerCase()) {
            case "eq": return "EQ";
            case "ne": return "NE";
            case "lt": return "LT";
            case "le": return "LE";
            case "gt": return "GT";
            case "ge": return "GE";
            case "cc": case "lo": return "LT";   // unsigned lower / carry clear
            case "cs": case "hs": return "GE";   // unsigned higher-or-same / carry set
            case "hi": return "GT";              // unsigned higher
            case "ls": return "LE";              // unsigned lower-or-same
            case "mi": return "LT";              // negative
            case "pl": return "GE";              // positive or zero
            default:   return null;              // vs / vc / al: no signed mapping
        }
    }

    /** Invert a condition type (EQ&lt;-&gt;NE, LT&lt;-&gt;GE, LE&lt;-&gt;GT). */
    public static String invertCond(String cond) {
        if (cond == null) {
            return null;
        }
        switch (cond.toUpperCase()) {
            case "EQ": return "NE";
            case "NE": return "EQ";
            case "LT": return "GE";
            case "LE": return "GT";
            case "GT": return "LE";
            case "GE": return "LT";
            default:   return cond;
        }
    }

    /** Extract the condition suffix following a '.' in a mnemonic (e.g. "b.eq" -&gt; "eq"). */
    public static String extractCondSuffix(String mn) {
        if (mn == null) {
            return null;
        }
        int dot = mn.indexOf('.');
        if (dot < 0 || dot >= mn.length() - 1) {
            return null;
        }
        String suffix = mn.substring(dot + 1).toLowerCase();
        return COND_SUFFIXES.contains(suffix) ? suffix : null;
    }

    /** Bit width implied by a register's leading character (mirrors r2dec _reg_bits). */
    public static int regBits(String reg) {
        if (reg == null || reg.isEmpty()) {
            return 32;
        }
        switch (Character.toLowerCase(reg.charAt(0))) {
            case 'x': return 64;
            case 'd': return 64;
            case 'q': return 128;
            case 'w': return 32;
            case 's': return 32;
            case 'r': return 32;
            case 'h': return 16;
            case 'b': return 8;
            default:  return 32;
        }
    }

    /** True when the token looks like an ARM/AArch64 register name. */
    public static boolean isRegister(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String sl = s.toLowerCase();
        if (sl.equals("sp") || sl.equals("lr") || sl.equals("pc")
                || sl.equals("fp") || sl.equals("ip")
                || sl.equals("wzr") || sl.equals("xzr")) {
            return true;
        }
        if (sl.length() >= 2) {
            char c = sl.charAt(0);
            char d = sl.charAt(1);
            if ((c == 'x' || c == 'w' || c == 'r' || c == 'd'
                    || c == 's' || c == 'q' || c == 'h' || c == 'b')
                    && Character.isDigit(d)) {
                return true;
            }
        }
        return false;
    }

    /** True when the token is a numeric immediate (decimal or hex, optional leading '#'). */
    public static boolean isImmediate(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String t = s.startsWith("#") ? s.substring(1) : s;
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                Long.parseUnsignedLong(t.substring(2), 16);
                return true;
            }
            if (t.startsWith("-0x") || t.startsWith("-0X")) {
                Long.parseUnsignedLong(t.substring(3), 16);
                return true;
            }
            Long.parseLong(t);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Normalize an immediate token by stripping a leading '#'. */
    public static String normImm(String s) {
        if (s == null) {
            return null;
        }
        return s.startsWith("#") ? s.substring(1) : s;
    }

    // ── Parsed result ──

    /**
     * Result of {@link #parse(String)}: a normalized mnemonic plus a
     * heterogeneous operand array whose elements are {@code String} or
     * {@code String[]} (memory operands).
     */
    public static final class Parsed {
        public final String mnem;
        public final Object[] opd;

        public Parsed(String mnem, Object[] opd) {
            this.mnem = mnem;
            this.opd = opd == null ? new Object[0] : opd;
        }

        /** Operand as a String (or {@code null} if it is a memory sub-array). */
        public String s(int i) {
            if (i < 0 || i >= opd.length) {
                return null;
            }
            Object o = opd[i];
            return o instanceof String ? (String) o : null;
        }

        /** Operand as a String[] memory sub-array (or {@code null}). */
        public String[] m(int i) {
            if (i < 0 || i >= opd.length) {
                return null;
            }
            Object o = opd[i];
            return o instanceof String[] ? (String[]) o : null;
        }

        /** Operand rendered as a single string (memory joined with " + "). */
        public String str(int i) {
            if (i < 0 || i >= opd.length) {
                return null;
            }
            Object o = opd[i];
            if (o instanceof String) {
                return (String) o;
            }
            if (o instanceof String[]) {
                return String.join(" + ", (String[]) o);
            }
            return o == null ? null : o.toString();
        }

        /** Number of operands. */
        public int length() {
            return opd.length;
        }
    }
}

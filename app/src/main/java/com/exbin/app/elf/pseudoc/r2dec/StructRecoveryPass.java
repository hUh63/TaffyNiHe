package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure recovery pass — clusters memory accesses by base address and
 * offset, recovering struct field access patterns.
 *
 * <h3>Problem</h3>
 * <pre>
 *   *(uint64_t*)(x9 + 0x6a0) = x8;
 *   *(uint64_t*)(x10 + 0x8) = x0;
 *   *(uint64_t*)(x10 + 0x10) = x1;
 *   *(uint64_t*)(x10) = x2;
 *   *(uint32_t*)(0x8f9000 + 0x680) = w3;
 *   *(uint32_t*)(0x8f9000 + 0x688) = w4;
 * </pre>
 *
 * <h3>After recovery</h3>
 * <pre>
 *   *(uint64_t*)(x9 + 0x6a0) = x8;    // standalone offset → keep
 *   g_state->field_0 = x2;             // clustered → struct field
 *   g_state->field_8 = x0;
 *   g_state->field_10 = x1;
 *   g_config->field_680 = w3;
 *   g_config->field_688 = w4;
 * </pre>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Collect</b> all memory access expressions: {@code *(type*)(base + offset)}</li>
 *   <li><b>Group</b> by base (register name or global address)</li>
 *   <li><b>Cluster</b>: if a base has ≥ 2 distinct offsets, treat as struct</li>
 *   <li><b>Name</b>: global address → {@code g_<hex>}, register → {@code v_<reg>}</li>
 *   <li><b>Rewrite</b>: {@code *(type*)(base + off)} → {@code name->field_<off>}</li>
 * </ol>
 *
 * <p>Only rewrites accesses to bases with ≥ 2 distinct offsets. Single-offset
 * accesses are left unchanged (they might be simple pointer dereferences).
 */
public final class StructRecoveryPass {

    private StructRecoveryPass() {
    }

    // ── Patterns ──

    /**
     * Match memory access: *(type*)(BASE + OFFSET) or *(type*)(BASE)
     * <p>v2.9.41 FIX: Uses TWO separate patterns instead of one greedy
     * pattern. The old pattern used {@code \)+} (greedy) which ate closing
     * parentheses from enclosing function-pointer call casts, producing
     * broken output like:
     * <pre>
     *   ((void(*)(...))(v_r1->field_18(r0, r1, r2, r3);   // ← broken!
     * </pre>
     * instead of:
     * <pre>
     *   ((void(*)(...))v_r1->field_18)(r0, r1, r2, r3);    // ← correct
     * </pre>
     *
     * <p>Groups: 1=type, 2=base, 3=sign (optional), 4=offset (optional)
     *
     * <p>Double-paren format comes from SSA constant folding:
     * {@code *((uint64_t*)(0x8f9680))}
     * Single-paren format is the default:
     * {@code *(uint32_t*)(r1 + 0x18)}
     */
    private static final Pattern MEM_ACCESS_DBL = Pattern.compile(
            "\\*\\(\\(\\s*(uint\\d+_t|int\\d+_t|void\\s*\\*|char\\s*\\*)\\s*\\*\\)\\(" +
            "\\s*([wxr]\\d+|0x[0-9a-fA-F]+)\\s*" +
            "(?:([+\\-])\\s*(0x[0-9a-fA-F]+|\\d+))?\\s*\\)\\)");

    private static final Pattern MEM_ACCESS_SGL = Pattern.compile(
            "\\*\\(\\s*(uint\\d+_t|int\\d+_t|void\\s*\\*|char\\s*\\*)\\s*\\*\\)\\(" +
            "\\s*([wxr]\\d+|0x[0-9a-fA-F]+)\\s*" +
            "(?:([+\\-])\\s*(0x[0-9a-fA-F]+|\\d+))?\\s*\\)");

    /** Convenience: try double-paren first, then single-paren. */
    private static Matcher matchMemAccess(String line) {
        Matcher mDbl = MEM_ACCESS_DBL.matcher(line);
        if (mDbl.find()) {
            mDbl.reset();
            return mDbl;
        }
        Matcher mSgl = MEM_ACCESS_SGL.matcher(line);
        mSgl.reset();
        return mSgl;
    }

    /**
     * Run structure recovery on the body lines.
     *
     * <p>v2.9.43: EMERGENCY FIX — struct recovery is DISABLED. The previous
     * implementation generated {@code v_x10->field_0} expressions where
     * {@code v_x10} was never declared, causing compilation errors. Also,
     * global address clustering ({@code g_8f9000->field_680}) produced
     * semantically wrong double-dereferences and undeclared globals.
     *
     * <p>The detection logic is preserved below (commented) for future
     * re-enablement once type inference can properly declare pointer-typed
     * base variables. Until then, the original {@code *(type*)(base + offset)}
     * expressions are valid C and require no new declarations.
     *
     * @param body the structured body lines
     * @return body unchanged (struct recovery disabled)
     */
    public static List<String> recover(List<String> body) {
        if (body == null || body.isEmpty()) {
            return body == null ? new ArrayList<String>() : new ArrayList<String>(body);
        }

        // v2.9.43: Struct recovery disabled — return body unchanged.
        // The original *(type*)(base + offset) expressions are valid C.
        // Re-enable plan:
        //   1. Type inference: mark base variables as void* when dereferenced
        //   2. Declare v_base = (void*)base at function entry
        //   3. Then v_base->field_N is valid
        return new ArrayList<String>(body);

        // === DISABLED CODE (preserved for future re-enablement) ===
        // try {
        //     Map<String, String> foldedAddrMap = clusterFoldedAddresses(body);
        //     ... (original detection and rewriting logic) ...
        // } catch (Throwable t) {
        //     return new ArrayList<String>(body);
        // }
    }

    // === Original struct recovery logic (DISABLED in v2.9.43) ===
    // The methods below are preserved for future re-enablement once
    // type inference can properly declare pointer-typed base variables.

    /**
     * v2.9.39: Cluster folded global addresses into struct field notation.
     *
     * <p>After SSA constant folding, addresses like 0x8f9680, 0x8f9688,
     * 0x8f96a0 appear as standalone constants in memory accesses. If ≥2
     * addresses share the same high 16-bit page base (e.g. 0x8f9000),
     * they are likely fields of the same global struct.
     *
     * <p>This method collects all 0xNNNNNN addresses from *(type*)(0xNNNNNN)
     * patterns, groups them by page base, and generates:
     *   0x8f9680 → g_8f9000->field_680
     *   0x8f9688 → g_8f9000->field_688
     *   0x8f96a0 → g_8f9000->field_6a0
     *
     * @return map: original address string → struct field notation
     */
    private static Map<String, String> clusterFoldedAddresses(List<String> body) {
        Map<String, String> result = new HashMap<>();

        // v2.9.41: Use two exact-match patterns instead of one greedy pattern
        // to avoid eating closing parens from enclosing expressions.
        // Double-paren: *((type*)(0xNNNNNN))
        Pattern foldedAccessDbl = Pattern.compile(
                "\\*\\(\\(\\s*(?:uint\\d+_t|int\\d+_t|void\\s*\\*|char\\s*\\*)\\s*\\*\\)\\(" +
                "\\s*(0x[0-9a-fA-F]{4,})\\s*\\)\\)");
        // Single-paren: *(type*)(0xNNNNNN)
        Pattern foldedAccessSgl = Pattern.compile(
                "\\*\\(\\s*(?:uint\\d+_t|int\\d+_t|void\\s*\\*|char\\s*\\*)\\s*\\*\\)\\(" +
                "\\s*(0x[0-9a-fA-F]{4,})\\s*\\)");

        // Collect all folded addresses
        // page base → list of full addresses
        Map<Long, List<Long>> byPage = new HashMap<>();
        Map<Long, String> addrStrMap = new HashMap<>(); // addr → original string

        for (String line : body) {
            if (line == null) continue;
            for (Pattern fap : new Pattern[]{foldedAccessDbl, foldedAccessSgl}) {
                Matcher m = fap.matcher(line);
                while (m.find()) {
                    String addrStr = m.group(1);
                    long addr = parseLongSafe(addrStr);
                    // Page base = high bits (align to 0x1000)
                    long pageBase = addr & ~0xFFF;
                    byPage.computeIfAbsent(pageBase, k -> new ArrayList<>()).add(addr);
                    addrStrMap.put(addr, addrStr);
                }
            }
        }

        // For each page with ≥2 addresses, create struct field mappings
        for (Map.Entry<Long, List<Long>> entry : byPage.entrySet()) {
            List<Long> addrs = entry.getValue();
            if (addrs.size() < 2) continue;

            long pageBase = entry.getKey();
            String structName = "g_" + Long.toHexString(pageBase);

            for (long addr : addrs) {
                long offset = addr - pageBase;
                String origStr = addrStrMap.get(addr);
                String fieldStr;
                if (offset == 0) {
                    fieldStr = structName + "->field_0";
                } else {
                    fieldStr = structName + "->field_" + Long.toHexString(offset);
                }
                result.put(origStr, fieldStr);
            }
        }

        return result;
    }

    /**
     * Rewrite all accesses to a specific base with struct field notation.
     * v2.9.41: Tries double-paren pattern first, then single-paren, to
     * avoid greedy paren matching that broke function-pointer call casts.
     */
    private static String rewriteAccesses(String line, String base, String structName) {
        // Try double-paren pattern first, then single-paren
        String result = rewriteWithPattern(line, base, structName, MEM_ACCESS_DBL);
        result = rewriteWithPattern(result, base, structName, MEM_ACCESS_SGL);
        return result;
    }

    private static String rewriteWithPattern(String line, String base,
                                              String structName, Pattern pat) {
        Matcher m = pat.matcher(line);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String foundBase = m.group(2);
            String sign = m.group(3);
            String offsetStr = m.group(4);

            if (!foundBase.equals(base)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }

            long offset = 0;
            if (offsetStr != null) {
                offset = parseLongSafe(offsetStr);
                if ("-".equals(sign)) offset = -offset;
            }

            // Generate field access: structName->field_OFFSET
            String fieldAccess;
            if (offset == 0) {
                fieldAccess = structName + "->field_0";
            } else if (offset > 0) {
                fieldAccess = structName + "->field_" + Long.toHexString(offset);
            } else {
                fieldAccess = structName + "->field_neg" + Long.toHexString(-offset);
            }

            m.appendReplacement(sb, fieldAccess);
        }
        m.appendTail(sb);

        return sb.toString();
    }

    /**
     * Generate a struct variable name from a base address or register.
     * 0x8f9000 → "g_8f9000"
     * x9 → "v_x9"
     * x10 → "v_x10"
     */
    private static String generateStructName(String base) {
        if (base.startsWith("0x") || base.startsWith("0X")) {
            // Global address
            String hex = base.substring(2).replaceFirst("^0+(?!$)", "");
            return "g_" + hex;
        }
        // Register-based struct pointer
        return "v_" + base;
    }

    private static long parseLongSafe(String s) {
        try {
            s = s.trim();
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic stack-variable naming (v4.2).
 *
 * <p>Renames stack slots whose meaning is fixed by the JNI ABI:
 * the second argument of {@code GetEnv} is {@code JNIEnv**}, i.e. the
 * {@code env} slot:
 * <pre>
 *   (*r0)->GetEnv(r0, &var_14, r2);
 * </pre>
 * becomes
 * <pre>
 *   (*r0)->GetEnv(r0, &env, r2);
 * </pre>
 * The caller applies the returned rename map to the final output so the
 * declaration line ({@code uint64_t var_14;}) is updated in lockstep.
 *
 * <p>Conservative: only renames a variable when the pattern is unambiguous,
 * and never changes register names (their lifetime may span redefinitions).
 */
public final class VariableSemanticsPass {

    private VariableSemanticsPass() {
    }

    /** Result: renamed body + rename map for the caller to apply to declarations. */
    public static final class RenameResult {
        public final List<String> body;
        public final Map<String, String> renames;
        /** v4.6: 语义变量名 → 声明类型 (如 env → JNIEnv*), 由调用方应用到声明行 */
        public final Map<String, String> typeOverrides;

        RenameResult(List<String> body, Map<String, String> renames,
                     Map<String, String> typeOverrides) {
            this.body = body;
            this.renames = renames;
            this.typeOverrides = typeOverrides;
        }
    }

    // (*R)->GetEnv(R, &var_X, ...) — group 1: the env slot variable
    private static final Pattern ENV_SLOT = Pattern.compile(
            "\\(\\*\\w+\\)->GetEnv\\([^,]+,\\s*&(var_\\w+),");

    public static RenameResult run(List<String> body) {
        List<String> safe = (body == null) ? new ArrayList<String>() : new ArrayList<>(body);
        Map<String, String> renames = new HashMap<>();
        Map<String, String> typeOverrides = new HashMap<>();
        for (String line : safe) {
            Matcher m = ENV_SLOT.matcher(line);
            if (m.find()) {
                renames.put(m.group(1), "env");
                typeOverrides.put("env", "JNIEnv*");
                break;
            }
        }
        if (renames.isEmpty()) {
            return new RenameResult(safe, renames, typeOverrides);
        }
        List<String> out = new ArrayList<>(safe.size());
        for (String line : safe) {
            String l = line;
            for (Map.Entry<String, String> e : renames.entrySet()) {
                l = l.replaceAll("\\b" + e.getKey() + "\\b", e.getValue());
            }
            out.add(l);
        }
        return new RenameResult(out, renames, typeOverrides);
    }
}

package com.exbin.app.elf.pseudoc.r2dec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JNI vtable call resolution (v4.2).
 *
 * <p>Recognizes the indirect function-pointer call pattern produced for
 * JNI invocation-interface calls:
 * <pre>
 *   r1 = *(uint32_t*)(r0);                        // r1 = *vm (function table)
 *   ((void(*)(void*, uint32_t, uint32_t, uint32_t))(*(uint32_t*)(r1 + 0x18)))(r0, &var_14, r2, r3);
 * </pre>
 * and rewrites it to the readable form
 * <pre>
 *   (*r0)->GetEnv(r0, &var_14, r2, r3);
 * </pre>
 *
 * <p>Vtable layouts (JNI ABI, fixed):
 * <ul>
 *   <li>{@code JavaVM} (JNIInvokeInterface, 3 reserved slots):
 *       0x0c DestroyJavaVM, 0x10 AttachCurrentThread, 0x14 DetachCurrentThread,
 *       0x18 GetEnv, 0x1c AttachCurrentThreadAsDaemon</li>
 *   <li>{@code JNIEnv} (JNINativeInterface, 4 reserved slots):
 *       0x10 GetVersion, 0x14 DefineClass, 0x18 FindClass, 0x1c FromReflectedMethod,
 *       0x20 FromReflectedField, ... 0x35c RegisterNatives</li>
 * </ul>
 * The table chosen depends on the base pointer type: {@code JavaVM*} → invoke
 * table, {@code JNIEnv*} → native table. Unknown types fall back to the invoke
 * table (JNI_OnLoad's first parameter is a {@code JavaVM*}).
 *
 * <p>Only rewrites when the function-pointer load is a direct dereference of
 * a register that was previously loaded from a parameter register
 * ({@code R = *(uint32_t*)(P)}). Other indirect calls stay unchanged.
 */
public final class JniVtablePass {

    private JniVtablePass() {
    }

    // ((void(*)(TYPES))(*(uintN_t*)(R + 0xOFF)))(ARGS);
    // groups: 1=bits, 2=base reg, 3=offset, 4=args
    private static final Pattern CALL_PATTERN = Pattern.compile(
            "\\(\\s*\\(\\s*void\\s*\\(\\s*\\*\\s*\\)\\s*\\([^)]*\\)\\s*\\)" +
            "\\s*\\(\\s*\\*\\s*\\(\\s*uint(32|64)_t\\s*\\*\\s*\\)\\s*\\(\\s*([rwx]\\d+)\\s*\\+\\s*(0x[0-9a-fA-F]+)\\s*\\)\\s*\\)\\s*\\)" +
            "\\s*\\(([^;]*)\\)\\s*;");

    // R = *(uintN_t*)(P);   groups: 1=base reg, 2=param reg
    private static final Pattern DEREF_PATTERN = Pattern.compile(
            "^\\s*([rwx]\\d+)\\s*=\\s*\\*\\s*\\(\\s*uint(32|64)_t\\s*\\*\\s*\\)\\s*\\(\\s*([rwx]\\d+)\\s*\\)\\s*;$");

    // AArch64 blr: "x3(x0, x1, x2, x3);" or "x0 = x3(x0, x1, x2);"
    // groups: 1=assign dst (optional), 2=callee reg, 3=args
    private static final Pattern REG_CALL = Pattern.compile(
            "^\\s*(?:([rwx]\\d+)\\s*=\\s*)?([rwx]\\d+)\\(([^;]*)\\)\\s*;$");

    /** JavaVM invoke-interface table: offset → method name. */
    private static final Map<Long, String> INVOKE_TABLE = new HashMap<>();

    /** JNIEnv native-interface table (partial): offset → method name. */
    private static final Map<Long, String> NATIVE_TABLE = new HashMap<>();

    static {
        INVOKE_TABLE.put(0x0cL, "DestroyJavaVM");
        INVOKE_TABLE.put(0x10L, "AttachCurrentThread");
        INVOKE_TABLE.put(0x14L, "DetachCurrentThread");
        INVOKE_TABLE.put(0x18L, "GetEnv");
        INVOKE_TABLE.put(0x1cL, "AttachCurrentThreadAsDaemon");

        NATIVE_TABLE.put(0x10L, "GetVersion");
        NATIVE_TABLE.put(0x14L, "DefineClass");
        NATIVE_TABLE.put(0x18L, "FindClass");
        NATIVE_TABLE.put(0x1cL, "FromReflectedMethod");
        NATIVE_TABLE.put(0x20L, "FromReflectedField");
        NATIVE_TABLE.put(0x24L, "ToReflectedMethod");
        NATIVE_TABLE.put(0x28L, "GetSuperclass");
        NATIVE_TABLE.put(0x2cL, "IsAssignableFrom");
        NATIVE_TABLE.put(0x30L, "ToReflectedField");
        NATIVE_TABLE.put(0x34L, "Throw");
        NATIVE_TABLE.put(0x38L, "ThrowNew");
        NATIVE_TABLE.put(0x3cL, "ExceptionOccurred");
        NATIVE_TABLE.put(0x40L, "ExceptionDescribe");
        NATIVE_TABLE.put(0x44L, "ExceptionClear");
        NATIVE_TABLE.put(0x48L, "FatalError");
        NATIVE_TABLE.put(0x4cL, "PushLocalFrame");
        NATIVE_TABLE.put(0x50L, "PopLocalFrame");
        NATIVE_TABLE.put(0x54L, "NewGlobalRef");
        NATIVE_TABLE.put(0x58L, "DeleteGlobalRef");
        NATIVE_TABLE.put(0x5cL, "DeleteLocalRef");
        NATIVE_TABLE.put(0x60L, "IsSameObject");
        NATIVE_TABLE.put(0x64L, "NewLocalRef");
        NATIVE_TABLE.put(0x68L, "EnsureLocalCapacity");
        NATIVE_TABLE.put(0x6cL, "AllocObject");
        NATIVE_TABLE.put(0x70L, "NewObject");
        NATIVE_TABLE.put(0x74L, "NewObjectV");
        NATIVE_TABLE.put(0x78L, "NewObjectA");
        NATIVE_TABLE.put(0x7cL, "GetObjectClass");
        NATIVE_TABLE.put(0x80L, "IsInstanceOf");
        NATIVE_TABLE.put(0x84L, "GetMethodID");
        NATIVE_TABLE.put(0x88L, "CallObjectMethod");
        NATIVE_TABLE.put(0x8cL, "CallObjectMethodV");
        NATIVE_TABLE.put(0x90L, "CallObjectMethodA");
        NATIVE_TABLE.put(0x94L, "CallBooleanMethod");
        NATIVE_TABLE.put(0x98L, "CallBooleanMethodV");
        NATIVE_TABLE.put(0x9cL, "CallBooleanMethodA");
        NATIVE_TABLE.put(0xa0L, "CallByteMethod");
        NATIVE_TABLE.put(0xa4L, "CallByteMethodV");
        NATIVE_TABLE.put(0xa8L, "CallByteMethodA");
        NATIVE_TABLE.put(0xacL, "CallCharMethod");
        NATIVE_TABLE.put(0xb0L, "CallCharMethodV");
        NATIVE_TABLE.put(0xb4L, "CallCharMethodA");
        NATIVE_TABLE.put(0xb8L, "CallShortMethod");
        NATIVE_TABLE.put(0xbcL, "CallShortMethodV");
        NATIVE_TABLE.put(0xc0L, "CallShortMethodA");
        NATIVE_TABLE.put(0xc4L, "CallIntMethod");
        NATIVE_TABLE.put(0xc8L, "CallIntMethodV");
        NATIVE_TABLE.put(0xccL, "CallIntMethodA");
        NATIVE_TABLE.put(0xd0L, "CallLongMethod");
        NATIVE_TABLE.put(0xd4L, "CallLongMethodV");
        NATIVE_TABLE.put(0xd8L, "CallLongMethodA");
        NATIVE_TABLE.put(0xdcL, "CallFloatMethod");
        NATIVE_TABLE.put(0xe0L, "CallFloatMethodV");
        NATIVE_TABLE.put(0xe4L, "CallFloatMethodA");
        NATIVE_TABLE.put(0xe8L, "CallDoubleMethod");
        NATIVE_TABLE.put(0xecL, "CallDoubleMethodV");
        NATIVE_TABLE.put(0xf0L, "CallDoubleMethodA");
        NATIVE_TABLE.put(0xf4L, "CallVoidMethod");
        NATIVE_TABLE.put(0xf8L, "CallVoidMethodV");
        NATIVE_TABLE.put(0xfcL, "CallVoidMethodA");
        NATIVE_TABLE.put(0x100L, "GetFieldID");
        NATIVE_TABLE.put(0x104L, "GetObjectField");
        NATIVE_TABLE.put(0x108L, "GetBooleanField");
        NATIVE_TABLE.put(0x10cL, "GetByteField");
        NATIVE_TABLE.put(0x110L, "GetCharField");
        NATIVE_TABLE.put(0x114L, "GetShortField");
        NATIVE_TABLE.put(0x118L, "GetIntField");
        NATIVE_TABLE.put(0x11cL, "GetLongField");
        NATIVE_TABLE.put(0x120L, "GetFloatField");
        NATIVE_TABLE.put(0x124L, "GetDoubleField");
        NATIVE_TABLE.put(0x128L, "SetObjectField");
        NATIVE_TABLE.put(0x12cL, "SetBooleanField");
        NATIVE_TABLE.put(0x130L, "SetByteField");
        NATIVE_TABLE.put(0x134L, "SetCharField");
        NATIVE_TABLE.put(0x138L, "SetShortField");
        NATIVE_TABLE.put(0x13cL, "SetIntField");
        NATIVE_TABLE.put(0x140L, "SetLongField");
        NATIVE_TABLE.put(0x144L, "SetFloatField");
        NATIVE_TABLE.put(0x148L, "SetDoubleField");
        NATIVE_TABLE.put(0x14cL, "GetStaticMethodID");
        NATIVE_TABLE.put(0x150L, "CallStaticObjectMethod");
        NATIVE_TABLE.put(0x154L, "CallStaticObjectMethodV");
        NATIVE_TABLE.put(0x158L, "CallStaticObjectMethodA");
        NATIVE_TABLE.put(0x15cL, "CallStaticBooleanMethod");
        NATIVE_TABLE.put(0x160L, "CallStaticBooleanMethodV");
        NATIVE_TABLE.put(0x164L, "CallStaticBooleanMethodA");
        NATIVE_TABLE.put(0x168L, "CallStaticByteMethod");
        NATIVE_TABLE.put(0x16cL, "CallStaticByteMethodV");
        NATIVE_TABLE.put(0x170L, "CallStaticByteMethodA");
        NATIVE_TABLE.put(0x174L, "CallStaticCharMethod");
        NATIVE_TABLE.put(0x178L, "CallStaticCharMethodV");
        NATIVE_TABLE.put(0x17cL, "CallStaticCharMethodA");
        NATIVE_TABLE.put(0x180L, "CallStaticShortMethod");
        NATIVE_TABLE.put(0x184L, "CallStaticShortMethodV");
        NATIVE_TABLE.put(0x188L, "CallStaticShortMethodA");
        NATIVE_TABLE.put(0x18cL, "CallStaticIntMethod");
        NATIVE_TABLE.put(0x190L, "CallStaticIntMethodV");
        NATIVE_TABLE.put(0x194L, "CallStaticIntMethodA");
        NATIVE_TABLE.put(0x198L, "CallStaticLongMethod");
        NATIVE_TABLE.put(0x19cL, "CallStaticLongMethodV");
        NATIVE_TABLE.put(0x1a0L, "CallStaticLongMethodA");
        NATIVE_TABLE.put(0x1a4L, "CallStaticFloatMethod");
        NATIVE_TABLE.put(0x1a8L, "CallStaticFloatMethodV");
        NATIVE_TABLE.put(0x1acL, "CallStaticFloatMethodA");
        NATIVE_TABLE.put(0x1b0L, "CallStaticDoubleMethod");
        NATIVE_TABLE.put(0x1b4L, "CallStaticDoubleMethodV");
        NATIVE_TABLE.put(0x1b8L, "CallStaticDoubleMethodA");
        NATIVE_TABLE.put(0x1bcL, "CallStaticVoidMethod");
        NATIVE_TABLE.put(0x1c0L, "CallStaticVoidMethodV");
        NATIVE_TABLE.put(0x1c4L, "CallStaticVoidMethodA");
        NATIVE_TABLE.put(0x1c8L, "GetStaticFieldID");
        NATIVE_TABLE.put(0x1ccL, "GetStaticObjectField");
        NATIVE_TABLE.put(0x1d0L, "GetStaticBooleanField");
        NATIVE_TABLE.put(0x1d4L, "GetStaticByteField");
        NATIVE_TABLE.put(0x1d8L, "GetStaticCharField");
        NATIVE_TABLE.put(0x1dcL, "GetStaticShortField");
        NATIVE_TABLE.put(0x1e0L, "GetStaticIntField");
        NATIVE_TABLE.put(0x1e4L, "GetStaticLongField");
        NATIVE_TABLE.put(0x1e8L, "GetStaticFloatField");
        NATIVE_TABLE.put(0x1ecL, "GetStaticDoubleField");
        NATIVE_TABLE.put(0x1f0L, "SetStaticObjectField");
        NATIVE_TABLE.put(0x1f4L, "SetStaticBooleanField");
        NATIVE_TABLE.put(0x1f8L, "SetStaticByteField");
        NATIVE_TABLE.put(0x1fcL, "SetStaticCharField");
        NATIVE_TABLE.put(0x200L, "SetStaticShortField");
        NATIVE_TABLE.put(0x204L, "SetStaticIntField");
        NATIVE_TABLE.put(0x208L, "SetStaticLongField");
        NATIVE_TABLE.put(0x20cL, "SetStaticFloatField");
        NATIVE_TABLE.put(0x210L, "SetStaticDoubleField");
        NATIVE_TABLE.put(0x214L, "NewString");
        NATIVE_TABLE.put(0x218L, "GetStringLength");
        NATIVE_TABLE.put(0x21cL, "GetStringChars");
        NATIVE_TABLE.put(0x220L, "ReleaseStringChars");
        NATIVE_TABLE.put(0x224L, "NewStringUTF");
        NATIVE_TABLE.put(0x228L, "GetStringUTFLength");
        NATIVE_TABLE.put(0x22cL, "GetStringUTFChars");
        NATIVE_TABLE.put(0x230L, "ReleaseStringUTFChars");
        NATIVE_TABLE.put(0x234L, "GetArrayLength");
        NATIVE_TABLE.put(0x238L, "NewObjectArray");
        NATIVE_TABLE.put(0x23cL, "GetObjectArrayElement");
        NATIVE_TABLE.put(0x240L, "SetObjectArrayElement");
        NATIVE_TABLE.put(0x244L, "NewBooleanArray");
        NATIVE_TABLE.put(0x248L, "NewByteArray");
        NATIVE_TABLE.put(0x24cL, "NewCharArray");
        NATIVE_TABLE.put(0x250L, "NewShortArray");
        NATIVE_TABLE.put(0x254L, "NewIntArray");
        NATIVE_TABLE.put(0x258L, "NewLongArray");
        NATIVE_TABLE.put(0x25cL, "NewFloatArray");
        NATIVE_TABLE.put(0x260L, "NewDoubleArray");
        NATIVE_TABLE.put(0x264L, "GetBooleanArrayElements");
        NATIVE_TABLE.put(0x268L, "GetByteArrayElements");
        NATIVE_TABLE.put(0x26cL, "GetCharArrayElements");
        NATIVE_TABLE.put(0x270L, "GetShortArrayElements");
        NATIVE_TABLE.put(0x274L, "GetIntArrayElements");
        NATIVE_TABLE.put(0x278L, "GetLongArrayElements");
        NATIVE_TABLE.put(0x27cL, "GetFloatArrayElements");
        NATIVE_TABLE.put(0x280L, "GetDoubleArrayElements");
        NATIVE_TABLE.put(0x284L, "ReleaseBooleanArrayElements");
        NATIVE_TABLE.put(0x288L, "ReleaseByteArrayElements");
        NATIVE_TABLE.put(0x28cL, "ReleaseCharArrayElements");
        NATIVE_TABLE.put(0x290L, "ReleaseShortArrayElements");
        NATIVE_TABLE.put(0x294L, "ReleaseIntArrayElements");
        NATIVE_TABLE.put(0x298L, "ReleaseLongArrayElements");
        NATIVE_TABLE.put(0x29cL, "ReleaseFloatArrayElements");
        NATIVE_TABLE.put(0x2a0L, "ReleaseDoubleArrayElements");
        NATIVE_TABLE.put(0x2a4L, "GetBooleanArrayRegion");
        NATIVE_TABLE.put(0x2a8L, "GetByteArrayRegion");
        NATIVE_TABLE.put(0x2acL, "GetCharArrayRegion");
        NATIVE_TABLE.put(0x2b0L, "GetShortArrayRegion");
        NATIVE_TABLE.put(0x2b4L, "GetIntArrayRegion");
        NATIVE_TABLE.put(0x2b8L, "GetLongArrayRegion");
        NATIVE_TABLE.put(0x2bcL, "GetFloatArrayRegion");
        NATIVE_TABLE.put(0x2c0L, "GetDoubleArrayRegion");
        NATIVE_TABLE.put(0x2c4L, "SetBooleanArrayRegion");
        NATIVE_TABLE.put(0x2c8L, "SetByteArrayRegion");
        NATIVE_TABLE.put(0x2ccL, "SetCharArrayRegion");
        NATIVE_TABLE.put(0x2d0L, "SetShortArrayRegion");
        NATIVE_TABLE.put(0x2d4L, "SetIntArrayRegion");
        NATIVE_TABLE.put(0x2d8L, "SetLongArrayRegion");
        NATIVE_TABLE.put(0x2dcL, "SetFloatArrayRegion");
        NATIVE_TABLE.put(0x2e0L, "SetDoubleArrayRegion");
        NATIVE_TABLE.put(0x2e4L, "RegisterNatives");
        NATIVE_TABLE.put(0x2e8L, "UnregisterNatives");
        NATIVE_TABLE.put(0x2ecL, "MonitorEnter");
        NATIVE_TABLE.put(0x2f0L, "MonitorExit");
        NATIVE_TABLE.put(0x2f4L, "GetJavaVM");
        NATIVE_TABLE.put(0x2f8L, "GetStringRegion");
        NATIVE_TABLE.put(0x2fcL, "GetStringUTFRegion");
        NATIVE_TABLE.put(0x300L, "GetPrimitiveArrayCritical");
        NATIVE_TABLE.put(0x304L, "ReleasePrimitiveArrayCritical");
        NATIVE_TABLE.put(0x308L, "GetStringCritical");
        NATIVE_TABLE.put(0x30cL, "ReleaseStringCritical");
        NATIVE_TABLE.put(0x310L, "NewWeakGlobalRef");
        NATIVE_TABLE.put(0x314L, "DeleteWeakGlobalRef");
        NATIVE_TABLE.put(0x318L, "ExceptionCheck");
        NATIVE_TABLE.put(0x31cL, "NewDirectByteBuffer");
        NATIVE_TABLE.put(0x320L, "GetDirectBufferAddress");
        NATIVE_TABLE.put(0x324L, "GetDirectBufferCapacity");
        NATIVE_TABLE.put(0x328L, "GetObjectRefType");
    }

    /**
     * Run the pass on the body lines.
     *
     * @param body       structured body lines (after variable renaming)
     * @param paramTypes map: parameter register name → declared/inferred type
     * @return rewritten body
     */
    public static List<String> run(List<String> body, Map<String, String> paramTypes) {
        if (body == null || body.isEmpty()) {
            return body == null ? new ArrayList<String>() : new ArrayList<String>(body);
        }
        List<String> out = new ArrayList<>(body.size());
        for (String line : body) {
            out.add(rewriteLine(line, body, paramTypes));
        }
        return out;
    }

    private static String rewriteLine(String line, List<String> body, Map<String, String> paramTypes) {
        // ARM32 blx: ((void(*)(...))(*(uintN_t*)(base + off)))(args);
        Matcher m = CALL_PATTERN.matcher(line);
        if (m.find()) {
            String base = m.group(2);
            String offHex = m.group(3);
            long offset;
            try {
                offset = Long.parseUnsignedLong(offHex.substring(2), 16);
            } catch (NumberFormatException e) {
                return line;
            }
            String args = m.group(4);
            if (args == null) return line;
            String paramReg = findDerefSource(base, body);
            if (paramReg == null) return line;
            String method = lookupMethod(offset, paramReg, paramTypes);
            if (method == null) return line;
            // The blx target register (REG = *(uintN_t*)(base + off)) is the
            // function pointer, not a call argument — drop it from the args.
            String targetReg = findFuncPtrTarget(base, offHex, body);
            String filtered = removeArg(args, targetReg);
            String replacement = "(*" + paramReg + ")->" + method + "(" + filtered + ");";
            return m.replaceFirst(Matcher.quoteReplacement(replacement));
        }

        // AArch64 blr: "x3(x0, x1, x2, x3);" or "x0 = x3(x0, x1, x2);"
        Matcher rc = REG_CALL.matcher(line);
        if (!rc.find()) return line;
        String calleeReg = rc.group(2);
        String args = rc.group(3);
        String[] slot = findFuncPtrLoad(calleeReg, body); // {base, offHex}
        if (slot == null) return line;
        long offset;
        try {
            offset = Long.parseUnsignedLong(slot[1].substring(2), 16);
        } catch (NumberFormatException e) {
            return line;
        }
        String paramReg = findDerefSource(slot[0], body);
        if (paramReg == null) return line;
        String method = lookupMethod(offset, paramReg, paramTypes);
        if (method == null) return line;
        // blr target reg is the function pointer — drop it from the args.
        String filtered = removeArg(args, calleeReg);
        String call = "(*" + paramReg + ")->" + method + "(" + filtered + ")";
        String replacement = (rc.group(1) != null ? rc.group(1) + " = " : "") + call + ";";
        return rc.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    /**
     * Remove the blx/blr target register from the argument list — it holds
     * the function pointer, not a call argument. Nested calls are left alone.
     */
    private static String removeArg(String args, String targetReg) {
        if (targetReg == null || args == null || args.contains("(")) return args;
        String[] parts = args.split(",");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.trim().equals(targetReg)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static Map<Long, String> tableFor(String paramReg, Map<String, String> paramTypes) {
        String ptype = paramTypes.get(paramReg);
        return (ptype != null && ptype.contains("JNIEnv")) ? NATIVE_TABLE : INVOKE_TABLE;
    }

    private static String lookupMethod(long offset, String paramReg, Map<String, String> paramTypes) {
        return tableFor(paramReg, paramTypes).get(offset);
    }

    /** Find "REG = *(uintN_t*)(base [+ off]);" for the given base/off — return REG, or null. */
    private static String findFuncPtrTarget(String base, String offHex, List<String> body) {
        for (String line : body) {
            Matcher m = FPTR_LOAD.matcher(line.trim());
            if (!m.matches() || !m.group(3).equals(base)) continue;
            String off = m.group(4);
            if (off == null && (offHex == null || offHex.equalsIgnoreCase("0x0"))) {
                return m.group(1);
            }
            if (off != null && off.equalsIgnoreCase(offHex)) {
                return m.group(1);
            }
        }
        return null;
    }

    /** Find "R = *(uintN_t*)(P);" in body — return P, or null. */
    private static String findDerefSource(String base, List<String> body) {
        for (String line : body) {
            Matcher m = DEREF_PATTERN.matcher(line.trim());
            if (m.matches() && m.group(1).equals(base)) {
                return m.group(3);
            }
        }
        return null;
    }

    // "REG = *(uintN_t*)(BASE);" or "REG = *(uintN_t*)(BASE + 0xOFF);"
    private static final Pattern FPTR_LOAD = Pattern.compile(
            "^\\s*([rwx]\\d+)\\s*=\\s*\\*\\s*\\(\\s*uint(32|64)_t\\s*\\*\\s*\\)\\s*\\(\\s*([rwx]\\d+)(?:\\s*\\+\\s*(0x[0-9a-fA-F]+))?\\s*\\)\\s*;$");

    /** Find "REG = *(uintN_t*)(BASE [+ off]);" — return {base, offHex}, or null. */
    private static String[] findFuncPtrLoad(String reg, List<String> body) {
        for (String line : body) {
            Matcher m = FPTR_LOAD.matcher(line.trim());
            if (m.matches() && m.group(1).equals(reg)) {
                String off = m.group(4);
                return new String[]{m.group(3), off != null ? off : "0x0"};
            }
        }
        return null;
    }
}

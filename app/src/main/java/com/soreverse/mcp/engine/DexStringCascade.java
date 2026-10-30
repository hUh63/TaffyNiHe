package com.soreverse.mcp.engine;

import com.android.tools.smali.dexlib2.ReferenceType;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31c;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31c;

import java.util.ArrayList;
import java.util.List;

/**
 * 类重命名的「字符串常量级联」补齐。
 *
 * dexlib2 的 Rewriter 刻意不提供 string rewriter —— 字符串池里的
 * {@code "Lcom/foo/Bar;"} / {@code "com.foo.Bar"} 不会被 DexClassRenamer 改到。
 * 但真实 App 里 Class.forName / 反射 / 序列化键名大量以字符串形式承载类名，
 * 只改类型描述符会让重命名后的包在运行期找不到类。
 *
 * 这里做的是「可选的后处理」：把 const-string / const-string/jumbo 指令里
 * 与旧类名完全相等的字符串换成新类名。只处理这两种指令格式（字符串加载在 dex
 * 里只有这两条），其它引用一律不动，因此不会误改普通文本。
 *
 * 只匹配**完全相等**的两种写法：
 *   - 类型描述符 com/foo/Bar  → Lcom/foo/Bar;
 *   - 点分类名  com.foo.Bar
 * 不做子串替换（否则 "I love com.foo.Bar" 这种日志文案也会被改）。
 */
public final class DexStringCascade {

    private DexStringCascade() {
    }

    public static final class Result {
        public final DexFile dexFile;
        public final int rewritten;

        public Result(DexFile dexFile, int rewritten) {
            this.dexFile = dexFile;
            this.rewritten = rewritten;
        }
    }

    /**
     * @param oldDotted 旧类名（点分），如 com.foo.Bar
     * @param newDotted 新类名（点分）
     */
    public static Result renameClassStrings(DexFile src, String oldDotted, String newDotted) {
        if (src == null || oldDotted == null || newDotted == null || oldDotted.isEmpty()) {
            throw new IllegalArgumentException("dex/类名为空");
        }
        String oldDescriptor = "L" + oldDotted.replace('.', '/') + ";";
        String newDescriptor = "L" + newDotted.replace('.', '/') + ";";

        int hits = 0;
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef cls : src.getClasses()) {
            List<Method> methods = new ArrayList<>();
            boolean classTouched = false;
            for (Method m : cls.getMethods()) {
                MethodImplementation impl = m.getImplementation();
                if (impl == null) {
                    methods.add(m);
                    continue;
                }
                int methodHits = 0;
                List<Instruction> newInsns = new ArrayList<>();
                for (Instruction insn : impl.getInstructions()) {
                    Instruction replaced = replaceOne(insn, oldDotted, oldDescriptor, newDotted, newDescriptor);
                    if (replaced != insn) methodHits++;
                    newInsns.add(replaced);
                }
                if (methodHits == 0) {
                    methods.add(m);
                    continue;
                }
                classTouched = true;
                hits += methodHits;
                methods.add(new ImmutableMethod(
                        m.getDefiningClass(),
                        m.getName(),
                        m.getParameters(),
                        m.getReturnType(),
                        m.getAccessFlags(),
                        m.getAnnotations(),
                        m.getHiddenApiRestrictions(),
                        new ImmutableMethodImplementation(
                                impl.getRegisterCount(),
                                newInsns,
                                impl.getTryBlocks(),
                                impl.getDebugItems())));
            }
            if (!classTouched) {
                classes.add(cls);
                continue;
            }
            classes.add(new ImmutableClassDef(
                    cls.getType(),
                    cls.getAccessFlags(),
                    cls.getSuperclass(),
                    cls.getInterfaces(),
                    cls.getSourceFile(),
                    cls.getAnnotations(),
                    cls.getFields(),
                    methods));
        }
        return new Result(new ImmutableDexFile(src.getOpcodes(), classes), hits);
    }

    /** 只处理 const-string / const-string/jumbo，且要求内容完全等于旧类名的两种写法。 */
    private static Instruction replaceOne(
            Instruction insn,
            String oldDotted,
            String oldDescriptor,
            String newDotted,
            String newDescriptor) {
        if (!(insn instanceof ReferenceInstruction)) return insn;
        ReferenceInstruction refInsn = (ReferenceInstruction) insn;
        if (refInsn.getReferenceType() != ReferenceType.STRING) return insn;
        if (!(refInsn.getReference() instanceof StringReference)) return insn;
        String value = ((StringReference) refInsn.getReference()).getString();
        String next;
        if (value.equals(oldDescriptor)) {
            next = newDescriptor;
        } else if (value.equals(oldDotted)) {
            next = newDotted;
        } else {
            return insn;
        }
        ImmutableStringReference ref = new ImmutableStringReference(next);
        if (insn instanceof Instruction21c) {
            Instruction21c i = (Instruction21c) insn;
            return new ImmutableInstruction21c(i.getOpcode(), i.getRegisterA(), ref);
        }
        if (insn instanceof Instruction31c) {
            Instruction31c i = (Instruction31c) insn;
            return new ImmutableInstruction31c(i.getOpcode(), i.getRegisterA(), ref);
        }
        return insn;
    }

}

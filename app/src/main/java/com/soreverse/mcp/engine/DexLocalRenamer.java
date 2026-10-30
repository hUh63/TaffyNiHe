package com.soreverse.mcp.engine;

import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.debug.DebugItem;
import com.android.tools.smali.dexlib2.iface.debug.EndLocal;
import com.android.tools.smali.dexlib2.iface.debug.RestartLocal;
import com.android.tools.smali.dexlib2.iface.debug.StartLocal;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableEndLocal;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableRestartLocal;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableStartLocal;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法局部变量重命名：只改调试信息里的 .local / .restart local / .end local，
 * 不动方法签名、不动参数。
 * <p>
 * 按「方法定位 + 寄存器号」精确匹配，同一方法内同名不同寄存器互不影响。
 */
public class DexLocalRenamer {

    private final String ownerType;      // Lcom/foo/A;
    private final String methodName;
    private final String returnType;     // 返回类型描述符
    private final String[] paramTypes;   // 参数类型描述符，可为 null 表示不校验
    private final int targetRegister;
    private final String newName;

    private int renamedCount = 0;

    public DexLocalRenamer(String ownerType, String methodName, String returnType,
                           String[] paramTypes, int targetRegister, String newName) {
        this.ownerType = ownerType;
        this.methodName = methodName;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.targetRegister = targetRegister;
        this.newName = newName;
    }

    public int getRenamedCount() {
        return renamedCount;
    }

    private boolean isTargetMethod(Method m) {
        if (!ownerType.equals(m.getDefiningClass())) return false;
        if (!methodName.equals(m.getName())) return false;
        if (returnType != null && !returnType.equals(m.getReturnType())) return false;
        if (paramTypes == null) return true;
        List<? extends MethodParameter> ps = m.getParameters();
        if (ps.size() != paramTypes.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (!paramTypes[i].equals(ps.get(i).getType())) return false;
        }
        return true;
    }

    /** 只改寄存器匹配目标寄存器的调试项 */
    private DebugItem rewriteDebugItem(DebugItem di) {
        if (di instanceof StartLocal) {
            StartLocal sl = (StartLocal) di;
            if (sl.getRegister() == targetRegister && !newName.equals(sl.getName())) {
                renamedCount++;
                return new ImmutableStartLocal(sl.getCodeAddress(), sl.getRegister(),
                        newName, sl.getType(), sl.getSignature());
            }
        } else if (di instanceof RestartLocal) {
            RestartLocal rl = (RestartLocal) di;
            if (rl.getRegister() == targetRegister && !newName.equals(rl.getName())) {
                renamedCount++;
                return new ImmutableRestartLocal(rl.getCodeAddress(), rl.getRegister(),
                        newName, rl.getType(), rl.getSignature());
            }
        } else if (di instanceof EndLocal) {
            EndLocal el = (EndLocal) di;
            if (el.getRegister() == targetRegister && !newName.equals(el.getName())) {
                renamedCount++;
                return new ImmutableEndLocal(el.getCodeAddress(), el.getRegister(),
                        newName, el.getType(), el.getSignature());
            }
        }
        return di;
    }

    private Method rewriteMethod(Method m) {
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return m;

        List<DebugItem> newDbg = new ArrayList<>();
        boolean changed = false;
        for (DebugItem di : impl.getDebugItems()) {
            DebugItem nd = rewriteDebugItem(di);
            if (nd != di) changed = true;
            newDbg.add(nd);
        }
        if (!changed) return m;

        List<Instruction> insns = new ArrayList<>();
        for (Instruction ins : impl.getInstructions()) insns.add(ins);
        List<com.android.tools.smali.dexlib2.iface.TryBlock<? extends com.android.tools.smali.dexlib2.iface.ExceptionHandler>> tbs =
                new ArrayList<>();
        for (com.android.tools.smali.dexlib2.iface.TryBlock<? extends com.android.tools.smali.dexlib2.iface.ExceptionHandler> tb
                : impl.getTryBlocks()) {
            tbs.add(tb);
        }
        MethodImplementation newImpl = new ImmutableMethodImplementation(
                impl.getRegisterCount(), insns, tbs, newDbg);

        return new ImmutableMethod(
                m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), newImpl);
    }

    public DexFile rename(DexFile src) throws Exception {
        renamedCount = 0;
        List<ClassDef> out = new ArrayList<>();
        boolean any = false;
        for (ClassDef cd : src.getClasses()) {
            if (!ownerType.equals(cd.getType())) {
                out.add(cd);
                continue;
            }
            List<Method> newMethods = new ArrayList<>();
            boolean changed = false;
            for (Method m : cd.getMethods()) {
                if (isTargetMethod(m)) {
                    Method nm = rewriteMethod(m);
                    if (nm != m) changed = true;
                    newMethods.add(nm);
                } else {
                    newMethods.add(m);
                }
            }
            if (changed) {
                any = true;
                List<Field> fields = new ArrayList<>();
                for (Field f : cd.getFields()) fields.add(f);
                List<? extends Annotation> anns = new ArrayList<Annotation>(cd.getAnnotations());
                out.add(new ImmutableClassDef(
                        cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
                        cd.getInterfaces(), cd.getSourceFile(), anns, fields, newMethods));
            } else {
                out.add(cd);
            }
        }
        if (!any) return src;
        return new com.android.tools.smali.dexlib2.immutable.ImmutableDexFile(src.getOpcodes(), out);
    }
}

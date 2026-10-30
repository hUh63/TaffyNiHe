package com.soreverse.mcp.engine;

import com.android.tools.smali.dexlib2.HiddenApiRestriction;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.debug.DebugItem;
import com.android.tools.smali.dexlib2.iface.debug.EndLocal;
import com.android.tools.smali.dexlib2.iface.debug.RestartLocal;
import com.android.tools.smali.dexlib2.iface.debug.StartLocal;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableEndLocal;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableRestartLocal;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableStartLocal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 方法参数重命名：改 .param 里的名字，同时同步 .local / .restart local / .end local。
 * <p>
 * 参数名是纯调试信息，不参与签名匹配，不影响运行时行为。
 * 已 strip 的 dex 参数名为 null，重命名等价于「补名字」。
 */
public class DexParamRenamer {

    private final String ownerType;      // Lcom/foo/A;
    private final String methodName;
    private final String returnType;     // 返回类型描述符
    private final String[] paramTypes;   // 参数类型描述符
    private final int paramIndex;        // 第几个参数（0 起）
    private final String newName;

    private int renamedCount = 0;

    public DexParamRenamer(String ownerType, String methodName, String returnType,
                           String[] paramTypes, int paramIndex, String newName) {
        this.ownerType = ownerType;
        this.methodName = methodName;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.paramIndex = paramIndex;
        this.newName = newName;
    }

    public int getRenamedCount() {
        return renamedCount;
    }

    /** 判断是否是目标方法 */
    private boolean isTargetMethod(Method m) {
        if (!ownerType.equals(m.getDefiningClass())) return false;
        if (!methodName.equals(m.getName())) return false;
        if (returnType != null && !returnType.equals(m.getReturnType())) return false;
        List<? extends MethodParameter> ps = m.getParameters();
        int n = ps.size();
        int expect = paramTypes == null ? 0 : paramTypes.length;
        if (n != expect) return false;
        for (int i = 0; i < n; i++) {
            if (!paramTypes[i].equals(ps.get(i).getType())) return false;
        }
        return true;
    }

    /** 计算第 paramIndex 个参数对应的寄存器号；算不出返回 -1 */
    private int computeParamRegister(Method m) {
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return -1;
        List<? extends MethodParameter> ps = m.getParameters();
        int wideCnt = 0;
        for (MethodParameter p : ps) {
            String t = p.getType();
            if ("J".equals(t) || "D".equals(t)) wideCnt++;
        }
        boolean isStatic = (m.getAccessFlags() & 0x8) != 0;
        int paramRegs = ps.size() + wideCnt + (isStatic ? 0 : 1);
        int base = impl.getRegisterCount() - paramRegs;
        if (base < 0) return -1;
        int reg = base + (isStatic ? 0 : 1);  // 非静态跳过 this
        for (int i = 0; i < paramIndex && i < ps.size(); i++) {
            String t = ps.get(i).getType();
            reg += ("J".equals(t) || "D".equals(t)) ? 2 : 1;
        }
        return reg;
    }

    /** 重写一个方法：改参数名 + 同步调试信息 */
    private Method rewriteMethod(Method m) {
        List<? extends MethodParameter> ps = m.getParameters();
        List<MethodParameter> newParams = new ArrayList<>();
        boolean paramChanged = false;
        for (int i = 0; i < ps.size(); i++) {
            MethodParameter p = ps.get(i);
            if (i == paramIndex) {
                String old = p.getName();
                if (!newName.equals(old)) {
                    paramChanged = true;
                    renamedCount++;
                }
                newParams.add(new ImmutableMethodParameter(
                        p.getType(), p.getAnnotations(), newName));
            } else {
                newParams.add(p);
            }
        }

        MethodImplementation impl = m.getImplementation();
        MethodImplementation newImpl = impl;
        if (impl != null) {
            int targetReg = computeParamRegister(m);
            if (targetReg >= 0) {
                List<DebugItem> newDbg = new ArrayList<>();
                boolean dbgChanged = false;
                for (DebugItem di : impl.getDebugItems()) {
                    DebugItem nd = rewriteDebugItem(di, targetReg);
                    if (nd != di) dbgChanged = true;
                    newDbg.add(nd);
                }
                if (dbgChanged) {
                    List<com.android.tools.smali.dexlib2.iface.TryBlock<? extends com.android.tools.smali.dexlib2.iface.ExceptionHandler>> tbs =
                            new ArrayList<>();
                    for (com.android.tools.smali.dexlib2.iface.TryBlock<? extends com.android.tools.smali.dexlib2.iface.ExceptionHandler> tb
                            : impl.getTryBlocks()) {
                        tbs.add(tb);
                    }
                    List<Instruction> insns = new ArrayList<>();
                    for (Instruction ins : impl.getInstructions()) insns.add(ins);
                    newImpl = new ImmutableMethodImplementation(
                            impl.getRegisterCount(), insns, tbs, newDbg);
                }
            }
        }

        if (!paramChanged && newImpl == impl) return m;

        Set<? extends Annotation> anns = m.getAnnotations();
        Set<HiddenApiRestriction> har = m.getHiddenApiRestrictions();
        return new ImmutableMethod(
                m.getDefiningClass(), m.getName(), newParams, m.getReturnType(),
                m.getAccessFlags(), anns, har, newImpl);
    }

    /** 只改寄存器匹配目标参数的调试项 */
    private DebugItem rewriteDebugItem(DebugItem di, int targetReg) {
        if (di instanceof StartLocal) {
            StartLocal sl = (StartLocal) di;
            if (sl.getRegister() == targetReg && !newName.equals(sl.getName())) {
                renamedCount++;
                return new ImmutableStartLocal(sl.getCodeAddress(), sl.getRegister(),
                        newName, sl.getType(), sl.getSignature());
            }
        } else if (di instanceof RestartLocal) {
            RestartLocal rl = (RestartLocal) di;
            if (rl.getRegister() == targetReg && !newName.equals(rl.getName())) {
                renamedCount++;
                return new ImmutableRestartLocal(rl.getCodeAddress(), rl.getRegister(),
                        newName, rl.getType(), rl.getSignature());
            }
        } else if (di instanceof EndLocal) {
            EndLocal el = (EndLocal) di;
            if (el.getRegister() == targetReg && !newName.equals(el.getName())) {
                renamedCount++;
                return new ImmutableEndLocal(el.getCodeAddress(), el.getRegister(),
                        newName, el.getType(), el.getSignature());
            }
        }
        return di;
    }

    /** 遍历所有类，找到目标方法并重命名 */
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
                out.add(new com.android.tools.smali.dexlib2.immutable.ImmutableClassDef(
                        cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
                        cd.getInterfaces(), cd.getSourceFile(), cd.getAnnotations(),
                        cd.getFields(), newMethods));
            } else {
                out.add(cd);
            }
        }
        if (!any) return src;
        return new com.android.tools.smali.dexlib2.immutable.ImmutableDexFile(src.getOpcodes(), out);
    }
}

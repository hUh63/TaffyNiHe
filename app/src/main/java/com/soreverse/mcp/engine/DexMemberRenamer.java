package com.soreverse.mcp.engine;

import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.rewriter.ClassDefRewriter;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.FieldReferenceRewriter;
import com.android.tools.smali.dexlib2.rewriter.FieldRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodReferenceRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodRewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;

import java.util.ArrayList;
import java.util.List;

/**
 * dex 成员（字段/方法）重命名器。
 * <p>
 * 同时更新定义与所有引用（指令中的 iget/iput/invoke-*）。
 * <p>
 * 实现要点：不调 super.rewrite，直接构造 Immutable*，避免
 * dexlib2 重写器内部递归导致 StackOverflow。
 */
public class DexMemberRenamer {

    public static final int KIND_FIELD = 0;
    public static final int KIND_METHOD = 1;

    private final String ownerSlash;
    private final int kind;
    private final String oldName;
    private final String typeSlash;          // 字段类型 / 方法返回类型
    private final String[] paramTypesSlash;  // 方法参数；字段为 null
    private final String newName;

    private int renamedCount = 0;

    /** 进度回调：每处理完一个类调一次 */
    public interface ProgressListener {
        void onProgress(int done, int total, String className);
    }

    private ProgressListener progressListener;
    private int classTotal = 0;
    private int classDone = 0;

    public void setProgressListener(ProgressListener l) {
        this.progressListener = l;
    }

    public DexMemberRenamer(String ownerSlash, int kind, String oldName,
                            String typeSlash, String[] paramTypesSlash, String newName) {
        this.ownerSlash = ownerSlash;
        this.kind = kind;
        this.oldName = oldName;
        this.typeSlash = typeSlash;
        this.paramTypesSlash = paramTypesSlash;
        this.newName = newName;
    }

    public int getRenamedCount() {
        return renamedCount;
    }

    private boolean matchesFieldRef(FieldReference ref) {
        if (ref == null) return false;
        if (!ownerSlash.equals(ref.getDefiningClass())) return false;
        if (!oldName.equals(ref.getName())) return false;
        return typeSlash == null || typeSlash.equals(ref.getType());
    }

    private boolean matchesMethodRef(MethodReference ref) {
        if (ref == null) return false;
        if (!ownerSlash.equals(ref.getDefiningClass())) return false;
        if (!oldName.equals(ref.getName())) return false;
        if (typeSlash != null && !typeSlash.equals(ref.getReturnType())) return false;
        if (paramTypesSlash != null) {
            List<? extends CharSequence> params = ref.getParameterTypes();
            if (params.size() != paramTypesSlash.length) return false;
            for (int i = 0; i < params.size(); i++) {
                if (!paramTypesSlash[i].contentEquals(params.get(i))) return false;
            }
        }
        return true;
    }

    /** 在内存中执行重命名，返回新 DexFile */
    public DexFile rename(DexFile src) throws Exception {
        renamedCount = 0;
        classDone = 0;
        classTotal = src.getClasses().size();

        DexRewriter rewriter = new DexRewriter(new RewriterModule() {

            @Override
            public FieldRewriter getFieldRewriter(Rewriters rewriters) {
                return new FieldRewriter(rewriters) {
                    @Override
                    public Field rewrite(Field field) {
                        if (kind == KIND_FIELD
                                && ownerSlash.equals(field.getDefiningClass())
                                && oldName.equals(field.getName())
                                && (typeSlash == null || typeSlash.equals(field.getType()))) {
                            renamedCount++;
                            return new ImmutableField(
                                    field.getDefiningClass(),
                                    newName,
                                    field.getType(),
                                    field.getAccessFlags(),
                                    field.getInitialValue(),
                                    field.getAnnotations(),
                                    field.getHiddenApiRestrictions());
                        }
                        // 不调 super.rewrite，避免 RewrittenField 递归
                        return field;
                    }
                };
            }

            @Override
            public MethodRewriter getMethodRewriter(Rewriters rewriters) {
                return new MethodRewriter(rewriters) {
                    @Override
                    public Method rewrite(Method method) {
                        boolean match = (kind == KIND_METHOD)
                                && ownerSlash.equals(method.getDefiningClass())
                                && oldName.equals(method.getName())
                                && (typeSlash == null || typeSlash.equals(method.getReturnType()));
                        if (match && paramTypesSlash != null) {
                            List<? extends MethodParameter> params = method.getParameters();
                            if (params.size() != paramTypesSlash.length) {
                                match = false;
                            } else {
                                for (int i = 0; i < params.size(); i++) {
                                    if (!paramTypesSlash[i].equals(params.get(i).getType())) {
                                        match = false;
                                        break;
                                    }
                                }
                            }
                        }

                        // 必须重建所有方法体：里面可能引用被改名成员
                        MethodImplementation origImpl = method.getImplementation();
                        MethodImplementation newImpl = origImpl;
                        if (origImpl != null) {
                            newImpl = rewriters.getMethodImplementationRewriter().rewrite(origImpl);
                        }

                        if (match) {
                            renamedCount++;
                            return new ImmutableMethod(
                                    method.getDefiningClass(),
                                    newName,
                                    method.getParameters(),
                                    method.getReturnType(),
                                    method.getAccessFlags(),
                                    method.getAnnotations(),
                                    method.getHiddenApiRestrictions(),
                                    newImpl);
                        }
                        if (newImpl != origImpl) {
                            return new ImmutableMethod(
                                    method.getDefiningClass(),
                                    method.getName(),
                                    method.getParameters(),
                                    method.getReturnType(),
                                    method.getAccessFlags(),
                                    method.getAnnotations(),
                                    method.getHiddenApiRestrictions(),
                                    newImpl);
                        }
                        return method;
                    }
                };
            }

            @Override
            public FieldReferenceRewriter getFieldReferenceRewriter(Rewriters rewriters) {
                return new FieldReferenceRewriter(rewriters) {
                    @Override
                    public FieldReference rewrite(FieldReference ref) {
                        if (matchesFieldRef(ref)) {
                            renamedCount++;
                            return new ImmutableFieldReference(
                                    ref.getDefiningClass(), newName, ref.getType());
                        }
                        return ref;
                    }
                };
            }

            @Override
            public MethodReferenceRewriter getMethodReferenceRewriter(Rewriters rewriters) {
                return new MethodReferenceRewriter(rewriters) {
                    @Override
                    public MethodReference rewrite(MethodReference ref) {
                        if (matchesMethodRef(ref)) {
                            renamedCount++;
                            List<String> p = new ArrayList<>();
                            for (CharSequence c : ref.getParameterTypes()) p.add(c.toString());
                            return new ImmutableMethodReference(
                                    ref.getDefiningClass(), newName, p, ref.getReturnType());
                        }
                        return ref;
                    }
                };
            }

            @Override
            public ClassDefRewriter getClassDefRewriter(Rewriters rewriters) {
                return new ClassDefRewriter(rewriters) {
                    @Override
                    public ClassDef rewrite(ClassDef cd) {
                        ClassDef out = super.rewrite(cd);
                        int done = ++classDone;
                        ProgressListener pl = progressListener;
                        if (pl != null) pl.onProgress(done, classTotal, cd.getType());
                        return out;
                    }
                };
            }
        });

        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(src);
        // 物化，避免下游 parse 时再触发重写
        java.util.List<ClassDef> materialized = new java.util.ArrayList<>();
        for (ClassDef c : rewritten.getClasses()) {
            materialized.add(c);
        }
        return new com.android.tools.smali.dexlib2.immutable.ImmutableDexFile(src.getOpcodes(), materialized);
    }
}

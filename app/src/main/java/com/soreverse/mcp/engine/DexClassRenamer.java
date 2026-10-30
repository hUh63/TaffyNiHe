package com.soreverse.mcp.engine;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.rewriter.ClassDefRewriter;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.rewriter.TypeRewriter;

import java.util.ArrayList;
import java.util.List;

/**
 * dex 重命名器，两种模式：
 * 1. 单类模式：一个类 + 其内部类（Outer$Inner）
 * 2. 包前缀模式：某个包下所有类（含子包）
 * <p>
 * 只改类型描述符及其所有引用（字段类型/方法签名/指令/注解/枚举/数组），
 * 不改字符串常量里的反射类名（dexlib2 无 getStringRewriter）。
 */
public class DexClassRenamer {

    public interface ProgressListener {
        void onProgress(int done, int total, String className);
    }

    private final String oldType;
    private final String newType;
    private final String oldInnerPrefix;
    private final String newInnerPrefix;

    /** 包前缀模式时非 null，形如 Lcom/foo/ */
    private final String oldPkgPrefix;
    private final String newPkgPrefix;

    private int renamedCount = 0;
    private int classTotal = 0;
    private int classDone = 0;
    private ProgressListener progressListener;

    /** 单类模式 */
    public DexClassRenamer(String oldType, String newType) {
        this.oldType = oldType;
        this.newType = newType;
        this.oldInnerPrefix = oldType.substring(0, oldType.length() - 1) + "$";
        this.newInnerPrefix = newType.substring(0, newType.length() - 1) + "$";
        this.oldPkgPrefix = null;
        this.newPkgPrefix = null;
    }

    /** 包前缀模式专用构造（第三参数仅用于区分重载，固定传 true） */
    private DexClassRenamer(String oldPkgPrefix, String newPkgPrefix, boolean pkgMode) {
        this.oldType = null;
        this.newType = null;
        this.oldInnerPrefix = null;
        this.newInnerPrefix = null;
        this.oldPkgPrefix = oldPkgPrefix;
        this.newPkgPrefix = newPkgPrefix;
    }

    /** 包前缀模式：oldPkgSlash / newPkgSlash 形如 com/foo（不带 L 和结尾斜杠） */
    public static DexClassRenamer forPackage(String oldPkgSlash, String newPkgSlash) {
        return new DexClassRenamer("L" + oldPkgSlash + "/", "L" + newPkgSlash + "/", true);
    }

    public void setProgressListener(ProgressListener l) {
        this.progressListener = l;
    }

    public int getRenamedCount() {
        return renamedCount;
    }

    private String mapType(String t) {
        if (t == null) return null;
        if (oldPkgPrefix != null) {
            if (t.startsWith(oldPkgPrefix)) {
                renamedCount++;
                return newPkgPrefix + t.substring(oldPkgPrefix.length());
            }
            return t;
        }
        if (t.equals(oldType)) {
            renamedCount++;
            return newType;
        }
        if (t.startsWith(oldInnerPrefix)) {
            renamedCount++;
            return newInnerPrefix + t.substring(oldInnerPrefix.length());
        }
        return t;
    }

    public DexFile rename(DexFile src) throws Exception {
        renamedCount = 0;
        classDone = 0;
        classTotal = src.getClasses().size();

        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public TypeRewriter getTypeRewriter(Rewriters rewriters) {
                return new TypeRewriter() {
                    @Override
                    protected String rewriteUnwrappedType(String type) {
                        return mapType(type);
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
        List<ClassDef> materialized = new ArrayList<>();
        for (ClassDef c : rewritten.getClasses()) {
            materialized.add(c);
        }
        return new com.android.tools.smali.dexlib2.immutable.ImmutableDexFile(src.getOpcodes(), materialized);
    }
}

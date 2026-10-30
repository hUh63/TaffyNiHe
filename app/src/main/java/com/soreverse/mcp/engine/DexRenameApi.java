package com.soreverse.mcp.engine;

import com.android.tools.smali.dexlib2.iface.DexFile;

/**
 * dex 重命名的统一公共入口。
 *
 * 把原先散落在 MainActivity / SmaliActivity 里的
 * 「构造 DexXxxRenamer → 设进度 → rename → 取计数」流程收拢到这里，
 * 调用方只关心「改哪个、改成什么」，不再重复拼描述符、判断静态/宽类型。
 *
 * 约定：
 * - 类名、包名一律传「点分」形式，如 com.foo.Bar、com.foo
 * - 字段/方法名传原始名，类型传可读类型（int、java.lang.String、int[]）
 * - 所有方法都是纯函数式：只产生新的内存 DexFile，不写磁盘、不动 DexCache，
 *   落回缓存由调用方自行决定（通常是 DexEditSession.apply）
 */
public final class DexRenameApi {

    private DexRenameApi() {
    }

    /** 统一进度回调：done/total 为已处理/总类数 */
    public interface Progress {
        void onProgress(int done, int total);
    }

    /** 重命名结果：新的内存 dex 与命中处数 */
    public static final class Result {
        public final DexFile dexFile;
        public final int renamedCount;

        public Result(DexFile dexFile, int renamedCount) {
            this.dexFile = dexFile;
            this.renamedCount = renamedCount;
        }
    }

    // ===== 类型转换工具 =====

    /** 点分类名 → 类型描述符，com.foo.Bar → Lcom/foo/Bar; */
    public static String toType(String dottedClass) {
        if (dottedClass == null || dottedClass.isEmpty()) return null;
        return "L" + dottedClass.replace('.', '/') + ";";
    }

    /** 可读类型 → smali 描述符：int → I，java.lang.String → Ljava/lang/String;，int[] → [I */
    public static String toSmaliType(String readable) {
        if (readable == null) return "V";
        int arr = 0;
        String t = readable;
        while (t.endsWith("[]")) {
            arr++;
            t = t.substring(0, t.length() - 2);
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < arr; i++) prefix.append('[');

        String base;
        switch (t) {
            case "void": base = "V"; break;
            case "boolean": base = "Z"; break;
            case "byte": base = "B"; break;
            case "short": base = "S"; break;
            case "char": base = "C"; break;
            case "int": base = "I"; break;
            case "long": base = "J"; break;
            case "float": base = "F"; break;
            case "double": base = "D"; break;
            default:
                base = t.indexOf('.') >= 0
                        ? "L" + t.replace('.', '/') + ";"
                        : "L" + t + ";";
        }
        return prefix + base;
    }

    /** 可读类型数组 → smali 描述符数组 */
    public static String[] toSmaliTypes(String[] readable) {
        if (readable == null) return null;
        String[] out = new String[readable.length];
        for (int i = 0; i < readable.length; i++) out[i] = toSmaliType(readable[i]);
        return out;
    }

    // ===== 类 / 包 =====

    /** 重命名单个类（含其内部类 Outer$Inner） */
    public static Result renameClass(DexFile src, String oldDotted, String newDotted,
                                     Progress progress) throws Exception {
        requireDex(src);
        String oldType = toType(oldDotted);
        String newType = toType(newDotted);
        if (oldType == null || newType == null) throw new IllegalArgumentException("类名为空");
        if (oldType.equals(newType)) throw new IllegalArgumentException("新旧类名相同");

        DexClassRenamer rn = new DexClassRenamer(oldType, newType);
        if (progress != null) {
            rn.setProgressListener((done, total, cn) -> progress.onProgress(done, total));
        }
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    /** 重命名整个包（含子包） */
    public static Result renamePackage(DexFile src, String oldPkgDotted, String newPkgDotted,
                                       Progress progress) throws Exception {
        requireDex(src);
        if (oldPkgDotted == null || oldPkgDotted.isEmpty()
                || newPkgDotted == null || newPkgDotted.isEmpty()) {
            throw new IllegalArgumentException("包名为空");
        }
        if (oldPkgDotted.equals(newPkgDotted)) throw new IllegalArgumentException("新旧包名相同");

        DexClassRenamer rn = DexClassRenamer.forPackage(
                oldPkgDotted.replace('.', '/'), newPkgDotted.replace('.', '/'));
        if (progress != null) {
            rn.setProgressListener((done, total, cn) -> progress.onProgress(done, total));
        }
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    // ===== 字段 / 方法 =====

    /** 重命名字段；typeReadable 传字段可读类型 */
    public static Result renameField(DexFile src, String ownerDotted, String oldName,
                                     String typeReadable, String newName,
                                     Progress progress) throws Exception {
        return renameMember(src, ownerDotted, DexMemberRenamer.KIND_FIELD,
                oldName, typeReadable, null, newName, progress);
    }

    /** 重命名方法；returnReadable/paramReadables 传可读类型，用于精确匹配重载 */
    public static Result renameMethod(DexFile src, String ownerDotted, String oldName,
                                      String returnReadable, String[] paramReadables,
                                      String newName, Progress progress) throws Exception {
        return renameMember(src, ownerDotted, DexMemberRenamer.KIND_METHOD,
                oldName, returnReadable, paramReadables, newName, progress);
    }

    private static Result renameMember(DexFile src, String ownerDotted, int kind,
                                       String oldName, String typeReadable,
                                       String[] paramReadables, String newName,
                                       Progress progress) throws Exception {
        requireDex(src);
        String owner = toType(ownerDotted);
        if (owner == null || oldName == null || oldName.isEmpty()) {
            throw new IllegalArgumentException("属主或成员名为空");
        }
        if (newName == null || newName.isEmpty()) throw new IllegalArgumentException("新名为空");

        String type = typeReadable == null ? null : toSmaliType(typeReadable);
        String[] params = paramReadables == null ? null : toSmaliTypes(paramReadables);

        DexMemberRenamer rn = new DexMemberRenamer(owner, kind, oldName, type, params, newName);
        if (progress != null) {
            rn.setProgressListener((done, total, cn) -> progress.onProgress(done, total));
        }
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    // ===== 参数 / 局部变量 =====

    /** 重命名方法参数；paramIndex 从 0 起，不含 this */
    public static Result renameParam(DexFile src, String ownerDotted, String methodName,
                                     String returnReadable, String[] paramReadables,
                                     int paramIndex, String newName) throws Exception {
        requireDex(src);
        String owner = toType(ownerDotted);
        if (owner == null || methodName == null) throw new IllegalArgumentException("属主或方法名为空");
        if (paramIndex < 0) throw new IllegalArgumentException("参数下标非法");
        if (newName == null || newName.isEmpty()) throw new IllegalArgumentException("新名为空");

        DexParamRenamer rn = new DexParamRenamer(owner, methodName,
                toSmaliType(returnReadable), toSmaliTypes(paramReadables), paramIndex, newName);
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    /** 重命名方法内局部变量；register 为绝对寄存器号（vN 的 N） */
    public static Result renameLocal(DexFile src, String ownerDotted, String methodName,
                                     String returnReadable, String[] paramReadables,
                                     int register, String newName) throws Exception {
        requireDex(src);
        String owner = toType(ownerDotted);
        if (owner == null || methodName == null) throw new IllegalArgumentException("属主或方法名为空");
        if (register < 0) throw new IllegalArgumentException("寄存器号非法");
        if (newName == null || newName.isEmpty()) throw new IllegalArgumentException("新名为空");

        DexLocalRenamer rn = new DexLocalRenamer(owner, methodName,
                toSmaliType(returnReadable), toSmaliTypes(paramReadables), register, newName);
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    // ===== 原始描述符重载：供已持有 smali 描述符的调用方直接使用 =====

    public static Result renameClassRaw(DexFile src, String oldType, String newType,
                                        Progress progress) throws Exception {
        requireDex(src);
        if (oldType == null || newType == null) throw new IllegalArgumentException("类型为空");
        if (oldType.equals(newType)) throw new IllegalArgumentException("新旧类型相同");
        DexClassRenamer rn = new DexClassRenamer(oldType, newType);
        if (progress != null) {
            rn.setProgressListener((done, total, cn) -> progress.onProgress(done, total));
        }
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    public static Result renamePackageRaw(DexFile src, String oldPkgSlash, String newPkgSlash,
                                          Progress progress) throws Exception {
        requireDex(src);
        if (oldPkgSlash == null || newPkgSlash == null) throw new IllegalArgumentException("包名为空");
        if (oldPkgSlash.equals(newPkgSlash)) throw new IllegalArgumentException("新旧包名相同");
        DexClassRenamer rn = DexClassRenamer.forPackage(oldPkgSlash, newPkgSlash);
        if (progress != null) {
            rn.setProgressListener((done, total, cn) -> progress.onProgress(done, total));
        }
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    public static Result renameMemberRaw(DexFile src, String ownerType, int kind,
                                         String oldName, String typeSlash,
                                         String[] paramTypesSlash, String newName,
                                         Progress progress) throws Exception {
        requireDex(src);
        if (ownerType == null || oldName == null || newName == null) {
            throw new IllegalArgumentException("属主/旧名/新名不能为空");
        }
        DexMemberRenamer rn = new DexMemberRenamer(ownerType, kind, oldName,
                typeSlash, paramTypesSlash, newName);
        if (progress != null) {
            rn.setProgressListener((done, total, cn) -> progress.onProgress(done, total));
        }
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    public static Result renameParamRaw(DexFile src, String ownerType, String methodName,
                                        String returnType, String[] paramTypes,
                                        int paramIndex, String newName) throws Exception {
        requireDex(src);
        DexParamRenamer rn = new DexParamRenamer(ownerType, methodName,
                returnType, paramTypes, paramIndex, newName);
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    public static Result renameLocalRaw(DexFile src, String ownerType, String methodName,
                                        String returnType, String[] paramTypes,
                                        int register, String newName) throws Exception {
        requireDex(src);
        DexLocalRenamer rn = new DexLocalRenamer(ownerType, methodName,
                returnType, paramTypes, register, newName);
        DexFile out = rn.rename(src);
        return new Result(out, rn.getRenamedCount());
    }

    private static void requireDex(DexFile src) {
        if (src == null) throw new IllegalArgumentException("dex 为空");
    }
}

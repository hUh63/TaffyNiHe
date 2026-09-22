package com.exbin.app.elf;

import android.util.Log;

import androidx.annotation.Nullable;

import com.exbin.app.nativebridge.NativeBridge;
import com.exbin.app.util.TypeMapper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v2.0.9: 函数签名分析器 (callee + caller 双向夹击)
 * <p>
 * 参数识别策略 (用户 v2.0.9 反馈):
 * <ol>
 *   <li><b>callee-side</b>: 读函数体, 追踪每个 R0-R3/X0-X7 在被覆写前是否被读.
 *       给出"至少几个参数" (min bound).</li>
 *   <li><b>caller-side</b>: 扫所有 bl target = fn 的 caller 函数, 在每个 call site
 *       往前找 mov r0/.../r3 (或 mov x0/.../x7) 看 caller 设了几个. 取所有 call site 的
 *       最大值作"最多几个" (max bound).</li>
 *   <li><b>SP 偏移追踪</b>: 通过函数序言 sub sp, sp, #N (或 push {regs}) 算出 SP delta,
 *       区分 sp+ 局部变量 (delta 之后) 和 caller 压入的 arg5+ (delta 之前).</li>
 *   <li><b>综合输出</b>: 给出 [min, max] 区间 + 置信度 (high/medium/low) + flags
 *       (varargs_detected, hand_written_asm_warn, sp_tracking_lost_at).</li>
 * </ol>
 * <p>
 * 历史: v2.0.5/v2.0.8 仅做 callee-side first-def/first-use, 没有 caller-side 验证,
 * 也没有 SP 偏移追踪, 对 arg5+ 容易漏.
 */
public final class FunctionSignatureAnalyzer {

    /** v2.0.9: 简单可空注解 (不依赖 androidx) */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
    public @interface Nullable {}

    private static final String TAG = "FuncSigAnalyzer";

    /** v2.3.2: 当前 SO 文件路径, 供 native restoreSignatures 使用. */
    private static volatile String sSoPath = null;
    private static final long MAX_SO_BYTES = 64 * 1024 * 1024L; // 64 MB 上限

    // v3.2.6: demangle 缓存 — 同一函数名多次还原时 O(1) 命中, 减少 JNI 往返
    private static final ConcurrentHashMap<String, String> sDemangleCache = new ConcurrentHashMap<>();
    // 同一签名占大量 CPU, 用守卫避免并发触发 (签名还原的 idempotency 标志)
    private static final ConcurrentHashMap<String, AtomicInteger> sRestoreInFlight = new ConcurrentHashMap<>();
    // 后台签名还原线程池: 低优先级, 串行, 不阻塞其他解析工作
    private static final ExecutorService sRestoreExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SignatureRestore-worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /**
     * 设置当前分析的 SO 文件路径. 在 ElfParser 解析完成后调用.
     */
    public static void setSoPath(String path) {
        sSoPath = path;
    }

    /**
     * v3.2.6: 异步签名还原 (后台线程跑, 不阻塞 UI/解析主流程).
     * <p>
     * 调用链:
     *  - ParseEngine.finalizeAsync 在解析成功后立即调本方法, 让用户在解析完成
     *    对话框可即刻点 "进入详情", 而签名还原在后台线程慢慢跑
     *  - FuncListTabFragment 也会调本方法作为 On-Shot 按需补全
     * <p>
     * 幂等性:
     *  - 通过 elf.signaturesRestored 标志 + sRestoreInFlight 守卫, 同一 ElfFile
     *    多次调用只会真正跑一次 batchRestoreSignatures
     * <p>
     * 进度反馈: 通过 onComplete 回调 (在调用线程 — 通常主线程 — 或 background)
     * <p>
     * 注意: 不影响精度, 只是把工作搬到后台 + 去重
     */
    public static void batchRestoreSignaturesAsync(final ElfFile elf,
                                                    @Nullable final Runnable onComplete) {
        if (elf == null || elf.header == null) {
            if (onComplete != null) onComplete.run();
            return;
        }
        // 已有结果, 直接回调
        if (elf.signaturesRestored) {
            if (onComplete != null) onComplete.run();
            return;
        }
        // 并发去重: 同一文件第一调用入队, 其余直接挂回调到等待通知
        final String key = elf.filePath != null ? elf.filePath
                : String.valueOf(System.identityHashCode(elf));
        AtomicInteger guard = sRestoreInFlight.computeIfAbsent(key, k -> new AtomicInteger(0));
        guard.incrementAndGet(); // 计数正在等待完成的人数
        if (guard.get() > 1) {
            // 已有人在跑, 直接挂回调等结果 (当前跑完会回调所有等待者)
            // 这里用一个简单同步: 轮询 elf.signaturesRestored; 总耗时有限
            sRestoreExecutor.submit(() -> {
                while (!elf.signaturesRestored && !Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { return; }
                }
                if (onComplete != null) onComplete.run();
            });
            return;
        }
        // 第一个 — 实际跑
        sRestoreExecutor.submit(() -> {
            try {
                if (!elf.signaturesRestored) {
                    long t0 = System.currentTimeMillis();
                    int machine = elf.header.eMachine;
                    int n = batchRestoreSignatures(elf.functions, elf, machine);
                    elf.signaturesRestored = true;
                    Log.i(TAG, "batchRestoreAsync: " + n + " restored in "
                            + (System.currentTimeMillis() - t0) + "ms");
                }
            } catch (Throwable t) {
                Log.w(TAG, "batchRestoreAsync failed", t);
            } finally {
                if (onComplete != null) onComplete.run();
                sRestoreInFlight.remove(key);
            }
        });
    }

    /** v3.2.6: 缓存包装的 demangle — 同一函数名不重复走 native */
    static String demangleCached(String name) {
        if (name == null) return null;
        String cached = sDemangleCache.get(name);
        if (cached != null) return cached;
        // 第一次 miss, 调 native 并写回; 用 putIfAbsent 避免并发时写两次
        String demangled = NativeBridge.demangle(name);
        String prev = sDemangleCache.putIfAbsent(name, demangled != null ? demangled : name);
        return prev != null ? prev : demangled;
    }
	
	

    /**
     * v3.2.8 优化: 批量预填 demangle 缓存 — 一次 JNI 调用处理所有 _Z 函数名.
     * 修复: 之前对每个 _Z 符号逐函数调 nativeDemangle (5 万符号 ≈ 5 万次 JNI 往返,
     * 仅往返开销 25-100 秒). 批量后往返降到 1 次, 精度完全不变 (结果相同, 只是缓存预热).
     */
    static void prefillDemangleCache(List<FunctionInfo> functions) {
        if (functions == null || functions.isEmpty()) return;
        try {
            Set<String> unique = new HashSet<>();
            for (FunctionInfo fn : functions) {
                if (fn == null || fn.name == null) continue;
                String name = fn.name;
                // 与 batchRestoreSignatures 相同条件: _Z 开头 + 长度 < 512 + 未缓存
                if (name.length() >= 2 && name.charAt(0) == '_' && name.charAt(1) == 'Z'
                        && name.length() < 512 && !sDemangleCache.containsKey(name)) {
                    unique.add(name);
                }
            }
            if (unique.isEmpty()) return;
            String[] arr = unique.toArray(new String[0]);
            String[] results = NativeBridge.demangleBatch(arr);
            if (results == null || results.length != arr.length) return;
            for (int i = 0; i < arr.length; i++) {
                String r = results[i];
                sDemangleCache.putIfAbsent(arr[i], r != null ? r : arr[i]);
            }
            Log.i(TAG, "prefillDemangleCache: " + arr.length + " names in 1 JNI call");
        } catch (Throwable t) {
            Log.w(TAG, "prefillDemangleCache failed: " + t.getMessage());
        }
    }

    /** 清除 demangle 缓存 (测试用) */
    public static void clearDemangleCache() {
        sDemangleCache.clear();
    }

    /**
     * v2.7.4: 批量还原函数签名。
     * 一次性将所有函数地址传给 native，只读一次 SO 文件。
     * 结果缓存在 FunctionInfo.restoredSignature 中。
     *
     * @param functions 函数列表
     * @param elf       ELF 文件（用于虚拟地址转文件偏移）
     * @param machine   机器类型
     * @return 还原成功的函数数量
     */
    public static int batchRestoreSignatures(List<FunctionInfo> functions, ElfFile elf, int machine) {
        if (functions == null || functions.isEmpty() || elf == null) return 0;
        if (!NativeBridge.isSigSupported()) return 0;

        // v3.2.8 优化: 先批量预热 demangle 缓存 (1 次 JNI 代替 N 次), 精度不变
        long tPrefill = System.currentTimeMillis();
        prefillDemangleCache(functions);
        Log.i(TAG, "batchRestore: prefill demangle cache in "
                + (System.currentTimeMillis() - tPrefill) + "ms");

        // v2.8.56: 优先用 native handle (避免读整个 SO 到 Java 堆)
        long handle = elf.nativeHandle;
        String path = sSoPath != null ? sSoPath : elf.filePath;

        long t0 = System.currentTimeMillis();
        android.util.Log.i(TAG, "batchRestore: start, " + functions.size() + " functions");

        // v2.8.56: 如果有 handle, 不需要读文件; 否则回退到旧方式
        byte[] soData = null;
        if (handle == 0 && path != null && !path.isEmpty()) {
            soData = readSoBytes(path);
            if (soData == null || soData.length == 0) {
                android.util.Log.w(TAG, "batchRestore: failed to read SO file");
                return 0;
            }
            android.util.Log.i(TAG, "batchRestore: SO file read, " + soData.length + " bytes, "
                    + (System.currentTimeMillis() - t0) + "ms");
        } else if (handle == 0) {
            android.util.Log.w(TAG, "batchRestore: no handle and no path");
            return 0;
        }

        // 2) 收集需要分析的函数（跳过已有签名的）
        List<Integer> needAnalyze = new ArrayList<>();
        for (int i = 0; i < functions.size(); i++) {
            FunctionInfo fn = functions.get(i);
            if (fn == null) continue;
            if (fn.restoredSignature != null) continue; // 已分析

            // C++ mangled 名 — demangle 直接得参数 (v3.2.6 加 cache)
            if (fn.name != null && fn.name.startsWith("_Z") && fn.name.length() < 512) {
                try {
                    String demangled = demangleCached(fn.name);
                    if (demangled != null && !demangled.equals(fn.name) && demangled.contains("(")) {
                        // demangle 结果含参数，但无返回类型
                        // 用 native 分析返回类型，参数用 demangle 的
                        fn.restoredSignature = demangled; // 先存 demangled，后面 native 批量补返回类型
                        // 仍加入 native 分析以获取返回类型
                    }
                } catch (Throwable ignored) {}
            }

            needAnalyze.add(i);
        }

        android.util.Log.i(TAG, "batchRestore: " + needAnalyze.size() + " functions need native analysis, "
                + (System.currentTimeMillis() - t0) + "ms");

        if (needAnalyze.isEmpty()) {
            android.util.Log.i(TAG, "batchRestore: all done (no native needed), "
                    + (System.currentTimeMillis() - t0) + "ms");
            return functions.size();
        }

        // 3) 批量构建 native 输入数组
        // v2.7.5: 取消函数数量限制，全量分析
        int n = needAnalyze.size();
        long[] addrs = new long[n];
        int[] sizes = new int[n];
        int[] thumbFlags = new int[n];
        String[] names = new String[n];

        boolean isArm = (machine == ElfConstants.EM_ARM);
        int valid = 0;
        for (int k = 0; k < n && k < needAnalyze.size(); k++) {
            int idx = needAnalyze.get(k);
            FunctionInfo fn = functions.get(idx);

            // 虚拟地址转文件偏移
            long fileOffset = virtualToFileOffset(fn.address, elf);
            if (fileOffset < 0) fileOffset = fn.address;
            // v2.8.56: handle 模式下不做 soData.length 检查 (native 会检查)
            if (fileOffset < 0) continue;
            if (soData != null && fileOffset >= soData.length) continue;

            addrs[valid] = fileOffset;
            sizes[valid] = (int) Math.min(fn.size > 0 ? fn.size : 256, 8192);
            thumbFlags[valid] = (isArm && fn.isThumb) ? 1 : 0;
            names[valid] = fn.name != null ? fn.name : "sub_" + Long.toHexString(fn.address);
            valid++;
        }

        android.util.Log.i(TAG, "batchRestore: " + valid + " valid functions for native, "
                + (System.currentTimeMillis() - t0) + "ms");

        if (valid == 0) {
            android.util.Log.w(TAG, "batchRestore: no valid functions");
            return 0;
        }

        // 4) 一次 native 调用分析所有函数
        try {
            // 如果 valid < n，截断数组
            if (valid < n) {
                long[] a2 = new long[valid];
                int[] s2 = new int[valid];
                int[] t2 = new int[valid];
                String[] nm2 = new String[valid];
                System.arraycopy(addrs, 0, a2, 0, valid);
                System.arraycopy(sizes, 0, s2, 0, valid);
                System.arraycopy(thumbFlags, 0, t2, 0, valid);
                System.arraycopy(names, 0, nm2, 0, valid);
                addrs = a2; sizes = s2; thumbFlags = t2; names = nm2;
            }

            android.util.Log.i(TAG, "batchRestore: calling native restoreSignatures for " + valid + " functions...");
            // v3.3.1: 优先结构化通道 — native 直接产出参数/返回类型等分析结果,
            //         Java 不再 parseNativeSig 重复解析, r2dec 直接消费结构化数据.
            // v2.8.56: 优先用 handle, 避免 byte[] 拷贝
            Object[][] structs = null;
            String[] sigs = null;
            if (handle != 0) {
                structs = NativeBridge.restoreSignaturesStructuredByHandle(handle, addrs, sizes, thumbFlags, machine, names);
                if (structs == null) {
                    // 旧 so / 结构化通道不可用: 降级字符串通道
                    sigs = NativeBridge.restoreSignaturesByHandle(handle, addrs, sizes, thumbFlags, machine, names);
                }
            } else {
                sigs = NativeBridge.restoreSignatures(soData, addrs, sizes, thumbFlags, machine, names);
            }
            int got = structs != null ? structs.length : (sigs != null ? sigs.length : 0);
            android.util.Log.i(TAG, "batchRestore: native returned " + got
                    + " results, " + (System.currentTimeMillis() - t0) + "ms");

            if (got == 0) {
                android.util.Log.w(TAG, "batchRestore: native returned empty");
                return 0;
            }

            // 5) 将结果写回 FunctionInfo
            int restored = 0;
            for (int k = 0; k < valid && k < got; k++) {
                int idx = needAnalyze.get(k);
                FunctionInfo fn = functions.get(idx);

                // ── v3.3.1: 结构化通道 — 直接落地 native 分析结果, 不解析字符串 ──
                if (structs != null) {
                    if (applyStructuredResult(fn, structs[k], machine)) restored++;
                    continue;
                }

                // ── 降级: 字符串通道 (旧 so / 无 handle) ──
                String sig = sigs[k];
                if (sig == null || sig.isEmpty()) {
                    fn.restoredSignature = "";
                    continue;
                }

                // 解析 native 签名
                NativeSigParts parts = parseNativeSig(sig);

                // C++ mangled: 用 demangle 的参数替换 native 的（更准确）
                if (fn.name != null && fn.name.startsWith("_Z") && fn.name.length() < 512) {
                    try {
                        String demangled = demangleCached(fn.name);
                        if (demangled != null && !demangled.equals(fn.name) && demangled.contains("(")) {
                            NativeSigParts dem = parseDemangledOnly(demangled);
                            // demangle 参数更准，但无返回类型
                            // v2.8.0: native 的返回类型通过交叉引用分析得到（不再靠函数名猜）
                            parts = new NativeSigParts(parts.ret, dem.name, dem.params);
                        }
                    } catch (Throwable ignored) {}
                }

                // 格式化签名
                List<String> paramRegs = new ArrayList<>();
                for (int j = 0; j < parts.params.size(); j++) {
                    paramRegs.add(abiParamReg(machine, j));
                }
                String finalSig = formatSigV2(parts.ret, parts.name, parts.params, paramRegs, null,
                        machine == ElfConstants.EM_AARCH64);
                fn.restoredSignature = finalSig;
                restored++;
            }

            android.util.Log.i(TAG, "batchRestore: COMPLETE, " + restored + " restored, "
                    + (System.currentTimeMillis() - t0) + "ms total");
            return restored;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "batchRestore failed", t);
            return 0;
        }
    }



    /* ══════════════ 公共数据模型 ══════════════ */

    public static class ParamInfo {
        public final String reg;        // 寄存器名 ("r0" / "x0" / "stack#0" / "stack#1" ...)
        public final int index;         // 顺序 (a1, a2, ...)
        public final String type;       // 推断类型
        public final String detail;     // 推断理由 (debug)
        /** v2.0.9: 置信度 ("high" | "medium" | "low") */
        public final String confidence;
        public ParamInfo(String reg, int index, String type, String detail, String confidence) {
            this.reg = reg;
            this.index = index;
            this.type = type;
            this.detail = detail;
            this.confidence = confidence;
        }
        public ParamInfo(String reg, int index, String type, String detail) {
            this(reg, index, type, detail, "medium");
        }
    }

    public static class Result {
        public final String signature;
        public final String returnType;
        public final List<String> paramTypes;
        public final List<String> paramRegs;
        public final List<ParamInfo> paramInfos;
        public final String addressHex;
        public final long size;
        public final String sectionName;
        public final String functionName;
        public final List<LocalVar> localVars;
        public final List<StringRef> stringRefs;
        public final boolean demangled;
        public final String originalName;
        public final String notes;
        public final int machine;
        /** v2.0.9: 参数个数下界 (callee 看到至少几个) */
        public final int minArgs;
        /** v2.0.9: 参数个数上界 (caller 看到最多几个) */
        public final int maxArgs;
        /** v2.0.9: 综合置信度 (high=上下界收敛到一点, medium=差 1-2, low=差 3+) */
        public final String overallConfidence;
        /** v2.0.9: 标志 (varargs_detected, hand_written_asm, sp_tracking_lost) */
        public final List<String> flags;
        /** v2.0.9: caller-side 详细 (每个 call site 传了几个) */
        public final List<String> callSiteDetails;
        /** v2.0.9: SP delta 追踪是否中断 (true = 后续分析不可信) */
        public final boolean spTrackingLost;

        Result(String signature, String returnType, List<String> paramTypes,
               List<String> paramRegs, List<ParamInfo> paramInfos,
               String addressHex, long size, String sectionName, String functionName,
               List<LocalVar> localVars, List<StringRef> stringRefs,
               boolean demangled, String originalName, String notes, int machine,
               int minArgs, int maxArgs, String overallConfidence,
               List<String> flags, List<String> callSiteDetails, boolean spTrackingLost) {
            this.signature = signature;
            this.returnType = returnType;
            this.paramTypes = paramTypes;
            this.paramRegs = paramRegs;
            this.paramInfos = paramInfos;
            this.addressHex = addressHex;
            this.size = size;
            this.sectionName = sectionName;
            this.functionName = functionName;
            this.localVars = localVars;
            this.stringRefs = stringRefs;
            this.demangled = demangled;
            this.originalName = originalName;
            this.notes = notes;
            this.machine = machine;
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.overallConfidence = overallConfidence;
            this.flags = flags;
            this.callSiteDetails = callSiteDetails;
            this.spTrackingLost = spTrackingLost;
        }
    }

    public static class LocalVar {
        public final int spOffset;       // sp 偏移
        public final int accessCount;   // 访问次数
        public final int size;          // 估计大小 (4/8)
        public LocalVar(int spOffset, int accessCount, int size) {
            this.spOffset = spOffset;
            this.accessCount = accessCount;
            this.size = size;
        }
    }

    public static class StringRef {
        public final long address;
        public final String preview;
        public StringRef(long address, String preview) {
            this.address = address;
            this.preview = preview;
        }
    }

    /** v2.0.9: caller-side 输入 (主调) */
    public static class CallerInfo {
        public final long callSiteAddress;
        public final int maxRegsSet;   // 这次 call site 最多设了几个寄存器参数
        public final boolean spPush;   // 是否在 sp 压了额外参数
        public final int spPushCount;  // 压了几个
        public CallerInfo(long callSiteAddress, int maxRegsSet, boolean spPush, int spPushCount) {
            this.callSiteAddress = callSiteAddress;
            this.maxRegsSet = maxRegsSet;
            this.spPush = spPush;
            this.spPushCount = spPushCount;
        }
    }

    /* ══════════════ 入口 ══════════════ */

    private FunctionSignatureAnalyzer() {}

    /** v2.7.5: 从已还原的签名快速构建 Result（不调 native） */
    public static Result createSimpleResult(String sig, String ret, List<String> params,
                                            FunctionInfo fn, int machine) {
        List<String> paramRegs = new ArrayList<>();
        List<ParamInfo> paramInfos = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            String reg = abiParamReg(machine, i);
            paramRegs.add(reg);
            paramInfos.add(new ParamInfo(reg, i + 1, params.get(i), "restored", "high"));
        }
        return new Result(sig, ret, params, paramRegs, paramInfos,
                String.format(Locale.US, "0x%x", fn.address), fn.size,
                fn.sectionName, fn.name, Collections.emptyList(), Collections.emptyList(),
                false, fn.name, "restored signature", machine,
                params.size(), params.size(), "high",
                Collections.emptyList(), Collections.emptyList(), false);
    }

    /**
     * 主入口: 单函数分析.
     */
    public static Result analyze(FunctionInfo fn, int machine) {
        return analyze(fn, machine, null, null);
    }

    /**
     * v2.4.1: 主入口 (带 caller-side 输入 + ElfFile, 用于把虚拟地址转成文件偏移后调用 native).
     */
    public static Result analyze(FunctionInfo fn, int machine, ElfFile elf) {
        return analyze(fn, machine, null, elf);
    }

    /**
     * v2.3.2: 主入口 (带 caller-side 输入).
     * <p>
     * 优先使用 native restoreSignatures 还原完整签名,
     * 有符号函数再用 native demangle 解码函数名/参数.
     * native 不可用时回退到 Java 层反汇编推导.
     *
     * @param fn          被分析函数
     * @param machine     ELF 架构 (40=ARM, 183=ARM64, 3=x86, 62=x86_64)
     * @param callerInfos caller-side 信息: 所有调用 fn 的 site 各自传了几个参数.
     *                    可为 null (跳过 caller-side, 只做 callee-side).
     * @param elf         可选, 用于把函数虚拟地址转换为文件偏移, 再传给 native.
     */
    public static Result analyze(FunctionInfo fn, int machine,
                                 @Nullable List<CallerInfo> callerInfos,
                                 @Nullable ElfFile elf) {
        if (fn == null) return null;
        String name = fn.name != null ? fn.name : "";

        // v2.4.2: 先检查是否是已知标准函数 (JNI_OnLoad 等), 使用预定义签名
        Result knownResult = checkKnownFunction(name, fn, machine);
        if (knownResult != null) return knownResult;

        // v2.4.1: 如有 ElfFile, 把虚拟地址转换为文件偏移, 避免 native restoreSignatures 越界崩溃
        long fileOffset = -1;
        if (elf != null) {
            fileOffset = virtualToFileOffset(fn.address, elf);
        }

        // v2.4.2: 只使用 native 层函数签名还原
        return analyzeNative(fn, machine, callerInfos, fileOffset);
    }

    /**
     * v2.4.2: 检查是否是已知标准函数, 使用预定义签名.
     * 避免 JNI_OnLoad 等标准函数被错误推断为返回 long.
     */
    private static Result checkKnownFunction(String name, FunctionInfo fn, int machine) {
        if (name == null || name.isEmpty()) return null;

        // JNI 标准函数
        if (name.equals("JNI_OnLoad")) {
            List<String> paramTypes = new ArrayList<>();
            paramTypes.add("JavaVM*");
            paramTypes.add("void*");
            List<String> paramRegs = new ArrayList<>();
            paramRegs.add("x0");
            paramRegs.add("x1");
            List<ParamInfo> paramInfos = new ArrayList<>();
            paramInfos.add(new ParamInfo("x0", 1, "JavaVM*", "JNI 标准参数", "high"));
            paramInfos.add(new ParamInfo("x1", 2, "void*", "JNI 标准参数", "high"));
            String sig = "jint JNI_OnLoad(JavaVM*, void*)";
            return new Result(sig, "jint", paramTypes, paramRegs, paramInfos,
                              String.format(Locale.US, "0x%x", fn.address), fn.size,
                              fn.sectionName, name, Collections.emptyList(), Collections.emptyList(),
                              false, name, "JNI 标准函数", machine, 2, 2, "high",
                              Collections.emptyList(), Collections.emptyList(), false);
        }
        if (name.equals("JNI_OnUnload")) {
            List<String> paramTypes = new ArrayList<>();
            paramTypes.add("JavaVM*");
            paramTypes.add("void*");
            List<String> paramRegs = new ArrayList<>();
            paramRegs.add("x0");
            paramRegs.add("x1");
            List<ParamInfo> paramInfos = new ArrayList<>();
            paramInfos.add(new ParamInfo("x0", 1, "JavaVM*", "JNI 标准参数", "high"));
            paramInfos.add(new ParamInfo("x1", 2, "void*", "JNI 标准参数", "high"));
            String sig = "void JNI_OnUnload(JavaVM*, void*)";
            return new Result(sig, "void", paramTypes, paramRegs, paramInfos,
                              String.format(Locale.US, "0x%x", fn.address), fn.size,
                              fn.sectionName, name, Collections.emptyList(), Collections.emptyList(),
                              false, name, "JNI 标准函数", machine, 2, 2, "high",
                              Collections.emptyList(), Collections.emptyList(), false);
        }


        return null;
    }

    /**
     * v2.4.1: 将 ELF 虚拟地址转换为文件偏移.
     * 优先用 section headers, 缺失时用 program headers.
     */
    private static long virtualToFileOffset(long vaddr, ElfFile elf) {
        if (elf == null) return -1;
        // 1) 尝试 section headers
        if (elf.sectionHeaders != null) {
            for (SectionHeader sh : elf.sectionHeaders) {
                if (sh == null) continue;
                if (vaddr >= sh.shAddr && vaddr < sh.shAddr + sh.shSize) {
                    return sh.shOffset + (vaddr - sh.shAddr);
                }
            }
        }
        // 2) fallback 到 program headers (PT_LOAD 可执行段)
        if (elf.programHeaders != null) {
            for (ProgramHeader ph : elf.programHeaders) {
                if (ph == null || ph.pType != ElfConstants.PT_LOAD) continue;
                if ((ph.pFlags & ElfConstants.PF_X) == 0) continue;
                if (vaddr >= ph.pVaddr && vaddr < ph.pVaddr + ph.pMemsz) {
                    return ph.pOffset + (vaddr - ph.pVaddr);
                }
            }
        }
        return -1;
    }

    /**
     * v2.4.1: 使用 native restoreSignatures 还原签名.
     *
     * @param fileOffset 函数代码在 so 文件中的偏移; 若 < 0 则尝试用 fn.address 作为偏移(不推荐).
     */
    private static Result analyzeNative(FunctionInfo fn, int machine,
                                        @Nullable List<CallerInfo> callerInfos,
                                        long fileOffset) {
        if (!NativeBridge.isSigSupported()) return null;
        String path = sSoPath;
        if (path == null || path.isEmpty()) return null;

        List<DisassembledInstruction> insns = fn.instructions;
        if (insns == null) insns = Collections.emptyList();

        byte[] soData = readSoBytes(path);
        if (soData == null || soData.length == 0) return null;

        // v2.4.1: 必须有合法文件偏移才调用 native, 否则跳过避免越界崩溃.
        long nativeOffset = fileOffset >= 0 ? fileOffset : fn.address;
        if (nativeOffset < 0 || nativeOffset >= soData.length) {
            return null;
        }

        String name = fn.name != null ? fn.name : "";
        boolean isThumb = (machine == ElfConstants.EM_ARM);
        int[] thumbFlags = new int[]{isThumb ? 1 : 0};
        int[] sizes = new int[]{(int) Math.min(fn.size, 8192)};
        long[] addrs = new long[]{nativeOffset};
        String[] names = new String[]{name};

        try {
            String[] sigs = NativeBridge.restoreSignatures(soData, addrs, sizes,
                    thumbFlags, machine, names);
            if (sigs == null || sigs.length == 0) return null;
            String nativeSig = sigs[0];
            if (nativeSig == null || nativeSig.isEmpty()) return null;

            // 解析 native 签名: "ret_type name(args)"
            NativeSigParts parts = parseNativeSig(nativeSig);

            // 有符号函数: 用 native demangle 解码函数名和参数
            if (name.startsWith("_Z")) {
                String demangled = NativeBridge.demangle(name);
                if (demangled != null && !demangled.equals(name)) {
                    NativeSigParts dem = parseDemangledOnly(demangled);
                    // demangle 不带返回类型, 保持 native 的返回类型
                    parts = new NativeSigParts(parts.ret, dem.name, dem.params);
                }
            }

            List<String> paramTypes = parts.params;
            List<String> paramRegs = new ArrayList<>();
            List<ParamInfo> paramInfos = new ArrayList<>();
            for (int i = 0; i < paramTypes.size(); i++) {
                String reg = abiParamReg(machine, i);
                paramRegs.add(reg);
                paramInfos.add(new ParamInfo(reg, i + 1, paramTypes.get(i),
                        "native restore", "high"));
            }

            String sig = formatSigV2(parts.ret, parts.name, paramTypes, paramRegs, null,
                    machine == ElfConstants.EM_AARCH64);
            // v3.3.1: 单函数路径也落地结构化字段, 供 r2dec 直接消费 (与批量路径对齐)
            fn.restoredRetType = parts.ret;
            fn.restoredParamTypes = paramTypes;
            fn.restoredParamNames = null;
            fn.restoredParamFloat = null;
            fn.restoredParamWide = null;
            fn.restoredParamStackOffsets = null;
            fn.restoredNoreturn = false;
            fn.restoredVariadic = false;
            fn.restoredIsStatic = false;
            List<LocalVar> vars = collectLocalVars(insns, machine);
            List<StringRef> refs = collectStringRefs(insns, fn);
            return new Result(sig, parts.ret, paramTypes, paramRegs, paramInfos,
                    String.format(Locale.US, "0x%x", fn.address), fn.size,
                    fn.sectionName, parts.name, vars, refs,
                    name.startsWith("_Z"), name,
                    "native restore: " + nativeSig, machine,
                    paramTypes.size(), paramTypes.size(), "high",
                    new ArrayList<>(), new ArrayList<>(), false);
        } catch (Throwable t) {
            Log.w(TAG, "native analyze failed: " + t.getMessage());
            return null;
        }
    }

    /** v2.3.2: 读取 SO 文件字节 (带大小限制). */
    private static byte[] readSoBytes(String path) {
        try {
            java.io.File f = new java.io.File(path);
            long len = f.length();
            if (len <= 0 || len > MAX_SO_BYTES) return null;
            byte[] data = new byte[(int) len];
            java.io.FileInputStream fis = null;
            try {
                fis = new java.io.FileInputStream(f);
                int off = 0;
                while (off < data.length) {
                    int n = fis.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return off == data.length ? data : null;
            } finally {
                if (fis != null) try { fis.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "readSoBytes failed: " + t.getMessage());
            return null;
        }
    }

    private static final class NativeSigParts {
        final String ret;
        final String name;
        final List<String> params;
        NativeSigParts(String ret, String name, List<String> params) {
            this.ret = ret;
            this.name = name;
            this.params = params;
        }
    }

    // ── v3.3.1: 结构化签名落地 — native 层分析结果直接写入 FunctionInfo, 供 r2dec 消费 ──
    // Object[9]: [0]retType [1]paramTypes [2]paramNames [3]paramFloat [4]paramWide
    //            [5]paramStackOffsets [6]noreturn [7]variadic [8]isStatic
    // 返回 true 表示成功写入 (restoredSignature 非空).
    private static boolean applyStructuredResult(FunctionInfo fn, Object[] row, int machine) {
        if (row == null || row.length < 9) {
            fn.restoredSignature = "";
            return false;
        }
        String ret = asString(row[0]);
        List<String> types = asStringList(row[1]);
        List<String> pnames = asStringList(row[2]);
        boolean[] floats = asBoolArray(row[3], types.size());
        boolean[] wides = asBoolArray(row[4], types.size());
        int[] stackOffs = asIntArray(row[5], types.size());
        boolean noreturn = asBool(row[6]);
        boolean variadic = asBool(row[7]);
        boolean isStatic = asBool(row[8]);
        if (ret == null || ret.isEmpty()) ret = "int";
        // 0 参数是合法签名 (如 Minecraft::getGameMode() 这类无参成员函数),
        // 不能判失败 — 否则 native 的返回类型/noreturn/isStatic 等全被丢弃,
        // 列表页与伪 C 页会退化成 mangled 名 / void / 节区名。
        // (v3.4.2 修复: 原先 types.isEmpty() 直接 return false)

        // C++ mangled: 用 demangle 的参数替换 native 的（更准确, 与字符串通道行为一致）
        String finalName = fn.name != null ? fn.name : "sub_" + Long.toHexString(fn.address);
        if (fn.name != null && fn.name.startsWith("_Z") && fn.name.length() < 512) {
            try {
                String demangled = demangleCached(fn.name);
                if (demangled != null && !demangled.equals(fn.name) && demangled.contains("(")) {
                    NativeSigParts dem = parseDemangledOnly(demangled);
                    finalName = dem.name;
                    if (dem.params.size() == types.size()) {
                        // 槽位对齐时才替换类型, 保证 restoredParamTypes 与
                        // float/wide/stackOffsets 下标一致 (r2dec 按下标消费)
                        types = dem.params;
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 格式化签名展示 (与字符串通道输出一致)
        List<String> paramRegs = new ArrayList<>(types.size());
        for (int j = 0; j < types.size(); j++) {
            paramRegs.add(abiParamReg(machine, j));
        }
        String finalSig = formatSigV2(ret, finalName, types, paramRegs, pnames,
                machine == ElfConstants.EM_AARCH64);

        // 落地结构化字段 (r2dec 直接消费, 不再 parseToSignature)
        fn.restoredRetType = ret;
        fn.restoredParamTypes = types;
        fn.restoredParamNames = pnames;
        fn.restoredParamFloat = floats;
        fn.restoredParamWide = wides;
        fn.restoredParamStackOffsets = stackOffs;
        fn.restoredNoreturn = noreturn;
        fn.restoredVariadic = variadic;
        fn.restoredIsStatic = isStatic;
        fn.restoredSignature = finalSig;
        return true;
    }

    // ── v3.3.1: 结构化数组解析辅助 (兼容 JNI 返回的 String[]/boolean[]/int[]) ──
    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static List<String> asStringList(Object o) {
        if (o instanceof String[]) {
            String[] arr = (String[]) o;
            List<String> list = new ArrayList<>(arr.length);
            for (String s : arr) {
                if (s != null) list.add(s);
            }
            return list;
        }
        return new ArrayList<>();
    }

    private static boolean[] asBoolArray(Object o, int n) {
        if (o instanceof boolean[]) return ((boolean[]) o).clone();
        if (o instanceof int[]) { // native 层可能以 int[] 传输
            int[] arr = (int[]) o;
            boolean[] b = new boolean[Math.min(arr.length, n)];
            for (int i = 0; i < b.length; i++) b[i] = arr[i] != 0;
            return b;
        }
        return null;
    }

    private static int[] asIntArray(Object o, int n) {
        if (o instanceof int[]) return ((int[]) o).clone();
        return null;
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Integer) return (Integer) o != 0;
        return false;
    }

    /** v2.3.2: 解析 native 签名字符串 "ret name(args)". */
    private static NativeSigParts parseNativeSig(String sig) {
        if (sig == null) sig = "";
        sig = sig.trim();

        int paren = sig.indexOf('(');
        String before = paren >= 0 ? sig.substring(0, paren).trim() : sig;
        String argsPart = paren >= 0 ? sig.substring(paren) : "()";

        // before: "ret_type name"
        String ret = "int";
        String name = before;
        int lastSpace = before.lastIndexOf(' ');
        if (lastSpace > 0) {
            ret = before.substring(0, lastSpace).trim();
            name = before.substring(lastSpace + 1).trim();
        }
        if (name.isEmpty()) name = "sub_?";

        List<String> params = parseParamList(argsPart);
        return new NativeSigParts(ret, name, params);
    }

    /**
     * v2.3.2: 解析 demangled 字符串 "name(args)" (不含返回类型).
     */
    private static NativeSigParts parseDemangledOnly(String demangled) {
        if (demangled == null) demangled = "";
        demangled = demangled.trim();

        int paren = demangled.indexOf('(');
        String name = paren >= 0 ? demangled.substring(0, paren).trim() : demangled;
        String argsPart = paren >= 0 ? demangled.substring(paren) : "()";
        if (name.isEmpty()) name = "sub_?";

        List<String> params = parseParamList(argsPart);
        return new NativeSigParts("", name, params);
    }

    /** v2.3.2: 取第 n 个 ABI 参数寄存器名. */
    private static String abiParamReg(int machine, int n) {
        if (machine == ElfConstants.EM_AARCH64) {
            return n < 8 ? "x" + n : "stack#" + (n - 8);
        } else if (machine == ElfConstants.EM_ARM) {
            return n < 4 ? "r" + n : "stack#" + (n - 4);
        } else if (machine == ElfConstants.EM_X86_64) {
            String[] regs = {"rdi", "rsi", "rdx", "rcx", "r8", "r9"};
            return n < regs.length ? regs[n] : "stack#" + (n - regs.length);
        } else {
            String[] regs = {"eax", "ebx", "ecx", "edx"};
            return n < regs.length ? regs[n] : "stack#" + n;
        }
    }

    /* ══════════════ Demangle 路径 ══════════════ */

    private static Result fromDemangled(String demangled, String originalName,
                                         FunctionInfo fn, int machine) {
        int paren = demangled.indexOf('(');
        String beforeArgs = paren >= 0 ? demangled.substring(0, paren) : demangled;
        String argsPart = paren >= 0 ? demangled.substring(paren) : "()";

        List<String> paramTypes = parseParamList(argsPart);
        String funcName = beforeArgs;
        String ret = "int";  // demangle 不带返回类型

        List<String> regs = new ArrayList<>();
        List<ParamInfo> infos = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            regs.add("a" + (i + 1));
            infos.add(new ParamInfo("a" + (i + 1), i + 1, paramTypes.get(i),
                    "demangled", "high"));
        }

        // v2.0.19 改进: 不再硬截 4 参数, 保留全部 (跟 ARM 路径一致)
        // v2.0.19 改进: 用真实参数类型显示 (之前 v2.0.14 统一"void* arg"丢信息)
        String sig = formatSigV2(ret, funcName, paramTypes, regs, null, false);
        List<LocalVar> vars = collectLocalVars(fn.instructions, machine);
        List<StringRef> refs = collectStringRefs(fn.instructions, fn);
        return new Result(sig, ret, paramTypes, regs, infos,
                String.format(Locale.US, "0x%x", fn.address), fn.size,
                fn.sectionName, funcName, vars, refs,
                true, originalName, "demangled: " + demangled, machine,
                paramTypes.size(), paramTypes.size(), "high",
                new ArrayList<>(), new ArrayList<>(), false);
    }

    private static List<String> parseParamList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        s = s.trim();
        if (s.startsWith("(")) s = s.substring(1);
        if (s.endsWith(")")) s = s.substring(0, s.length() - 1);
        s = s.trim();
        if (s.isEmpty() || s.equals("void")) return out;
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                String p = s.substring(start, i).trim();
                if (!p.isEmpty()) out.add(p);
                start = i + 1;
            }
        }
        String p = s.substring(start).trim();
        if (!p.isEmpty()) out.add(p);
        return out;
    }

    /* ══════════════ 反汇编推导: 双向夹击 ══════════════ */

    private static Result inferFromCallingConv(String name,
                                               List<DisassembledInstruction> insns,
                                               int machine,
                                               FunctionInfo fn,
                                               @Nullable List<CallerInfo> callerInfos) {
        boolean isAarch64 = (machine == 183);
        boolean isX64 = (machine == 62);
        boolean isX86 = (machine == 3);

        if (isX86 || isX64) {
            return inferX86(name, insns, isX64, fn, machine, callerInfos);
        }

        // ── Step 1: SP/FPR 偏移追踪 (v2.0.9 新增) ──
        SpTracker spTrack = trackSp(insns, isAarch64);
        int spDeltaAtEntry = spTrack.deltaAtEntry;
        int spDeltaMax = spTrack.deltaMax;
        int spDeltaMin = spTrack.deltaMin;
        long spLostAt = spTrack.lostAtAddr;
        boolean spLost = (spLostAt != 0);

        // ── Step 2: callee-side — first def/use ──
        String[] intRegs = isAarch64
                ? new String[]{"x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7"}
                : new String[]{"r0", "r1", "r2", "r3"};
        String[] fpRegs = isAarch64
                ? new String[]{"d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7"}
                : new String[]{"s0", "s1", "s2", "s3", "d0", "d1", "d2", "d3"};

        // v2.0.9: 用 word boundary 正确检测 r10/r11/r12 是否被引用
        Map<String, Integer> firstDef = new HashMap<>();
        Map<String, Integer> firstUse = new HashMap<>();
        Map<String, UsageType> usage = new HashMap<>();
        for (String r : intRegs) usage.put(r, UsageType.NONE);
        for (String r : fpRegs) usage.put(r, UsageType.NONE);
        Map<Integer, Integer> stackAccess = new LinkedHashMap<>();

        for (int i = 0; i < insns.size(); i++) {
            DisassembledInstruction ins = insns.get(i);
            String mn = ins.mnemonic == null ? "" : ins.mnemonic.toLowerCase(Locale.ROOT);
            String op = ins.opStr == null ? "" : ins.opStr;

            // int reg
            for (String r : intRegs) {
                if (isWriteTo(op, r)) {
                    if (!firstDef.containsKey(r)) firstDef.put(r, i);
                }
                if (isReadOf(op, r) && !isWriteTo(op, r)) {
                    if (!firstUse.containsKey(r)) firstUse.put(r, i);
                }
                if (isReadOf(op, r) || isWriteTo(op, r)) {
                    usage.put(r, classifyUsage(mn, op, r, usage.get(r)));
                }
            }
            // fp reg
            for (String r : fpRegs) {
                if (isWriteTo(op, r)) {
                    if (!firstDef.containsKey(r)) firstDef.put(r, i);
                }
                if (isReadOf(op, r) && !isWriteTo(op, r)) {
                    if (!firstUse.containsKey(r)) firstUse.put(r, i);
                }
            }
            // stack (sp) access — 用 spTrack.correctOffset 来正确分类 local vs arg
            if (mn.startsWith("ldr") || mn.startsWith("str")) {
                Pattern p = Pattern.compile("\\[\\s*sp\\s*,\\s*#\\s*(-?\\d+)\\s*\\]");
                Matcher m = p.matcher(op);
                if (m.find()) {
                    int off = Integer.parseInt(m.group(1));
                    // 修正: 加上 spDeltaMax (入口 SP 已经被 sub 减少)
                    int correctedOff = off - spDeltaMax;
                    stackAccess.merge(correctedOff, 1, Integer::sum);
                }
            }
        }

        // ── Step 3: 分类参数 (callee-side min bound) ──
        List<String> paramRegs = new ArrayList<>();
        List<ParamInfo> paramInfos = new ArrayList<>();
        int minArgs = 0;

        for (String r : intRegs) {
            Integer fdef = firstDef.get(r);
            Integer fuse = firstUse.get(r);
            if (fuse == null) continue;  // 从未被读
            boolean isParam = (fdef == null) || (fdef > fuse);
            if (isParam) {
                paramRegs.add(r);
                String type = inferTypeForParam(usage.get(r), r, isAarch64, false);
                String detail = String.format(Locale.US, "first def=%s, first use=#%d",
                        fdef != null ? "#" + fdef : "none", fuse);
                paramInfos.add(new ParamInfo(r, paramRegs.size(), type, detail, "high"));
                minArgs = Math.max(minArgs, paramRegs.size());
            }
        }
        for (String r : fpRegs) {
            Integer fdef = firstDef.get(r);
            Integer fuse = firstUse.get(r);
            if (fuse == null) continue;
            boolean isParam = (fdef == null) || (fdef > fuse);
            if (isParam) {
                paramRegs.add(r);
                paramInfos.add(new ParamInfo(r, paramRegs.size(), "double",
                        String.format(Locale.US, "FP first def=%s, first use=#%d",
                                fdef != null ? "#" + fdef : "none", fuse),
                        "high"));
                minArgs = Math.max(minArgs, paramRegs.size());
            }
        }

        // v2.0.19 改进: callee-side 宽松回退 — 当标准"first use before first def"检测
        //   一个参数都没认出来 (编译器在 prologue 提前把 x0-x7 写到栈, 导致 fdef < fuse),
        //   但 caller 又没有 (没法反查), 不能直接报 0 — 用户看到"没有参数"会很迷惑.
        //   退而求其次: 看函数体里"被读但从未被写"的寄存器 (例如 mov x0, [sp, #N]; str ... ;
        //   后 ldr x0, [sp, #N]; ret → x0 实际是 param, 但被写又被读, 标准算法不认).
        //   规则: 第一次出现"读"的指令, 它的 dest 寄存器如果在该指令之前从未被写过, 算 param.
        //   即便编译器在更靠前的 prologue 写过, 但只要这段 ldr/ldr-stacked 是函数逻辑主体
        //   加载回 param 值的"标志性动作", 仍可识别.
        if (minArgs == 0 && (callerInfos == null || callerInfos.isEmpty())
                && insns.size() >= 4) {
            for (int i = 0; i < insns.size() && paramRegs.size() < 8; i++) {
                DisassembledInstruction ins = insns.get(i);
                String mn = ins.mnemonic == null ? "" : ins.mnemonic.toLowerCase(Locale.ROOT);
                String op = ins.opStr == null ? "" : ins.opStr;
                // 跳过 prologue 前 4 条
                if (i < 4) continue;
                String opl = op.toLowerCase(Locale.ROOT);
                // ldr x0, [sp, #N] / ldr w0, [...] → 把 x0/w0 读回 (典型从栈恢复)
                if ((mn.startsWith("ldr") || mn.startsWith("ldur") || mn.startsWith("ldp"))
                        && op.contains("[")) {
                    String dest = op.split(",")[0].trim().toLowerCase(Locale.ROOT);
                    for (String r : intRegs) {
                        if (dest.equals(r) && !paramRegs.contains(r)) {
                            paramRegs.add(r);
                            String type = inferTypeForParam(usage.get(r), r, isAarch64, false);
                            paramInfos.add(new ParamInfo(r, paramRegs.size(), type,
                                    "lenient fallback: ldr 后是 ret 前的恢复动作",
                                    "low"));
                            minArgs = Math.max(minArgs, paramRegs.size());
                            break;
                        }
                    }
                }
                // add x0, x1, x2 / mov x0, x1 这种把 x0 用作 dest 的, 若 x0 当前不在
                // paramRegs 且之前未被写, 也算 param
                if (mn.equals("mov") || mn.equals("add") || mn.equals("sub") || mn.equals("eor")
                        || mn.equals("orr") || mn.equals("and")) {
                    String dest = op.split(",")[0].trim().toLowerCase(Locale.ROOT);
                    for (String r : intRegs) {
                        if (dest.equals(r) && !paramRegs.contains(r)) {
                            // 只在 dest 寄存器之前的写入索引比当前 i 大 (即"未在之前写过")
                            Integer fdefIdx = firstDef.get(r);
                            if (fdefIdx == null || fdefIdx > i) {
                                paramRegs.add(r);
                                String type = inferTypeForParam(usage.get(r), r, isAarch64, false);
                                paramInfos.add(new ParamInfo(r, paramRegs.size(), type,
                                        "lenient fallback: 函数体中" + mn + " 使用 ret 寄存器",
                                        "low"));
                                minArgs = Math.max(minArgs, paramRegs.size());
                                break;
                            }
                        }
                    }
                }
            }
        }
        // 栈参数: 用 spTrack 正确分类 (delta 之后是 local, 之前是 caller 压入的 arg5+)
        List<Integer> stackOffsets = new ArrayList<>(stackAccess.keySet());
        Collections.sort(stackOffsets);
        for (int off : stackOffsets) {
            // local var (off < 0): 跳过
            if (off < 0) continue;
            // arg5+ (off > 0 且 off 在 caller 压入的范围内, 简化: 任何 sp+ 正偏移访问)
            // caller 压入的栈参数是: caller 视角的 [sp, #(N*4)] (N>=4)
            // 切换到 callee 视角: 进入 callee 时 SP 是 callee 入口 sp, caller 压入的在
            // [sp_entry - N*4, sp_entry), 即校正后 off < 0.
            // 校正后 off > 0 = callee 自己 push 的保存区 (r11/lr 之类), 不算 arg.
            // 但栈参数没出现在校正后 off > 0 里 — 那 caller-side 负责找.
        }
        // v2.0.9: stack arg 主要由 caller-side 决定, callee 端不直接数 (避免误报).
        // 但如果 callee 内部对校正后 [sp, #+N] 的高偏移 (>= 8) 做了 push/pop, 提示 varargs.

        // ── Step 4: caller-side — max bound ──
        int maxArgs = minArgs;  // 默认无 caller info, 区间退化为 min=min
        List<String> callSiteDetails = new ArrayList<>();
        if (callerInfos != null && !callerInfos.isEmpty()) {
            int observed = 0;
            for (CallerInfo ci : callerInfos) {
                if (ci == null) continue;
                observed = Math.max(observed, ci.maxRegsSet);
                String line = String.format(Locale.US,
                        "  0x%x: regs=%d  sp_push=%s(%d)",
                        ci.callSiteAddress, ci.maxRegsSet,
                        ci.spPush ? "yes" : "no", ci.spPushCount);
                callSiteDetails.add(line);
            }
            maxArgs = observed;
            if (maxArgs < minArgs) maxArgs = minArgs;  // 防御
        }

        // ── Step 5: 综合 paramInfos 的置信度 ──
        // 如果上下界收敛 (max-min <= 1), 整体置信度高; 否则 medium; 差 3+ → low
        int spread = maxArgs - minArgs;
        String overallConfidence;
        if (callerInfos == null) {
            overallConfidence = "callee-only (no caller info)";
        } else if (spread == 0) {
            overallConfidence = "high";
        } else if (spread <= 2) {
            overallConfidence = "medium";
        } else {
            overallConfidence = "low";
        }

        // flags
        List<String> flags = new ArrayList<>();
        if (spLost) {
            flags.add("sp_tracking_lost_at=0x" + Long.toHexString(spLostAt));
        }
        // varargs 检测: callee 在 SP+ 高偏移做了 push/pop 多个寄存器, 且 caller 传 sp_push
        if (callerInfos != null) {
            for (CallerInfo ci : callerInfos) {
                if (ci != null && ci.spPush && ci.spPushCount >= 2) {
                    flags.add("varargs_pattern_detected");
                    break;
                }
            }
        }
        // hand_written_asm_warn: 没有识别到标准的 push/mov/sub 等序言, 或者 spDelta 异常
        if (spDeltaAtEntry == 0 && insns.size() > 8) {
            // 函数体较长但没有 SP 调整, 可能是手写汇编或无栈帧
            // 不一定算手写, 只是 warn
            // flags.add("no_standard_prologue");
        }

        // 用 maxArgs 而不是 minArgs 来生成 paramTypes (因为 min 会有未用参数)
        // 但是 paramRegs 列表里只列出了 callee 实际用的, 即 minArgs 个
        // 如果 maxArgs > minArgs, 还要补 "未用参数" 占位项
        List<String> paramTypes = new ArrayList<>();
        for (ParamInfo pi : paramInfos) paramTypes.add(pi.type);
        while (paramTypes.size() < maxArgs) {
            // 占位: 补 "(unused?)" 类型, 提示用户这是 caller 传了但 callee 没读
            String regName = "a" + (paramTypes.size() + 1);
            paramTypes.add("?");
            paramInfos.add(new ParamInfo(regName, paramTypes.size(), "?",
                    "caller 传入, callee 未观察到使用 (max-min gap)", "low"));
        }

        // v2.0.19 改进: 不再硬截 4 参数 (那是 v2.0.14 的粗暴减半, 把 6/7 个参数的函数
        //   误显示为 4 个, 丢失信息). 改为按"已确认使用"的 paramInfos 数量优先,
        //   超出真实使用时再补 "?" 占位.
        //   旧逻辑: int effectiveMax = Math.min(maxArgs, 4);
        //   新逻辑: 保留全部 (paramTypes.size() 已经是 maxArgs, 不会更大).

        String ret = inferReturnType(insns, isAarch64, paramRegs);
        // v2.0.19 改进: 用真实类型显示参数 (v2.0.14 的 "void* arg" 统一泛化丢信息),
        //   形如 "ret name(int x0, void* x1, ...)" 让用户看到每个参数真实类型.
        String sig = formatSigV2(ret, name != null ? name : "sub_?", paramTypes, paramRegs, null, isAarch64);

        // 报告 [min, max] 区间到 notes
        // v2.0.10: 写得更直观, 让用户清楚看到分析了什么 + 为什么是 [min, max]
        StringBuilder notesSb = new StringBuilder();
        notesSb.append("callee 视角: 至少 ").append(minArgs).append(" 个参数 (基于寄存器读位置反推)\n");
        if (callerInfos == null || callerInfos.isEmpty()) {
            notesSb.append("caller 视角: 无数据 (函数未在反汇编中出现 bl site)\n");
            notesSb.append("  → 区间 [").append(minArgs).append(", ").append(maxArgs)
                    .append("], 只能保证下限, 上限不确定\n");
        } else {
            notesSb.append("caller 视角: 最多 ").append(maxArgs).append(" 个参数 (扫了 ")
                    .append(callerInfos.size()).append(" 个 bl site)\n");
            notesSb.append("  → 区间 [").append(minArgs).append(", ").append(maxArgs)
                    .append("], spread=").append(spread)
                    .append(", 置信度=").append(overallConfidence).append("\n");
        }
        notesSb.append("说明: 编译器可能优化掉未使用的参数, callee 看不到不代表没传.");
        if (spLost) {
            notesSb.append(" [SP 追踪中断 @ 0x").append(Long.toHexString(spLostAt)).append(", 栈参数分析失效]");
        }
        if (!flags.isEmpty()) {
            notesSb.append("\nflags: ").append(flags);
        }
        String notes = notesSb.toString();

        List<LocalVar> vars = collectLocalVars(insns, machine);
        List<StringRef> refs = collectStringRefs(insns, fn);
        return new Result(sig, ret, paramTypes, paramRegs, paramInfos,
                String.format(Locale.US, "0x%x", fn.address), fn.size,
                fn.sectionName, name, vars, refs,
                false, name, notes, machine,
                minArgs, maxArgs, overallConfidence, flags, callSiteDetails, spLost);
    }

    /* ══════════════ SP 偏移追踪器 (v2.0.9 新增) ══════════════ */

    static class SpTracker {
        /** 入口 SP delta: 进入函数后 vs 进入前, 负值 = SP 减小 (分配栈) */
        int deltaAtEntry = 0;
        /** SP delta 最小值 (栈最深) */
        int deltaMin = 0;
        /** SP delta 最大值 */
        int deltaMax = 0;
        /** SP 追踪丢失地址 (遇到未知指令), 0 = 未丢失 */
        long lostAtAddr = 0;
        /** 函数体入口是否做了标准的 push r11,lr / stp x29,x30 之类的保存 */
        boolean hasStandardPrologue = false;
    }

    /**
     * 跟踪函数体内每条指令的 SP delta. 简化版: 只追常见指令, 遇到 unknown 时标记 lostAtAddr.
     * 不做全仿真 — 只为区分 "sp+ 局部变量" 和 "sp+ caller 压入的 arg5+".
     */
    private static SpTracker trackSp(List<DisassembledInstruction> insns, boolean isAarch64) {
        SpTracker t = new SpTracker();
        if (insns == null || insns.isEmpty()) return t;
        int cur = 0;
        boolean inPrologue = true;
        for (int i = 0; i < insns.size(); i++) {
            DisassembledInstruction ins = insns.get(i);
            String mn = ins.mnemonic == null ? "" : ins.mnemonic.toLowerCase(Locale.ROOT);
            String op = ins.opStr == null ? "" : ins.opStr;

            if (isAarch64) {
                // aarch64: stp x29, x30, [sp, #-N]!  → SP -= N
                //         sub sp, sp, #N                 → SP -= N
                //         add sp, sp, #N                 → SP += N
                //         ldp x29, x30, [sp], #N          → SP += N
                if (mn.equals("stp") && op.contains("sp,") && op.contains("sp ]")) {
                    Matcher m = Pattern.compile("\\[\\s*sp\\s*,\\s*#-?(\\d+)\\s*\\]!?")
                            .matcher(op);
                    if (m.find()) {
                        int n = Integer.parseInt(m.group(1));
                        cur -= n;
                        if (i < 4) t.hasStandardPrologue = true;
                    }
                } else if (mn.equals("sub") && op.startsWith("sp,")) {
                    Matcher m = Pattern.compile("sp,\\s*sp,\\s*#(\\d+)").matcher(op);
                    if (m.find()) cur -= Integer.parseInt(m.group(1));
                } else if (mn.equals("add") && op.startsWith("sp,")) {
                    Matcher m = Pattern.compile("sp,\\s*sp,\\s*#(\\d+)").matcher(op);
                    if (m.find()) cur += Integer.parseInt(m.group(1));
                } else if (mn.equals("ldp") && op.contains("[sp") && op.contains("sp ],")) {
                    Matcher m = Pattern.compile("#(\\d+)").matcher(op);
                    if (m.find()) cur += Integer.parseInt(m.group(1));
                }
            } else {
                // arm32: push {regs} → SP -= 4*count
                //        pop {regs}  → SP += 4*count
                //        sub sp, sp, #N
                //        add sp, sp, #N
                if (mn.equals("push") && op.startsWith("{")) {
                    int cnt = countRegs(op);
                    cur -= cnt * 4;
                    if (i < 4) t.hasStandardPrologue = true;
                } else if (mn.equals("pop") && op.startsWith("{")) {
                    int cnt = countRegs(op);
                    cur += cnt * 4;
                } else if (mn.equals("sub") && op.startsWith("sp,")) {
                    Matcher m = Pattern.compile("sp,\\s*sp,\\s*#(\\d+)").matcher(op);
                    if (m.find()) cur -= Integer.parseInt(m.group(1));
                } else if (mn.equals("add") && op.startsWith("sp,")) {
                    Matcher m = Pattern.compile("sp,\\s*sp,\\s*#(\\d+)").matcher(op);
                    if (m.find()) cur += Integer.parseInt(m.group(1));
                }
            }

            // 计算 delta 范围
            if (cur < t.deltaMin) t.deltaMin = cur;
            if (cur > t.deltaMax) t.deltaMax = cur;

            // 退出 prologue: 第一次有 bl/pop 之外的"用户代码"就退
            if (inPrologue && i >= 3
                    && !mn.equals("push") && !mn.equals("pop")
                    && !mn.equals("stp") && !mn.equals("ldp")
                    && !mn.equals("sub") && !mn.equals("add")
                    && !(mn.equals("mov") && op.contains("r11, sp") || op.contains("x29, sp"))) {
                t.deltaAtEntry = cur;
                inPrologue = false;
            }
        }
        if (inPrologue) t.deltaAtEntry = cur;
        return t;
    }

    private static int countRegs(String op) {
        // push {r0, r1, r2}
        int s = op.indexOf('{');
        int e = op.indexOf('}');
        if (s < 0 || e < 0) return 0;
        String inside = op.substring(s + 1, e);
        return inside.split(",").length;
    }

    /* ══════════════ x86 / x86_64 路径 (v2.0.9 caller-side 加成) ══════════════ */

    private static Result inferX86(String name, List<DisassembledInstruction> insns,
                                    boolean isX64, FunctionInfo fn, int machine,
                                    @Nullable List<CallerInfo> callerInfos) {
        String[] regs = isX64
                ? new String[]{"rdi", "rsi", "rdx", "rcx", "r8", "r9"}
                : new String[]{"eax", "ebx", "ecx", "edx"};
        List<String> paramRegs = new ArrayList<>();
        List<ParamInfo> infos = new ArrayList<>();
        int minArgs = 0;
        for (String r : regs) {
            boolean used = false;
            int firstUse = -1;
            for (int i = 0; i < insns.size(); i++) {
                String op = insns.get(i).opStr == null ? "" : insns.get(i).opStr;
                if (isReadOf(op, r)) { used = true; if (firstUse < 0) firstUse = i; }
            }
            if (used) {
                paramRegs.add(r);
                String type = isX64 ? "long" : "int";
                infos.add(new ParamInfo(r, paramRegs.size(), type, "first use=#" + firstUse, "high"));
                minArgs = Math.max(minArgs, paramRegs.size());
            }
        }
        int maxArgs = minArgs;
        List<String> callSiteDetails = new ArrayList<>();
        if (callerInfos != null) {
            for (CallerInfo ci : callerInfos) {
                if (ci == null) continue;
                maxArgs = Math.max(maxArgs, ci.maxRegsSet);
                callSiteDetails.add(String.format(Locale.US,
                        "  0x%x: regs=%d  sp_push=%s(%d)",
                        ci.callSiteAddress, ci.maxRegsSet,
                        ci.spPush ? "yes" : "no", ci.spPushCount));
            }
        }
        String ret = isX64 ? "long" : "int";
        List<String> types = new ArrayList<>();
        for (ParamInfo pi : infos) types.add(pi.type);
        while (types.size() < maxArgs) {
            String regName = "a" + (types.size() + 1);
            types.add("?");
            infos.add(new ParamInfo(regName, types.size(), "?", "caller 传入, callee 未用", "low"));
        }
        int spread = maxArgs - minArgs;
        String conf = callerInfos == null ? "callee-only"
                : (spread == 0 ? "high" : spread <= 2 ? "medium" : "low");
        // v2.0.19 改进: 不再硬截 4 参数 (跟 ARM 路径一致, 保留全部)
        String sig = formatSigV2(ret, name, types, paramRegs, null, false);
        List<LocalVar> vars = collectLocalVars(insns, machine);
        List<StringRef> refs = collectStringRefs(insns, fn);
        return new Result(sig, ret, types, paramRegs, infos,
                String.format(Locale.US, "0x%x", fn.address), fn.size,
                fn.sectionName, name, vars, refs,
                false, name,
                String.format(Locale.US,
                        "callee: min=%d  caller: max=%d  confidence=%s",
                        minArgs, maxArgs, conf),
                machine, minArgs, maxArgs, conf,
                new ArrayList<>(), callSiteDetails, false);
    }

    /* ══════════════ 杂项 ══════════════ */

    private static String formatSig(String ret, String name, List<String> paramTypes) {
        StringBuilder sig = new StringBuilder();
        sig.append(ret).append(' ').append(name);
        sig.append('(');
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(paramTypes.get(i));
        }
        if (paramTypes.isEmpty()) sig.append("void");
        sig.append(')');
        return sig.toString();
    }

    /**
     * v2.0.19: 新格式签名 — 用真实参数类型 (而不是 v2.0.14 的统一 "void* arg"),
     *   形如 {@code <ret> <name>(int x0, void* x1, ...)}.
     * <p>
     * 输出示例:
     * <ul>
     *   <li>无参 → {@code int foo(void)}</li>
     *   <li>1 参 → {@code int foo(int x0)}</li>
     *   <li>3 参 → {@code void* bar(int x0, void* x1, double d0)}</li>
     *   <li>类型 "?" → {@code ?} (caller 传了, callee 没确认使用)</li>
     * </ul>
     */
    private static String formatSigV2(String ret, String name,
                                      List<String> paramTypes, List<String> paramRegs,
                                      List<String> paramNames, boolean isAarch64) {
        StringBuilder sig = new StringBuilder();
        sig.append(ret != null && !ret.isEmpty() ? ret : "int").append(' ').append(name);
        sig.append('(');
        if (paramTypes == null || paramTypes.isEmpty()) {
            sig.append("void");
        } else {
            for (int i = 0; i < paramTypes.size(); i++) {
                if (i > 0) sig.append(", ");
                String t = paramTypes.get(i);
                // 结构化通道参数名优先 (native known 签名拆分, 如 JNI_OnLoad 的 vm/reserved)
                String pn = (paramNames != null && i < paramNames.size()) ? paramNames.get(i) : null;
                if (pn != null && !pn.isEmpty()) {
                    sig.append((t == null || t.isEmpty() || t.equals("?")) ? "?" : t)
                       .append(' ').append(pn);
                    continue;
                }
                if (t == null || t.isEmpty() || t.equals("?")) {
                    sig.append("?");
                } else {
                    sig.append(t);
                }
                // 附 ABI 寄存器名 (debug 用)
                // v3.2.9: native 端已带 r2 风格参数名 (如 "JNIEnv* env"、"long arg3")
                //         时不再附加 abiParamReg 的 x0/x1 等
                String regName = null;
                if (!isR2ParamName(t)
                        && paramRegs != null && i < paramRegs.size()) {
                    regName = paramRegs.get(i);
                } else if (!isR2ParamName(t) && isAarch64 && i < 8) {
                    regName = "x" + i;
                } else if (!isR2ParamName(t) && !isAarch64 && i < 4) {
                    regName = "r" + i;
                }
                if (regName != null) sig.append(' ').append(regName);
            }
        }
        sig.append(')');
        return sig.toString();
    }

    /**
     * v3.2.9: 判断参数类型串是否已带 r2 风格参数名.
     *
     * native 层 (ndk_signature.cpp) 对 JNI 函数注入的参数格式为 "类型 名字",
     * 名字可能是: argN (寄存器)、arg_Xh (栈)、env/thiz/vm/reserved (JNI)。
     * 已带名字时 Java 层不再附加 abiParamReg 的 x0/x1/stack#N。
     *
     * 注意: "long long" 这类类型本身含空格, 但末尾 token 不是 r2 参数名,
     * 因此不会被误判。
     */
    private static boolean isR2ParamName(String t) {
        if (t == null || t.isEmpty()) return false;
        int sp = t.lastIndexOf(' ');
        if (sp < 0 || sp == t.length() - 1) return false;
        String tok = t.substring(sp + 1).trim();
        if (tok.isEmpty()) return false;
        // argN (N >= 1)
        if (tok.startsWith("arg") && tok.length() > 3) {
            String num = tok.substring(3);
            boolean allDigit = !num.isEmpty();
            for (int i = 0; i < num.length(); i++) {
                if (!Character.isDigit(num.charAt(i))) { allDigit = false; break; }
            }
            if (allDigit) return true;
        }
        // arg_Xh (X 为十六进制偏移)
        if (tok.startsWith("arg_") && tok.endsWith("h") && tok.length() > 5) {
            String hex = tok.substring(4, tok.length() - 1);
            boolean allHex = !hex.isEmpty();
            for (int i = 0; i < hex.length(); i++) {
                char c = hex.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    allHex = false; break;
                }
            }
            if (allHex) return true;
        }
        // JNI 语义名
        return tok.equals("env") || tok.equals("thiz")
                || tok.equals("vm") || tok.equals("reserved");
    }

    private enum UsageType {
        NONE, POINTER, INTEGER, FLOAT, CHAR_PTR
    }

    private static UsageType classifyUsage(String mn, String op, String r, UsageType prev) {
        if (mn.isEmpty() || op.isEmpty()) return prev;
        String opl = op.toLowerCase(Locale.ROOT);
        if (mn.startsWith("v") && (mn.contains("mov") || mn.contains("ldr") || mn.contains("str")
                || mn.contains("add") || mn.contains("mul") || mn.contains("cvt"))) {
            return UsageType.FLOAT;
        }
        if (opl.contains("s") && mn.equals("vmov") && !opl.contains("x")) {
            return UsageType.FLOAT;
        }
        if (mn.startsWith("ldrb") || mn.startsWith("strb") || mn.startsWith("ldrsb")) {
            return UsageType.CHAR_PTR;
        }
        // v2.0.9: 修复: 之前用 basePat 而 r 没转义, 改成词边界 regex
        Pattern basePat = Pattern.compile(
                "(ldr|ldur|ldp|ldrb|ldrh|str|strb|strh|stur|stp)\\s+" + Pattern.quote(r)
                        + "\\s*,\\s*\\[\\s*" + Pattern.quote(r));
        if (basePat.matcher(mn + " " + op).find()) {
            return UsageType.POINTER;
        }
        if ((mn.equals("ldr") || mn.equals("ldur")) && opl.startsWith(r + ", [")
                && opl.contains(",")) {
            return UsageType.POINTER;
        }
        // v2.0.19 改进: adr/adrp 加载 PC 相对地址 → POINTER
        if (mn.equals("adr") || mn.equals("adrp")) return UsageType.POINTER;
        // v2.0.19 改进: cbz/cbnz/cmp/tst 拿寄存器当 integer 用 → INTEGER
        if (mn.equals("cbz") || mn.equals("cbnz") || mn.equals("cmp") || mn.equals("tst")) {
            if (opl.contains(r)) return UsageType.INTEGER;
        }
        // v2.0.19 改进: 算术指令中寄存器被用作 src → INTEGER
        if (mn.equals("add") || mn.equals("sub") || mn.equals("mul") || mn.equals("and")
                || mn.equals("orr") || mn.equals("eor") || mn.equals("lsl") || mn.equals("lsr")
                || mn.equals("asr") || mn.equals("sdiv") || mn.equals("udiv")
                || mn.startsWith("smaddl") || mn.startsWith("umaddl")
                || mn.startsWith("smull") || mn.startsWith("umull")) {
            if (opl.contains(r)) {
                return UsageType.INTEGER;
            }
        }
        return prev;
    }

    private static String inferType(UsageType t, boolean isAarch64, boolean isFp) {
        if (isFp) return "double";
        if (t == null) return isAarch64 ? "long" : "int";
        switch (t) {
            case POINTER: return "void*";
            case CHAR_PTR: return "char*";
            case FLOAT: return "double";
            case INTEGER: return "int";
            case NONE: return isAarch64 ? "long" : "int";
            default: return isAarch64 ? "long" : "int";
        }
    }

    /**
     * v2.0.19 改进: 给定寄存器名 + 机器类型, 直接从寄存器位宽推断 fallback 类型.
     * <p>
     * 优先于 UsageType: 即使 UsageType.NONE (分类失败), 也能给出"基于寄存器位宽"的合理类型:
     * <ul>
     *   <li>x0 (aarch64 64-bit) → long</li>
     *   <li>w0 (aarch64 32-bit) → int</li>
     *   <li>r0 (arm32 32-bit) → int</li>
     *   <li>s0 → float</li>
     *   <li>d0 → double</li>
     * </ul>
     */
    private static String inferTypeFromReg(String reg, boolean isAarch64) {
        if (reg == null || reg.isEmpty()) return isAarch64 ? "long" : "int";
        String r = reg.toLowerCase(Locale.ROOT);
        if (r.startsWith("w")) return "int";
        if (r.startsWith("x")) return "long";
        if (r.startsWith("r")) return "int";
        if (r.startsWith("s")) return "float";
        if (r.startsWith("d")) return "double";
        if (r.startsWith("q")) return "long long";
        return isAarch64 ? "long" : "int";
    }

    /**
     * v2.0.19 改进: 把 usage → 类型的 fallback 走 {@link #inferTypeFromReg},
     *   避免 UsageType.NONE 时无脑给 long/int. 之前 classifyUsage 没匹配的"mov x0, x1" 等
     *   模式会返回 NONE, 然后 inferType 走 default 给 long (aarch64) — 用户看到的就是
     *   "所有 x0 都是 long" 的误判.
     */
    private static String inferTypeForParam(UsageType t, String reg, boolean isAarch64, boolean isFp) {
        if (isFp) return "double";
        if (t != null && t != UsageType.NONE) {
            return inferType(t, isAarch64, isFp);
        }
        // t == NONE: 按寄存器位宽 fallback
        return inferTypeFromReg(reg, isAarch64);
    }

    private static String inferReturnType(List<DisassembledInstruction> insns, boolean isAarch64) {
        // v2.0.19: 兼容老调用 (无 paramRegs), 直接走基础推断
        return inferReturnType(insns, isAarch64, null);
    }

    /**
     * v2.0.19 改进: 返回类型推断 — 不再无脑 int.
     * <p>
     * 策略: 从函数尾部 (ret 前) 往前找最后写 ret 寄存器的指令, 按指令类型推:
     * <ul>
     *   <li>{@code ldr xN, [...]} / {@code ldr xN, [pc, ...]} → 加载地址 → void*</li>
     *   <li>{@code ldr wN, [...]} → 32 位值 → int / uint</li>
     *   <li>{@code ldr xN, [...]} → 64 位值 → long</li>
     *   <li>{@code ldr dN, ...} / {@code fmov dN, ...} → double</li>
     *   <li>{@code ldr sN, ...} / {@code fmov sN, ...} → float</li>
     *   <li>{@code mov xN, #0} / {@code mov wN, #0} → 0 (具体类型由其它 param 决定, 默认 int)</li>
     *   <li>{@code mov xN, xM} → 同 param M 的类型 (跟参数一致)</li>
     *   <li>{@code add/sub/mul/... xN, ...} → 算术结果 → int/long</li>
     *   <li>无写 ret 寄存器的指令 (pure-call wrapper) → void</li>
     * </ul>
     * 若没匹配, 按架构默认 (aarch64 → long, arm32 → int).
     *
     * @param paramRegs 函数读到的参数寄存器列表 (供 "mov xN, xM" 查 param M 的类型),
     *                  为 null 时仅做基础推断
     */
    private static String inferReturnType(List<DisassembledInstruction> insns, boolean isAarch64,
                                          @Nullable List<String> paramRegs) {
        if (insns == null || insns.isEmpty()) return isAarch64 ? "long" : "int";
        String retReg = isAarch64 ? "x0" : "r0";
        for (int i = insns.size() - 1; i >= 0; i--) {
            DisassembledInstruction ins = insns.get(i);
            String mn = ins.mnemonic == null ? "" : ins.mnemonic.toLowerCase(Locale.ROOT);
            if (mn.equals("ret") || mn.equals("bx") || mn.equals("bxeq") || mn.equals("retq")
                    || mn.equals("bl") || mn.equals("blr") || mn.equals("b") || mn.equals("br")
                    || mn.equals("pop") || mn.equals("bne") || mn.equals("beq") || mn.equals("bgt")
                    || mn.equals("blt") || mn.equals("bge") || mn.equals("ble")) {
                continue;
            }
            if (mn.isEmpty()) continue;
            String op = ins.opStr == null ? "" : ins.opStr;
            if (!isWriteTo(op, retReg)) continue;
            String opl = op.toLowerCase(Locale.ROOT);

            // 1) ldr/ldur/ldp/ldpx → 加载
            if (mn.startsWith("ldr") || mn.startsWith("ldur") || mn.equals("ldp") || mn.equals("ldpx")
                    || mn.equals("ldnp")) {
                if (op.contains("[")) {
                    // 加载地址 → void*
                    // ldr xN, [xM] / ldr xN, [pc, #N] / ldp x0, x1, [...]
                    if (mn.startsWith("ldrb") || mn.startsWith("ldrsb")) return "char";
                    if (mn.startsWith("ldrh") || mn.startsWith("ldrsh")) return "short";
                    if (mn.startsWith("ldrsw")) return "long";
                    if (mn.equals("ldpsw")) return "long";
                    // 32-bit wN 寄存器 (只在 aarch64) 加载 → int
                    String dest = op.split(",")[0].trim().toLowerCase(Locale.ROOT);
                    if (dest.startsWith("w")) return "int";
                    return "void*";
                }
            }
            // 2) fmov / vldr / vstr 浮点
            if (mn.startsWith("fmov") || mn.startsWith("vldr") || mn.startsWith("vstr")
                    || mn.startsWith("fcvt") || mn.startsWith("vdiv") || mn.startsWith("vmul")
                    || mn.startsWith("vadd") || mn.startsWith("vsub") || mn.startsWith("vcvt")) {
                if (opl.startsWith("s") || mn.contains(".s")) return "float";
                if (opl.startsWith("d") || mn.contains(".d")) return "double";
                return "double";
            }
            // 3) mov xN, #0 / mov wN, #0 → 数字 0, 强类型未知时给 int
            if ((mn.equals("mov") || mn.equals("movz") || mn.equals("movn") || mn.equals("movk"))
                    && opl.contains("#0")) {
                // 0 值的"返回类型" = 函数应当返回的"占位 0"类型, 默认 int
                // 若函数返回值确实是 0 的语义 (void 返回), 应跟 caller side 比对
                String dest = op.split(",")[0].trim().toLowerCase(Locale.ROOT);
                if (dest.startsWith("w")) return "int";
                return isAarch64 ? "long" : "int";
            }
            // 4) mov xN, xM (同寄存器) → 跟 param M 类型
            if ((mn.equals("mov") || mn.equals("movz") || mn.equals("movk") || mn.equals("mvn"))
                    && op.contains(",")) {
                String src = op.split(",", 2)[1].trim().toLowerCase(Locale.ROOT);
                if (paramRegs != null && paramRegs.contains(src)) {
                    int idx = paramRegs.indexOf(src);
                    // param 的类型在 paramInfos 之外, 这里用 register 推断 fallback
                    return TypeMapper.fromRegister(src);
                }
            }
            // 5) add/sub/mul/lsl/lsr/and/orr/eor → 算术
            if (mn.equals("add") || mn.equals("sub") || mn.equals("mul") || mn.equals("mneg")
                    || mn.equals("sdiv") || mn.equals("udiv") || mn.equals("lsl") || mn.equals("lsr")
                    || mn.equals("asr") || mn.equals("and") || mn.equals("orr") || mn.equals("eor")
                    || mn.equals("neg") || mn.equals("smaddl") || mn.equals("umaddl")
                    || mn.equals("smull") || mn.equals("umull") || mn.equals("smulh") || mn.equals("umulh")) {
                String dest = op.split(",")[0].trim().toLowerCase(Locale.ROOT);
                if (dest.startsWith("w")) return "int";
                return isAarch64 ? "long" : "int";
            }
            // 6) cmp/tst 不写 ret 寄存器, 但意外命中 (不该)
            if (mn.equals("cmp") || mn.equals("tst") || mn.equals("cbz") || mn.equals("cbnz")) {
                continue;
            }
            // 7) adr / adrp 加载 PC 相对地址 → void*
            if (mn.equals("adr") || mn.equals("adrp")) return "void*";
            // 8) lea (x86) → void*
            if (mn.equals("lea")) return "void*";
            // 9) bl/blr (tail call) → void
            if (mn.equals("bl") || mn.equals("blr") || mn.equals("b") || mn.equals("br")) {
                return "void";
            }
            // 其它: 按 dest 寄存器类型 (wN → int, xN → long) fallback
            String dest = op.split(",")[0].trim().toLowerCase(Locale.ROOT);
            if (dest.startsWith("w")) return "int";
            if (dest.startsWith("s")) return "float";
            if (dest.startsWith("d")) return "double";
            if (dest.startsWith("q")) return "long long";
            return isAarch64 ? "long" : "int";
        }
        // 找不到 ret 寄存器的写入: 函数可能 pure-call wrapper, 给 void
        return "void";
    }

    private static boolean isWriteTo(String op, String r) {
        if (op == null || op.isEmpty() || r == null || r.isEmpty()) return false;
        String[] tokens = op.replaceAll("[\\[\\],#()!]", " ").trim().split("\\s+");
        if (tokens.length == 0) return false;
        String dest = tokens[0];
        if (dest.equals(r)) return true;
        if (r.length() == 2 && dest.length() == 2
                && (r.charAt(0) == 'x' || r.charAt(0) == 'w')
                && (dest.charAt(0) == 'x' || dest.charAt(0) == 'w')
                && dest.charAt(1) == r.charAt(1)) {
            return true;
        }
        return false;
    }

    private static boolean isReadOf(String op, String r) {
        if (op == null || op.isEmpty() || r == null || r.isEmpty()) return false;
        String rl = r.toLowerCase(Locale.ROOT);
        Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(rl) + "(?![A-Za-z0-9_])");
        return p.matcher(op.toLowerCase(Locale.ROOT)).find();
    }

    private static List<LocalVar> collectLocalVars(List<DisassembledInstruction> insns, int machine) {
        if (insns == null) return Collections.emptyList();
        Map<Integer, int[]> stats = new LinkedHashMap<>();
        Pattern p = Pattern.compile("\\[\\s*sp\\s*,\\s*#\\s*(-?\\d+)\\s*\\]");
        for (DisassembledInstruction ins : insns) {
            if (ins.opStr == null) continue;
            Matcher m = p.matcher(ins.opStr);
            while (m.find()) {
                int off = Integer.parseInt(m.group(1));
                if (off >= 0) continue;
                int[] s = stats.get(off);
                if (s == null) { s = new int[]{0, 4}; stats.put(off, s); }
                s[0]++;
                if ((ins.mnemonic != null && ins.mnemonic.startsWith("ldrd"))
                        || (ins.opStr != null && ins.opStr.contains("x") && machine == 183)) {
                    s[1] = 8;
                }
            }
        }
        List<LocalVar> out = new ArrayList<>();
        List<Integer> keys = new ArrayList<>(stats.keySet());
        Collections.sort(keys);
        for (int k : keys) {
            int[] s = stats.get(k);
            out.add(new LocalVar(k, s[0], s[1]));
        }
        return out;
    }

    private static List<StringRef> collectStringRefs(List<DisassembledInstruction> insns, FunctionInfo fn) {
        if (insns == null || fn == null) return Collections.emptyList();
        Set<Long> refs = new HashSet<>();
        for (DisassembledInstruction ins : insns) {
            String mn = ins.mnemonic == null ? "" : ins.mnemonic.toLowerCase(Locale.ROOT);
            String op = ins.opStr == null ? "" : ins.opStr;
            if (mn.equals("bl") || mn.equals("blr")) {
                // hint: bl strlen/strcmp 等
            }
        }
        return new ArrayList<>();
    }
}

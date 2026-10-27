package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * 借鉴 SOMCP v1.0.23 的 T1 审计批（上游 PR #179，30 个只读审计工具）。
 *
 * 全部是「已有 engine 数据 + 纯逻辑计算」，不新增 native、不改变现有工具行为：
 * ELF 结构与链接审计、JNI/NDK 画像、字符串与加密常量指纹、只读补丁校验、
 * APK 签名/ZIP/ABI 审计，以及可复现证据清单。
 *
 * 为什么单独一个文件：ToolCatalog.ALL 是扁平注册表，这些工具彼此不共享状态，
 * 集中放一处便于对照上游 diff、也避免把主目录继续撑大（arch-policy 有行数门禁）。
 */
object AuditTools {

    // ── T1 audit batch: ELF 结构与链接审计 ──

    private val elfAbiProfile = EngineToolHandler(
        ToolMeta(
            "elf_abi_profile",
            "解 e_ident/OSABI/e_flags 及各架构标志位，输出原始值与语义",
            "Decode e_ident, OSABI, ABI version, e_flags and architecture-specific flag bits with raw values and semantics.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfAbiProfile(a.str("workspaceId"), a.str("editSessionId")) }

    private val elfByteLedger = EngineToolHandler(
        ToolMeta(
            "elf_byte_ledger",
            "文件字节账本：每个区间由哪个节/段消费，列出空洞、重叠、越界",
            "Byte ledger: which section/segment consumes each file range; lists gaps, overlaps and out-of-bounds sections.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfByteLedger(a.str("workspaceId"), a.str("editSessionId")) }

    private val elfSectionAnomaly = EngineToolHandler(
        ToolMeta(
            "elf_section_anomaly",
            "检测节区异常：空名/零长/W+X/重名/越界/对齐",
            "Section anomalies: empty name, zero size, W+X, duplicate names, out-of-bounds spans, bad alignment.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfSectionAnomaly(a.str("workspaceId"), a.str("editSessionId")) }

    private val elfPhdrInvariants = EngineToolHandler(
        ToolMeta(
            "elf_phdr_invariants",
            "校验程序头不变量：filesz<=memsz、对齐同余、PT_LOAD 重叠与顺序",
            "Program header invariants: filesz<=memsz, alignment congruence, PT_LOAD overlaps and ordering.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfPhdrInvariants(a.str("workspaceId"), a.str("editSessionId")) }

    private val elfDynamicTags = EngineToolHandler(
        ToolMeta(
            "elf_dynamic_tags",
            "逐条解码全部 DT_*（含 ANDROID_*、RELR、VERDEF/VERNEED），字符串标签反解",
            "Decode every DT_* entry (incl. ANDROID_*, RELR, VERDEF/VERNEED) and resolve string-valued tags via DT_STRTAB.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfDynamicTags(a.str("workspaceId"), a.str("editSessionId")) }

    private val elfDtFlags = EngineToolHandler(
        ToolMeta(
            "elf_dt_flags",
            "解释 DF_*/DF_1_* 安全标志并给出置位证据",
            "Interpret DF_*/DF_1_* flags (BIND_NOW, PIE, TEXTREL, ...) with the raw value as evidence.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfDtFlags(a.str("workspaceId"), a.str("editSessionId")) }

    private val elfHashTables = EngineToolHandler(
        ToolMeta(
            "elf_hash_tables",
            "解析 SysV/GNU hash 桶链并统计查找代价、空桶",
            "Parse SysV/GNU hash bucket chains: bucket chain stats, empty buckets and lookup cost.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfHashTables(a.str("workspaceId"), a.str("editSessionId")) }

    private val elfRelocCensus = EngineToolHandler(
        ToolMeta(
            "elf_reloc_census",
            "按架构/类型聚合重定位普查，暴露未知类型",
            "Relocation census grouped by type and section; exposes unknown relocation types.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.elfRelocCensus(a.str("workspaceId"), a.str("editSessionId")) }

    private val dtNeededResolve = EngineToolHandler(
        ToolMeta(
            "dt_needed_resolve",
            "解析 DT_NEEDED 闭包：对已打开工作区按 SONAME/文件名匹配，列缺失",
            "Resolve the DT_NEEDED closure against other opened workspaces by SONAME or file name; lists what is missing.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.dtNeededResolve(a.str("workspaceId"), a.str("editSessionId")) }

    private val symbolVisibilityAudit = EngineToolHandler(
        ToolMeta(
            "symbol_visibility_audit",
            "按 visibility/binding 汇总导出面与隐藏面，列 weak 未定义引用",
            "Dynsym surface grouped by visibility/binding: exported, hidden/protected exports and weak undefined refs.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.symbolVisibilityAudit(a.str("workspaceId"), a.str("editSessionId")) }

    private val codePaddingCaves = EngineToolHandler(
        ToolMeta(
            "code_padding_caves",
            "枚举可复用填充空洞（零/nop/int3）与大小",
            "Enumerate reusable padding caves (zero/nop/int3 runs) with section, offset and VA.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                    "minSize" int "Minimum cave size in bytes (default 16, 4..65536)"
                }
            )
        }
    ) { e, a, _ -> e.codePaddingCaves(a.str("workspaceId"), a.str("editSessionId"), a.intValue("minSize", 16)) }

    private val jniMangleCodec = EngineToolHandler(
        ToolMeta(
            "jni_mangle_codec",
            "Java_* 符号与类/方法/签名双向编解码（含下划线转义）",
            "Two-way JNI symbol codec: Java_com_example_Foo_bar__sig <-> class/method/signature, with _1/_2/_3 escaping.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "action".oneOf("decode (default) | encode", "decode", "encode")
                    "symbol" str "JNI symbol to decode (action=decode)"
                    "className" str "Fully qualified class name (action=encode)"
                    "methodName" str "Method name (action=encode)"
                    "signature" str "Optional JNI signature like (Ljava/lang/String;)V (action=encode, produces the long name)"
                }
            )
        }
    ) { e, a, _ -> e.jniMangleCodec(a) }

    private val androidIdentNote = EngineToolHandler(
        ToolMeta(
            "android_ident_note",
            "解码 .note.android.ident 的 SDK/NDK/build number",
            "Decode .note.android.ident: SDK version, packed NDK version and build number.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.androidIdentNote(a.str("workspaceId"), a.str("editSessionId")) }

    private val ndkToolchainFingerprint = EngineToolHandler(
        ToolMeta(
            "ndk_toolchain_fingerprint",
            "从 clang 版本串、 sanitizer/LTO/rust 痕迹推断工具链",
            "Infer the toolchain from clang/GCC version strings plus sanitizer, LTO and rust markers; every claim carries evidence.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.ndkToolchainFingerprint(a.str("workspaceId"), a.str("editSessionId")) }

    private val libcxxVariant = EngineToolHandler(
        ToolMeta(
            "libcxx_variant",
            "判定 libc++/libstdc++/静态 STL 与异常/RTTI 开关",
            "Detect the STL variant (libc++ shared/static, legacy) and exception/RTTI hints from DT_NEEDED and dynsym.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.libcxxVariant(a.str("workspaceId"), a.str("editSessionId")) }

    // ── T1 audit batch: 字符串、常量、加密 ──

    private val strEncodingCensus = EngineToolHandler(
        ToolMeta(
            "str_encoding_census",
            "统计 ASCII/UTF-8/UTF-16LE 编码分布与重复字面量",
            "Census of string encodings (ASCII/UTF-8 multibyte/UTF-16LE raw sweep) plus duplicate literal ranking.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                    "limit" int "Maximum UTF-16LE runs and duplicate samples (default 200)"
                }
            )
        }
    ) { e, a, s -> e.strEncodingCensus(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit)) }

    private val strLiteralClasses = EngineToolHandler(
        ToolMeta(
            "str_literal_classes",
            "把字面量分类为 URL/路径/base64/格式化串/Java 描述符等",
            "Classify string literals: url, ip_port, abs_path, base64_like, hex_like, format_string, java_descriptor, package_name, uuid, sql, version.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                    "limit" int "Maximum classified items returned (default 500)"
                }
            )
        }
    ) { e, a, s -> e.strLiteralClasses(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit)) }

    private val cryptoConstFingerprint = EngineToolHandler(
        ToolMeta(
            "crypto_const_fingerprint",
            "用 S-box/轮常数/初值判定 AES/SM4/DES/ChaCha/TEA/Blowfish 等",
            "Identify cipher implementations by byte-exact constant tables (AES/SM4 S-boxes, ChaCha sigma, Blowfish P, TEA delta, DES IP) with offsets as evidence.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.cryptoConstFingerprint(a.str("workspaceId"), a.str("editSessionId")) }

    private val cryptoHashConstants = EngineToolHandler(
        ToolMeta(
            "crypto_hash_constants",
            "识别 MD5/SHA/CRC/FNV/xxHash 初值、常数表与位置",
            "Locate hash constants (MD5 T-table, SHA-256 K/IV, CRC-32 poly, FNV, xxHash64, MurmurHash) with file offsets.",
            "analyze",
            ToolClass.EXTRA,
            heavy = true
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                }
            )
        }
    ) { e, a, _ -> e.cryptoHashConstants(a.str("workspaceId"), a.str("editSessionId")) }

    private val netEndpointExtract = EngineToolHandler(
        ToolMeta(
            "net_endpoint_extract",
            "提取 URL、host、port、IP 端点并按协议分类",
            "Extract URLs, hosts, ports and IP endpoints from the string table, classified by scheme.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                    "limit" int "Maximum endpoints returned (default 300)"
                }
            )
        }
    ) { e, a, s -> e.netEndpointExtract(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit)) }

    private val filePathRoots = EngineToolHandler(
        ToolMeta(
            "file_path_roots",
            "归类 /proc /sys /data /sdcard 等绝对路径及用途，标反分析面",
            "Classify absolute paths by root (/proc, /sys, /data, /sdcard, ...) and flag anti-analysis surfaces like /proc/self/maps.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                    "limit" int "Maximum paths returned (default 300)"
                }
            )
        }
    ) { e, a, s -> e.filePathRoots(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit)) }

    private val envPropertyKeys = EngineToolHandler(
        ToolMeta(
            "env_property_keys",
            "列出形似 Android 系统属性/环境变量的键名（字符串形态，不证调用点）",
            "List strings shaped like Android system properties (ro./persist./...) and known env keys; string-shape only, call sites are not proven.",
            "analyze",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                    "limit" int "Maximum keys returned (default 300)"
                }
            )
        }
    ) { e, a, s -> e.envPropertyKeys(a.str("workspaceId"), a.str("editSessionId"), a.intValue("limit", s.defaultLimit)) }

    // ── T1 audit batch: 补丁规划与校验（全部只读） ──

    private val patchCaveFinder = EngineToolHandler(
        ToolMeta(
            "patch_cave_finder",
            "给出可执行空洞候选及安全约束（inExecLoad/reloc/entry），只读不写入",
            "Executable cave candidates with safety constraints: inside executable PT_LOAD, not covered by relocations, not holding the entry point. Read-only.",
            "edit",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID (optional)"
                    "minSize" int "Minimum cave size in bytes (default 16, 4..65536)"
                }
            )
        }
    ) { e, a, _ -> e.patchCaveFinder(a.str("workspaceId"), a.str("editSessionId"), a.intValue("minSize", 16)) }

    private val patchLengthGuard = EngineToolHandler(
        ToolMeta(
            "patch_length_guard",
            "校验补丁长度与目标指令跨度一致，给出 nop 填充建议",
            "Check a proposed patch against the target instruction-slot size; verdict fits/fits_with_fill/too_long with an architecture nop fill suggestion.",
            "edit",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "newHex" str "Hex bytes of the proposed patch"
                    "byteLength" int "Target instruction-slot size in bytes"
                    "architecture".oneOf("arm64 (default) | arm32 | x86_64 | x86", "arm64", "arm32", "x86_64", "x86")
                    "thumb" bool "ARM Thumb nop variant"
                }
            )
        }
    ) { e, a, _ -> e.patchLengthGuard(a) }

    private val patchApplyVerify = EngineToolHandler(
        ToolMeta(
            "patch_apply_verify",
            "应用后复核：字节差异、入口/节表/重定位目标一致性与 W+X 增量",
            "Post-apply verification: byte diffs as information; entry, header tables, reloc target set and W+X growth drive the verdict.",
            "edit",
            ToolClass.EXTRA,
            heavy = true
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "editSessionId" str "Edit session ID to verify against the workspace original (required)"
                }
            )
        }
    ) { e, a, _ -> e.patchApplyVerify(a.str("workspaceId"), a.str("editSessionId")) }

    // ── T1 audit batch: APK 签名/ZIP/ABI ──

    private val apkSigVerify = EngineToolHandler(
        ToolMeta(
            "apk_sig_verify",
            "解析并校验 APK v2/v3 签名块：重算分块摘要并验证签名",
            "Parse and verify APK Signing Block v2/v3: recompute the chunked content digests (0xa5/0x5a spec tree) and verify signer signatures against the embedded certificate.",
            "workspace",
            ToolClass.EXTRA,
            // 要按 1 MiB 分块把整包读一遍做两级摘要，几百 MB 的 APK 就是几百 MB 的 SHA-256：
            // 不占重活槽会让其它请求在并发上限内一直排队等它。
            heavy = true
        ) {
            objectSchema(
                props {
                    "path" str "Local APK path or path relative to the selected work directory"
                }
            )
        }
    ) { e, a, _ -> e.apkSigVerify(a.str("path").ifBlank { a.str("filePath") }) }

    private val apkCertInfo = EngineToolHandler(
        ToolMeta(
            "apk_cert_info",
            "输出签名证书主体/颁发者/算法/有效期/指纹",
            "Certificate details from the signing block and META-INF PKCS#7 entries: subject, issuer, validity, algorithms, SHA-256/SHA-1 fingerprints.",
            "workspace",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "path" str "Local APK path or path relative to the selected work directory"
                }
            )
        }
    ) { e, a, _ -> e.apkCertInfo(a.str("path").ifBlank { a.str("filePath") }) }

    private val apkZipAnomaly = EngineToolHandler(
        ToolMeta(
            "apk_zip_anomaly",
            "检测 ZIP 异常：重复项/路径穿越/越界偏移/未对齐 .so/加密位",
            "ZIP anomalies from the central directory: duplicate entries, path traversal, local headers inside CD, unaligned stored .so, encrypted flags.",
            "workspace",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "path" str "Local APK path or path relative to the selected work directory"
                }
            )
        }
    ) { e, a, _ -> e.apkZipAnomaly(a.str("path").ifBlank { a.str("filePath") }) }

    private val apkAbiCoverage = EngineToolHandler(
        ToolMeta(
            "apk_abi_coverage",
            "按 ABI 统计 SO、缺失 ABI、32/64 对称与 SONAME 冲突",
            "ABI coverage: group lib/<abi>/*.so, ELF-parse each for bits/architecture/SONAME, report missing ABIs and SONAME size conflicts.",
            "workspace",
            ToolClass.EXTRA,
            heavy = true
        ) {
            objectSchema(
                props {
                    "path" str "Local APK path or path relative to the selected work directory"
                }
            )
        }
    ) { e, a, _ -> e.apkAbiCoverage(a.str("path").ifBlank { a.str("filePath") }) }

    // ── T1 audit batch: 证据清单 ──

    private val evidenceManifest = EngineToolHandler(
        ToolMeta(
            "evidence_manifest",
            "生成可复现证据清单：输入哈希、scope、声明与自校验哈希",
            "Reproducibility manifest: input identity (sha256), analysis scope, caller-supplied claims and a self-hash over the canonical JSON.",
            "meta",
            ToolClass.EXTRA
        ) {
            objectSchema(
                props {
                    "workspaceId" str "Workspace ID"
                    "claims" arr "Optional claims: [{tool, resultSha256, note}] recorded verbatim"
                }
            )
        }
    ) { e, a, _ -> e.evidenceManifest(a.str("workspaceId"), a.optJSONArray("claims") ?: JSONArray()) }

    val ALL: List<ToolHandler> = listOf(
        elfAbiProfile,
        elfByteLedger,
        elfSectionAnomaly,
        elfPhdrInvariants,
        elfDynamicTags,
        elfDtFlags,
        elfHashTables,
        elfRelocCensus,
        dtNeededResolve,
        symbolVisibilityAudit,
        codePaddingCaves,
        jniMangleCodec,
        androidIdentNote,
        ndkToolchainFingerprint,
        libcxxVariant,
        strEncodingCensus,
        strLiteralClasses,
        cryptoConstFingerprint,
        cryptoHashConstants,
        netEndpointExtract,
        filePathRoots,
        envPropertyKeys,
        patchCaveFinder,
        patchLengthGuard,
        patchApplyVerify,
        apkSigVerify,
        apkCertInfo,
        apkZipAnomaly,
        apkAbiCoverage,
        evidenceManifest
    )
}

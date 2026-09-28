# Dex2C 加固目标：tools/dex2c/filter.txt 按【原始描述符】匹配方法
# （Lcom/soreverse/mcp/core/BackupCrypto;），因此该类必须原样活过 R8 ——
# 类名、成员名、方法体都不能被混淆/内联/删除，否则 dcc 会「no compiled methods」
# 而静默产出空结果（AGP 9.4.1 的 R8 下曾复现）。
-keep class com.soreverse.mcp.core.BackupCrypto { *; }

-keep class com.soreverse.mcp.nativecore.RizinNativeEngine {
    *;
}

-keep class com.soreverse.mcp.engine.LiefEngine {
    *;
}

-keep class com.soreverse.mcp.blutter.** {
    *;
}

-keep class com.github.unidbg.** {
    *;
}

-keep class unicorn.** {
    *;
}

-keep class net.fornwall.jelf.** {
    *;
}

-keep class capstone.** {
    *;
}

-keep class unicorn.** {
    *;
}

-keep class com.sun.jna.** {
    *;
}

-keep class com.sun.jna.ptr.** {
    *;
}

-keep class com.sun.jna.win32.** {
    *;
}

-keep class net.dongliu.apk.parser.** {
    *;
}

-keep class com.lambdapioneer.argon2kt.** {
    *;
}

-dontwarn com.github.unidbg.**
-dontwarn unicorn.**
-dontwarn net.fornwall.jelf.**
-dontwarn capstone.**
-dontwarn com.sun.jna.**
-dontwarn net.dongliu.apk.parser.**
-dontwarn com.google.common.collect.ArrayListMultimap
-dontwarn com.google.common.collect.Multimap
-dontwarn java.awt.Color
-dontwarn java.awt.Font
-dontwarn java.awt.Point
-dontwarn java.awt.Rectangle
-dontwarn javax.money.CurrencyUnit
-dontwarn javax.money.Monetary
-dontwarn org.javamoney.moneta.Money
-dontwarn org.joda.time.**
-dontwarn springfox.documentation.spring.web.json.Json
-dontwarn org.eclipse.xtext.**
-dontwarn org.glassfish.jersey.**

# ── Fastjson2（com.alibaba:fastjson:2.x = 官方 fastjson1 兼容层，内核 fastjson2）──
# 兼容层 / fastjson2-extension 里带了一批“可选第三方集成”类（spring / jersey / servlet /
# jaxrs / retrofit / netty / airlift / arrow / redisson / odps 等），Android 上不存在这些库；
# 一旦被 R8 判定为可达就会报 Missing class，逐包 dontwarn。
# （java.awt.** 已在下方 ELK 段落 dontwarn；okhttp3 是本工程已有依赖，无需处理）
# fastjson2 内部大量使用反射/运行期生成，整体保留（与同文件里 capstone/unidbg/jna 的处置一致）。
-keep class com.alibaba.fastjson.** { *; }
-keep class com.alibaba.fastjson2.** { *; }
-dontwarn com.alibaba.fastjson.**
-dontwarn com.alibaba.fastjson2.**
-dontwarn sun.misc.**
-dontwarn java.beans.**
-dontwarn javax.servlet.**
-dontwarn javax.ws.rs.**
-dontwarn org.springframework.**
-dontwarn org.apache.commons.logging.**
-dontwarn retrofit2.**
-dontwarn io.airlift.**
-dontwarn io.netty.**
-dontwarn org.apache.arrow.**
-dontwarn org.redisson.**
-dontwarn com.aliyun.odps.**

-keep class com.dsmcp.** {
    *;
}

-dontwarn com.dsmcp.**

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

-keep class kotlinx.coroutines.flow.Flow { *; }
-keep class kotlin.reflect.jvm.internal.LazyKProperty { *; }
-keepclassmembers class ** {
    static kotlin.reflect.KProperty[] $$delegatedProperties;
}

-dontwarn java.lang.management.**
-dontwarn org.slf4j.**

# APKEditor optional dependencies
-dontwarn com.reandroid.apk.DexProfileDecoder
-dontwarn com.reandroid.apk.DexProfileEncoder
-dontwarn com.reandroid.jcommand.OptionStringBuilder
-dontwarn com.reandroid.jcommand.annotations.ChoiceArg
-dontwarn com.reandroid.jcommand.annotations.CommandOptions
-dontwarn com.reandroid.jcommand.annotations.OptionArg
-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Image
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn org.jf.baksmali.Baksmali
-dontwarn org.jf.baksmali.BaksmaliOptions
-dontwarn org.jf.baksmali.CommentProvider
-dontwarn org.jf.dexlib2.Opcodes
-dontwarn org.jf.dexlib2.VersionMap
-dontwarn org.jf.dexlib2.dexbacked.DexBackedDexFile
-dontwarn org.jf.dexlib2.dexbacked.raw.HeaderItem
-dontwarn org.jf.dexlib2.iface.DexFile
-dontwarn org.jf.smali.Smali
-dontwarn org.jf.smali.SmaliOptions

# ── Eclipse ELK（CFG 可选布局引擎）及其传递依赖 EMF ──
# EMF 的 resource impl 引用了 Eclipse Platform / OSGi 的可选类（如 org.eclipse.core.resources.*），
# Android 上不存在 → R8 会因 Missing class 直接失败，必须 dontwarn。
# ELK/EMF 大量依赖反射与 EMF 工厂/元数据服务，整体保留（不混淆）。
-dontwarn org.eclipse.core.**
-dontwarn org.eclipse.emf.**
-dontwarn org.osgi.**
# ELK 的 comments/对齐子包引用了 AWT 几何类（Android 无 java.awt）
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.imageio.**
-keep class org.eclipse.elk.** { *; }
-keepclassmembers class org.eclipse.elk.** { *; }
-keep class org.eclipse.emf.** { *; }
-keepclassmembers class org.eclipse.emf.** { *; }

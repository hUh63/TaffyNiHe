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

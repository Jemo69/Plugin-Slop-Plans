# The launcher binds to these classes by name from the manifest, so obfuscation must
# never rename or remove them. AGP keeps manifest-referenced classes on its own, but
# the SDK's own base classes are matched by name too.
-keep class * extends android.content.ContentProvider { *; }
-keep class * extends android.app.Activity { *; }
-keep class de.jemo.kvaesitso.slopplans.SlopPlanProvider { *; }
-keep class de.jemo.kvaesitso.slopplans.SettingsActivity { *; }

# The plugin SDK does its own reflection over cursor columns and bundle keys. Keeping
# it whole is cheap and removes a whole class of "works in debug, crashes in release".
-keep class de.mm20.launcher2.** { *; }
-dontwarn de.mm20.launcher2.**

# kotlinx.serialization keeps generated serializers on the companion. Both the
# @Serializable classes and their generated $$serializer must survive.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.**
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class de.jemo.kvaesitso.slopplans.api.** { *; }

# Ktor and OkHttp pull in optional SLF4J bindings and optional coroutine pieces that
# are not on the runtime classpath.
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.**
-dontwarn io.ktor.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn javax.naming.**

# Crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

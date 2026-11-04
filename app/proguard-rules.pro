# 💡 JNI Native 方法保护
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.solo.purecast.core.nativebridge.** { *; }
-keep class com.solo.purecast.model.** { *; }

# 💡 UPnP 与网络类保护
-keep class net.mm2d.mmupnp.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**

# 💡 Android TV 与 Compose 保护
-keep class androidx.tv.material3.** { *; }

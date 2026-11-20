# 💡 JNI Native 方法保护
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.solo.purecast.core.nativebridge.** { *; }
-keep class com.solo.purecast.model.** { *; }

# 💡 AirPlay Native JNI 回调接口与实现类保护（防止 R8 混淆导致 GetMethodID 崩溃）
-keep interface com.solo.purecast.airplay.NativeAirPlayCallback { *; }
-keep class * implements com.solo.purecast.airplay.NativeAirPlayCallback { *; }

# 💡 UPnP 与网络类保护
-keep class net.mm2d.mmupnp.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**

# 💡 Android TV 与 Compose 保护
-keep class androidx.tv.material3.** { *; }

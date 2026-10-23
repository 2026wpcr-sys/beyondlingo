# 无界译 AI / BeyondLingo AI — ProGuard 规则
#
# 原则：只保留真正需要反射/序列化的部分，其余全部混淆以减小体积。

# ---------- Room ----------
# Room 生成的 DAO/Entity 实现需保留泛型签名与注解信息
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---------- Kotlin 反射与协程 ----------
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- AndroidX ----------
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---------- Compose ----------
# Compose 编译器生成的代码不需要额外规则，但需保留 Composable 相关元数据
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ---------- 本应用 ----------
# 无障碍服务与前台服务由系统按类名实例化，必须保留
-keep class dev.beyonddev.beyondlingo.service.SelectionAccessibilityService { *; }
-keep class dev.beyonddev.beyondlingo.service.FloatingWindowService { *; }
-keep class dev.beyonddev.beyondlingo.MainActivity { *; }
-keep class dev.beyonddev.beyondlingo.BeyondLingoApp { *; }

# ViewModel 通过反射按类名实例化，必须保留无参构造与类名
-keep class dev.beyonddev.beyondlingo.vm.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Compose 页面与组件被 Compose 编译器引用，保留以防反射失效
-keep class dev.beyonddev.beyondlingo.ui.** { *; }

# OCR 引擎
-keep class dev.beyonddev.beyondlingo.data.ocr.** { *; }

# 引擎实现通过接口调用，保留以支持未来插件化
-keep class dev.beyonddev.beyondlingo.data.engine.** { *; }

# 数据模型用于 JSON 解析
-keep class dev.beyonddev.beyondlingo.data.engine.EngineConfig { *; }
-keep class dev.beyonddev.beyondlingo.data.engine.EnginePreset { *; }

# ---------- ML Kit ----------
# ML Kit 通过反射加载内部实现，需整体保留
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# ---------- Coil ----------
-keep class coil.** { *; }
-dontwarn coil.**

# ---------- 通用 ----------
# 保留行号用于崩溃分析（混淆后仍可定位）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 移除日志（release 不输出 DEBUG/VERBOSE）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

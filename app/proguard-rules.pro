# 保留 Shizuku / 反射注入相关（如启用 R8 再细化）
-keep class dev.rikka.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }

# Ktor / kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-dontwarn io.ktor.**

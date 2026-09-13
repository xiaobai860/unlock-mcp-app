// 顶层构建脚本：统一声明插件版本（子模块只 apply，不重复版本号）
//
// 版本矩阵（与本机 Gradle 缓存对齐的现代化组合）：
//   Gradle 9.7.1（wrapper）+ AGP 9.3.0 + Kotlin 2.4.10
//   Compose BOM 2026.08.00（material3 1.4.0 / compose-ui 1.12.0）
//
// 说明：AGP 9.0 起内置 Kotlin 支持，不再需要（且不允许）apply `org.jetbrains.kotlin.android`。
// AGP 9.3.0 默认依赖 KGP 2.2.10，这里通过 buildscript classpath 将其提升到 2.4.10，
// 使 Compose / 序列化 编译器插件与语言版本保持一致。
buildscript {
    repositories {
        google { setUrl("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

// 顶层构建脚本：统一声明插件版本（子模块只 apply，不重复版本号）
//
// 版本矩阵（与本机 Gradle 缓存对齐的现代化组合）：
//   Gradle 9.7.1（wrapper）+ AGP 9.3.0 + Kotlin 2.4.20
//   Compose BOM 2026.09.00（material3 / compose-ui 由 BOM 锁定）
//
// ⚠️ AGP 上限受 Android Studio 版本约束，不是想用多新就用多新：
//    - 本机 Studio = AI-261.26222.65（2026.1），内置兼容上限即 AGP 9.3.0，
//      超限会报 "Latest supported version is AGP 9.3.0"，连 9.3.1/9.3.2 也大概率不在其已知列表内。
//    - AGP 9.4.0（当前最新稳定版，2026-09-03）需 Studio 2026.2（AI-262）及以上。
//    - AGP 9.5.0 目前仅 alpha05，无稳定版，需 Canary Studio。
//    故此处钉 9.3.0。升级 Studio 后按上述对应关系放开。
//
// 说明：AGP 9.0 起内置 Kotlin 支持，不再需要（且不允许）apply `org.jetbrains.kotlin.android`。
// AGP 9.3 自带 KGP，这里通过 buildscript classpath 显式对齐到 2.4.20，
// 使 Compose / 序列化 编译器插件与语言版本三者保持一致。
buildscript {
    repositories {
        google { setUrl("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}

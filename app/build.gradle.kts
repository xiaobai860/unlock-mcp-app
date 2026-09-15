import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    // AGP 9 内置 Kotlin（不要再 apply org.jetbrains.kotlin.android）
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

/* --------------------------------------------------------------------------
 * release 签名配置
 *   从仓库根目录的 keystore.properties 读取（该文件与 keystore 均已在 .gitignore 中）。
 *   文件不存在时「优雅降级」：不配置 release 签名，仅影响 assembleRelease，
 *   assembleDebug / 单元测试 / CI 首次 clone 后仍可正常构建。
 * ------------------------------------------------------------------------ */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning: Boolean = keystorePropsFile.exists()

android {
    namespace = "com.unlockguard.mcp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.unlockguard.mcp"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Ktor / Shizuku / 无障碍反射较多，关闭混淆最稳（保持原设计）
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    lint {
        // release 构建不因 lint 的（非致命）告警中断：
        // 本项目存在 Ktor 反射、Shizuku 隐藏 API、无障碍服务等场景，lint 误报偏多，
        // 且 release 不混淆，lintVital 的收益有限。关闭以避免误伤构建。
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// AGP 9 已移除 android.kotlinOptions，改用 Kotlin 插件的统一 compilerOptions（jvmTarget=17）
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Compose（版本由 BOM 统一管理）
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 图标库已停止更新（冻结在 1.7.8），显式钉住版本，避免新 BOM 未托管时解析失败
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 导航
    implementation("androidx.navigation:navigation-compose:2.10.1")

    // 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // MCP 官方 Kotlin SDK（0.15.0 = 2025-11-25 协议线，含 initialize 握手与版本协商）
    // 协议层（JSON-RPC 编解码、生命周期、能力协商、错误码、Streamable HTTP）全部由 SDK 实现，本项目不再自研。
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")

    // Ktor 3.x：与 2.x 相比为破坏性升级（改用 kotlinx-io）。
    // SDK 不传递 engine，故本项目自声明 CIO；
    // 以下 core / sse / content-negotiation / serialization 为 SDK 运行所需
    // （SDK 自身传递引入的是 3.5.1，显式对齐 3.5.2，避免与 CIO 混版）。
    implementation("io.ktor:ktor-server-cio:3.5.2")
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-sse:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")

    // 安全存储（PIN / Token）：1.1.0 已转正式版，脱离 alpha
    implementation("androidx.security:security-crypto:1.1.0")

    // Shizuku（主解锁通道，shell 权限）
    // api：Shizuku.pingBinder 等运行时接口；provider：随 AAR 自动并入 manifest 的 ShizukuProvider
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ---- 单元测试（QA 新增，仅 JVM，不影响 release 产物） ----
    testImplementation("junit:junit:4.13.2")
}

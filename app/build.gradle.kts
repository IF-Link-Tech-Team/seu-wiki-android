import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 签名材料从仓库外的 `keystore.properties` 读，路径可用环境变量
 * `SEU_WIKI_KEYSTORE` 覆盖。文件本身已在 .gitignore 里，密钥绝不入库。
 *
 * 找不到配置时 release 包会照常产出，只是**未签名**（装不上）——这样 CI 或
 * 别人 clone 下来 `assembleRelease` 不会直接失败，但本地 debug 开发不受影响。
 */
val keystorePropertiesFile = providers.environmentVariable("SEU_WIKI_KEYSTORE")
    .orElse("${System.getProperty("user.home")}/.seu-wiki-android/keystore.properties")
    .map { File(it) }

val keystoreProperties = Properties().apply {
    val f = keystorePropertiesFile.orNull
    if (f != null && f.exists()) f.inputStream().use { load(it) }
}

val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "tech.iflink.seuwiki"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.iflink.seuwiki"
        minSdk = 26
        targetSdk = 36
        // versionCode / versionName 从 gradle property 读，不再写死 1
        // （写死的话每次发版都要手动改，漏改就会被应用商店拒收）。
        // 缺省仍给 1，让本地能构建；CI 用 -PversionCode=… 覆盖。
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // 自检套件（SelfCheckTest）现在要断言 strings.xml 里的时间文案，
    // 必须让 Robolectric 能读到合并后的资源表；不开这个开关它拿到的
    // 是空的 Resource 对象，getString 直接抛 NotFoundException。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    /**
     * 缺签名材料时**直接失败**，不再静默产出未签名包。
     *
     * 之前 `if (hasReleaseSigning)` 一路包着，配置缺失时 release 任务照跑，
     * 产出一个**没有签名**的 APK —— 构建是绿的，装到设备上直接
     * INSTALL_PARSE_FAILED_NO_CERTIFICATES，很容易一路带到发布才炸。
     *
     * 逃生口是显式的：`-PallowUnsignedRelease=true`。只为「本地验证 release 变体
     * 能编过、R8 规则没问题」而存在，必须是有人主动加的，不会被 CI 顺手继承。
     */

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 2.4 起 `kotlinOptions.jvmTarget = "17"` 这种字符串写法被移除，
    // 必须走 compilerOptions DSL。与上面的 compileOptions 保持同为 17。
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // Analytics 需要 BuildConfig.DEBUG（DEBUG 构建不上报统计）与
        // BuildConfig.VERSION_NAME（User-Agent 里的版本段）。
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    // 自检套件：与 iOS 的 SelfCheck.swift 对齐的关键纯逻辑断言。
    // Robolectric 是必需的——Format/CampusHtml/Routes 都碰 Compose 与
    // android.net.Uri，普通 JVM 单测会直接抛「not mocked」。
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
/**
 * 缺 release 签名材料时**直接失败**，不再静默产出未签名包。
 *
 * 之前 `if (hasReleaseSigning)` 一路包着，配置缺失时 release 任务照跑，产出一个
 * **没有签名**的 APK —— 构建是绿的，装到设备上直接
 * INSTALL_PARSE_FAILED_NO_CERTIFICATES，很容易一路带到发布才炸。
 *
 * 判据取自 `startParameter.taskNames`（命令行请求的任务）而不是
 * `gradle.taskGraph.whenReady`：工程开了 configuration cache，
 * whenReady 在缓存命中时根本不会触发，守卫会静默失效。
 * 逃生口是显式的 `-PallowUnsignedRelease=true`：只为「本地验证 release 变体能编过、
 * R8 规则没问题」而存在，必须有人主动加，不会被 CI 顺手继承。
 */
val allowUnsignedRelease = (findProperty("allowUnsignedRelease") as String?) == "true"
val wantsRelease = gradle.startParameter.taskNames.any { it.contains("elease") }
if (!hasReleaseSigning && !allowUnsignedRelease && wantsRelease) {
    throw GradleException(
        "缺少 release 签名材料。请设置环境变量 SEU_WIKI_KEYSTORE 指向包含 " +
            "storeFile / storePassword / keyAlias / keyPassword 的 properties 文件。" +
            "若只是要本地验证 release 变体能否编过，请显式加 " +
            "-PallowUnsignedRelease=true（产出的包没有签名，不能发布）。",
    )
}

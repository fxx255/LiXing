import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.lixing"
    compileSdk = 36

    // 显式钉住本机已安装的版本，避免 AGP 去联网下载默认的 build-tools 35
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.example.lixing"
        // 所有构建类型统一挂 .debug 后缀（最终包名 com.example.lixing.debug）：
        // 设备上已装的历史版本就是这个包名，release 若不带后缀会被系统当作
        // 「另一个 App」并排安装而不是覆盖升级。个人应用无上架需求，
        // 保持与存量安装一致比「去掉 debug 字样」重要得多。
        applicationIdSuffix = ".debug"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.9"

        // 只包含公开的 OAuth 中转地址；百度 SecretKey 始终只保存在 Worker 中。
        buildConfigField(
            "String",
            "BAIDU_OAUTH_BASE_URL",
            "\"https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com\"",
        )
        // 版本更新清单（Cloudbase 云函数，国内可直连；APK 实体托管在 GitHub Releases）
        buildConfigField(
            "String",
            "UPDATE_CHECK_URL",
            "\"https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com/update/check\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // on-device 模型与运行时体积很大：x86_64 的 so 占 107MB 且只有模拟器用得上，
        // 真机分发只打 arm64-v8a（需要模拟器调试时临时加回即可）。
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // 调试签名固定用项目内的密钥库，避免用户主目录下的 debug.keystore
            // 被清理工具/安全软件删掉重建后，签名变化导致无法覆盖安装（数据被迫重置）。
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // applicationIdSuffix 已在 defaultConfig 统一设置（.debug）
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            // 模拟器调试需要 x86_64 so；只加在 debug，release 保持 arm64-only 瘦身。
            // AGP 会把这里的 abiFilters 与 defaultConfig 的取并集。
            ndk {
                abiFilters += "x86_64"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 必须用与已装版本相同的签名（项目内 debug.keystore），否则无法覆盖安装。
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += "onnx"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // 让单元测试能读到 Room 导出的 schema JSON（迁移测试用 9.json 重建 v9 库）。
    sourceSets {
        getByName("test") {
            resources.srcDir("$projectDir/schemas")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// 端侧食物模型必须是完整文件。曾发生下载超时留下“有 TFL3 文件头但内容被截断”的情况，
// 因此每次构建都校验固定 SHA-256，避免再次把坏模型装到真机。
val verifyMealModel by tasks.registering {
    val model = layout.projectDirectory.file("src/main/assets/food_classifier.tflite")
    inputs.file(model)
    doLast {
        val file = model.asFile
        check(file.isFile) { "Missing on-device food model: ${file.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02X".format(it) }
        check(digest == "03DD6D9129501F97BE00775D9E17B5D9AD13BE730149352ADB8F4CA953B7A650") {
            "Food model is incomplete or modified (SHA-256=$digest)"
        }
    }
}

val verifyMathOcrModels by tasks.registering {
    val modelDir = layout.projectDirectory.dir("src/main/assets/math_ocr")
    inputs.dir(modelDir)
    doLast {
        val expected = mapOf(
            "pix2text-mfd-1.5.onnx" to "40D4FC852D99BCBF25A9478897D2F49FBBB8F7FDD6569C088CD1C31386293BD7",
            "encoder_model.onnx" to "BD8D5C322792E9EC45793AF5569E9748F82A3D728A9E00213DBFC56C1486F37D",
            "decoder_model.onnx" to "FD0F92D7A012F3DAE41E1AC79421AEA0EA888B5A66CB3F9A004E424F82F3DAED",
            "tokenizer.json" to "3E2AB757277D22639BEC28C9D7972E352D3D1DBA223051FA674002DC5AB64DF3",
            "config.json" to "9F3812441D397C871B9B2A74E8D956B939AEC5F4F45745BBA9214E968D56449D",
            "preprocessor_config.json" to "36A945A7CC645688B9EF64DABAE16979CF5F7C1C448569CC306694EDC0598B9B",
            "generation_config.json" to "CBEA88288D5576A9655AD04E2456768544BE22273A1C5CA160E0D16384639B4F",
        )
        expected.forEach { (name, sha) ->
            val file = modelDir.file(name).asFile
            check(file.isFile) { "Missing math OCR asset: ${file.path}" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02X".format(it) }
            check(digest == sha) { "Math OCR asset is incomplete or modified: $name (SHA-256=$digest)" }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(verifyMealModel, verifyMathOcrModels) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.tensorflow.lite.task.vision)
    implementation(libs.opencv.android)
    implementation(libs.onnxruntime.android)

    // AI 助手的本地 OCR（离线、随 APK 打包、不依赖 GMS）：识别题目文字给非多模态模型用。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    // Rich AI answers: Markdown plus local JLatexMath rendering (no WebView or CDN).
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")
    // GFM 表格：让 | col | 渲染成真正的表格，而不是带竖线的纯文本。
    implementation("io.noties.markwon:ext-tables:4.6.2")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

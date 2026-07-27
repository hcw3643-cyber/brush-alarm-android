plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.hcw3643cyber.brushalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hcw3643cyber.brushalarm"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            buildConfigField("boolean", "TEST_FEATURES", "true")
            manifestPlaceholders["appLabel"] = "刷牙闹钟 测试版"
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("boolean", "TEST_FEATURES", "false")
            manifestPlaceholders["appLabel"] = "刷牙闹钟"
        }
    }

    // Keep one universal APK for convenience and smaller per-CPU APKs for phones.
    // The native ONNX Runtime library is the largest non-model component.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    testImplementation("junit:junit:4.13.2")
}

val verifyBrushModel by tasks.registering {
    group = "verification"
    description = "Checks that the separately distributed ONNX model is installed."
    doLast {
        val model = layout.projectDirectory.file(
            "src/main/assets/brush_classifier.onnx"
        ).asFile
        check(model.isFile) {
            "Missing ${model.path}. Run scripts/fetch-model.sh (or " +
                "scripts/fetch-model.ps1 on Windows) before building."
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyBrushModel)
}

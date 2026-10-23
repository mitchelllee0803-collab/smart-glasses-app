import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.smartglasses.app"
    compileSdk = 36
    // Same NDK as :engine and :llm; packaging uses its strip tool to drop native debug symbols.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.smartglasses.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The offline engine ships for 64-bit ARM phones only.
        ndk { abiFilters += "arm64-v8a" }
        // "test_device" in the scan list: checks the dashboard without glasses. Set to false before
        // publishing to users.
        buildConfigField("boolean", "SHOW_TEST_DEVICE", "true")
    }

    buildTypes {
        release {
            // R8: removes unused code (e.g. the unused Material icons) and resources. Names that
            // native code or reflection depends on are kept by proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug key until a release key exists: fine for installing with adb, not for a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Compressed in the APK (about half the size); Android unpacks them at install time.
            useLegacyPackaging = true
            // :engine and :llm both ship the NDK C++ runtime (same NDK version).
            pickFirsts += "**/libc++_shared.so"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Qualifiers such as @ApplicationContext also apply to the backing field (Kotlin 2.2+ default-to-be).
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":llm"))

    // VenusSDK (VenusOS smart glasses). It references Gson and androidx.core without bundling them.
    implementation(files("libs/VenusSDK_v1.8.4_6c1c58f_release.aar"))
    implementation(libs.gson)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

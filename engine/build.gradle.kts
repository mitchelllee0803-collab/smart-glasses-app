import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * Offline translation / speech engine, reused unchanged from the Translation project
 * (Translation/android/app). Kotlin packages keep their original names because the JNI symbols
 * of libotengine.so are derived from them.
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.offlinetranslate.engine"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        // 64-bit ARM only: the models need a 64-bit address space.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += "-O3"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        // Debug builds still run the release-optimised native engine.
        debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.jvmArgs("-Xmx2g") }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    // sherpa-onnx 1.13.8 (SenseVoice speech recognition, ONNX Runtime linked in).
    api(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation(libs.tesseract4android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.library)
}

// The proprietary VITURE SDK (libglasses.so + headers) is gitignored, so a fresh clone has
// none of it. Without it the JNI bridge is skipped: the module still compiles, HeadTracking
// simply finds no glasses, and NativeGlasses is never loaded. `-PuxspaceNoViture` forces the
// SDK-less build even when the SDK is present — the simulator build (docs/SIMULATOR.md) uses
// it so the APK carries no arm64-only libs and installs on an x86_64 emulator as-is.
val vitureSdkPresent =
    file("src/main/jniLibs/arm64-v8a/libglasses.so").exists() &&
        file("src/main/cpp/include").isDirectory
val buildVitureBridge = vitureSdkPresent && !project.hasProperty("uxspaceNoViture")

android {
    namespace = "com.uxspace.glasses"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 26

        // The VITURE SDK ships arm64 binaries; this phone is arm64.
        if (buildVitureBridge) {
            ndk {
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Builds libglasses_bridge.so — the JNI bridge to the native VITURE SDK.
    if (buildVitureBridge) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
    } else {
        // Keep the SDK's prebuilt .so files out of the APK too.
        sourceSets["main"].jniLibs.setSrcDirs(emptyList<String>())
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

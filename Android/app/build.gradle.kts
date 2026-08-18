plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.uxspace"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.uxspace"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Head tracking + the native VITURE SDK bridge, behind its own library.
    implementation(project(":glasses"))
    // The 3D workspace — camera, screens, GL renderer.
    implementation(project(":spatial"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Embedded ADB client — UxSpace activates its own shell-uid helper over Wireless
    // Debugging, with no separate Shizuku app. See docs/PRIVILEGE.md.
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.sun.security.android)

    testImplementation(libs.junit)
}

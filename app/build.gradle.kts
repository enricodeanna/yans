import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "eu.studiodeanna.yans"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "eu.studiodeanna.yans"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 2
        versionName = "0.1.1"
    }

    buildTypes {
        release {
            // R8 keeps only the code and resources the app reaches, out of the libraries it uses.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        // The app speaks English and Italian; the libraries' texts in other languages would go unused.
        localeFilters += listOf("en", "it")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    // F-Droid builds from source and reads no Google-signed blob of what the build used.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    target {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlin:compose-group-mapping:${libs.versions.kotlin.get()}")
}

android {
    namespace = "com.floatwindow.morebubblebutton"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.floatwindow.morebubblebutton"
        minSdk = 36
        targetSdk = 37
        versionCode = 6
        versionName = "1.6"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("/mnt/TY/android/android-project/hidenavbar/NavHideModule/keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("/mnt/TY/android/android-project/WIfikeyXposed/wifikeyxposed.keystore")
            storePassword = "tyopxn360"
            keyAlias = "tyopxn360"
            keyPassword = "tyopxn360"
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)

    compileOnly(libs.libxposed.api)
}
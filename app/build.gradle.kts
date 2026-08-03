import java.util.Base64
import java.util.Properties

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
        versionCode = 7
        versionName = "1.7"
    }

    signingConfigs {
        getByName("debug") {
            val localKeystore = file("/mnt/TY/android/android-project/hidenavbar/NavHideModule/keystore/debug.keystore")
            if (localKeystore.exists()) {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val localProps = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) localProps.load(localPropsFile.inputStream())

            val ksBase64 = System.getenv("KEYSTORE_BASE64")
            if (ksBase64 != null) {
                val ksFile = rootProject.file("release.keystore.tmp")
                ksFile.writeBytes(Base64.getDecoder().decode(ksBase64))
                storeFile = ksFile
            } else {
                val ksPath = localProps.getProperty("signing.storeFile")
                if (ksPath != null) storeFile = file(ksPath)
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: localProps.getProperty("signing.storePassword")
            keyAlias = System.getenv("KEY_ALIAS")
                ?: localProps.getProperty("signing.keyAlias")
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: localProps.getProperty("signing.keyPassword")
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
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing: a private key from the environment when one is given
// (KULTRDL_KEYSTORE_FILE), otherwise the public release key kept in the
// repository, so every build of a release installs over the last one.
val envKeystore = System.getenv("KULTRDL_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }
val publicKeystore = rootProject.file("signing/kultrdl-release.p12")

android {
    namespace = "app.kultr.dl"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kultr.dl"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.2"
    }

    signingConfigs {
        create("release") {
            if (envKeystore != null) {
                storeFile = envKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("KULTRDL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KULTRDL_KEY_ALIAS") ?: "kultrdl"
                keyPassword = System.getenv("KULTRDL_KEY_PASSWORD") ?: System.getenv("KULTRDL_KEYSTORE_PASSWORD")
            } else {
                storeFile = publicKeystore
                storeType = "PKCS12"
                storePassword = "kultrdl-public"
                keyAlias = "kultrdl"
                keyPassword = "kultrdl-public"
            }
        }
    }

    buildTypes {
        release {
            if (envKeystore != null || publicKeystore.isFile) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // yt-dlp, Python and ffmpeg are native per processor type: one APK per
    // ABI keeps each download small, and a universal one fits any phone.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // youtubedl-android runs Python and ffmpeg from the extracted native library directory.
        jniLibs {
            useLegacyPackaging = true
            // Prebuilt and already stripped (the Python and ffmpeg archives are not ELF at all).
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }

    lint {
        disable += "UnsafeOptInUsageError"
        disable += "MissingTranslation"
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.jaudiotagger)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

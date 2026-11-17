plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStoreFilePath = System.getenv("DCH_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("DCH_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("DCH_KEY_ALIAS")
val releaseKeyPassword = System.getenv("DCH_KEY_PASSWORD")

val releaseSigningValues = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)

val releaseSigningConfigured = releaseSigningValues.all {
    !it.isNullOrBlank()
}

if (releaseSigningValues.any { !it.isNullOrBlank() } &&
    !releaseSigningConfigured
) {
    throw GradleException(
        "Incomplete release signing configuration. Check all DCH_KEYSTORE_* and DCH_KEY_* environment variables."
    )
}

android {
    namespace = "com.fulltech.devilscutehornsai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fulltech.devilscutehornsai"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.0.3"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // CameraX
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

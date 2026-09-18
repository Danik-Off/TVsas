import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Version comes from CI (-PversionName=1.2.3 -PversionCode=10203) or falls back to a dev build.
val appVersionName = (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"
val appVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// Release signing: keystore.properties locally or environment variables in CI.
// When neither is present the release build is signed with the debug key so it stays installable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(key)

val hasReleaseKeystore = signingValue("KEYSTORE_FILE")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.tvsas.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tvsas.app"
        minSdk = 21
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    androidResources {
        // The service is Russian-only; dropping other locales of AndroidX libs shrinks the APK.
        localeFilters += listOf("ru")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(signingValue("KEYSTORE_FILE")!!)
                storePassword = signingValue("KEYSTORE_PASSWORD")
                keyAlias = signingValue("KEY_ALIAS")
                keyPassword = signingValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        htmlReport = true
        xmlReport = false
        // The app is TV-only and has no touch UI.
        disable += listOf("MissingTranslation", "ExtraTranslation", "IconMissingDensityFolder")
    }

    packaging {
        resources.excludes += listOf(
            "META-INF/*.version",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/DEPENDENCIES",
            "kotlin/**",
            "DebugProbesKt.bin",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.leanback)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.leanback)
    implementation(libs.okhttp)
    implementation(libs.coil)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

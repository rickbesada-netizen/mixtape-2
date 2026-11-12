import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.mixtape"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mixtape"
        minSdk = 26
        targetSdk = 35
        // Each GitHub build gets the next version number, so the in-app updater knows it's newer.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 1000 + run
        versionName = "0.6.$run"

        // Supabase project for accounts + cloud sync. Set these in gradle.properties, or as
        // GitHub Actions secrets (SUPABASE_URL / SUPABASE_ANON_KEY). They can also be entered
        // in the app under Settings > Account > Server.
        val supabaseUrl = (project.findProperty("SUPABASE_URL") as String?) ?: System.getenv("SUPABASE_URL") ?: ""
        val supabaseKey = (project.findProperty("SUPABASE_ANON_KEY") as String?) ?: System.getenv("SUPABASE_ANON_KEY") ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.trim()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseKey.trim()}\"")
    }

    // One permanent signing key for every build. Android only installs an update over an app
    // signed with the same key, so this is what lets new versions update in place and keep your
    // downloads instead of needing an uninstall.
    val signingProps = Properties().apply {
        val f = rootProject.file("keystore/signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val storePw = System.getenv("MIXTAPE_STORE_PASSWORD") ?: signingProps.getProperty("MIXTAPE_STORE_PASSWORD")
    signingConfigs {
        if (storePw != null && rootProject.file("keystore/mixtape.jks").exists()) {
            create("mixtape") {
                storeFile = rootProject.file("keystore/mixtape.jks")
                storePassword = storePw
                keyAlias = "mixtape"
                keyPassword = storePw
            }
        }
    }
    val mixtapeSigning = signingConfigs.findByName("mixtape")

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (mixtapeSigning != null) signingConfig = mixtapeSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key so the APK installs straight away.
            // Swap in your own keystore before publishing anywhere.
            signingConfig = mixtapeSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.nio APIs that need desugaring below API 33
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    // Extraction (YouTube, YouTube Music, SoundCloud)
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")

    // Kotlin / AndroidX core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Playback
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")

    // Chromecast
    implementation("androidx.media3:media3-cast:$media3")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Storage
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // Background downloads
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}

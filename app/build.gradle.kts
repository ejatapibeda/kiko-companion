import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
}

// Versi: satu sumber di gradle.properties (kiko.versionName). versionCode = major*10000 + minor*100 + patch.
val kikoVersionName = (findProperty("kiko.versionName") as String?) ?: "0.0.0"
val kikoVersionCode = kikoVersionName.split(".", "-").take(3).map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }

// Penandatanganan rilis: dari env (KIKO_KEYSTORE_FILE/_PASSWORD, KIKO_KEY_ALIAS/_PASSWORD) atau file properties
// (KIKO_KEYSTORE_PROPERTIES, -Pkiko.keystoreProperties, atau keystore.properties di root; semuanya di luar git).
// Tanpa keduanya, build rilis tetap jalan tapi tidak ditandatangani (untuk kontributor).
val signingProps = Properties().also { p ->
    val path = System.getenv("KIKO_KEYSTORE_PROPERTIES") ?: (findProperty("kiko.keystoreProperties") as String?)
    val f = path?.let { file(it) } ?: rootProject.file("keystore.properties")
    if (f.isFile) { f.inputStream().use(p::load); p.setProperty("_dir", f.absoluteFile.parent) }
}
fun signing(env: String, key: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(key)
val releaseStoreFile = signing("KIKO_KEYSTORE_FILE", "storeFile")?.let { path ->
    val f = File(path); if (f.isAbsolute) f else File(signingProps.getProperty("_dir") ?: rootDir.path, path)
}
val hasReleaseSigning = releaseStoreFile?.isFile == true && signing("KIKO_KEYSTORE_PASSWORD", "storePassword") != null &&
    signing("KIKO_KEY_ALIAS", "keyAlias") != null

android {
    namespace = "com.coactive.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.coactive.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = kikoVersionCode
        versionName = kikoVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Redirect URI Spotify = companion://spotify-callback (daftarkan di Spotify Developer Dashboard)
        manifestPlaceholders["spotifyRedirectScheme"] = "com.coactive.companion"
    }

    // APK per-ABI (kecil, mis. app-arm64-v8a-debug.apk) + APK universal (semua ABI)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = releaseStoreFile
            storePassword = signing("KIKO_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signing("KIKO_KEY_ALIAS", "keyAlias")
            keyPassword = signing("KIKO_KEY_PASSWORD", "keyPassword") ?: signing("KIKO_KEYSTORE_PASSWORD", "storePassword")
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        // Debug kecil untuk dibagikan: R8 + shrink resources, tetap debuggable & ditandatangani debug key.
        create("compact") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
            matchingFallbacks += "debug"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    androidResources { noCompress += "onnx" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { useLegacyPackaging = true; excludes += "lib/x86/**" } // .so dikompres di APK
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.all { it.environment("GROQ_API_KEY", System.getenv("GROQ_API_KEY") ?: ""); it.environment("LIVE_TESTS", System.getenv("LIVE_TESTS") ?: "") } }
}

// Nama APK rilis: kiko-companion-v1.0.0-arm64-v8a.apk (… -unsigned.apk bila tanpa kunci)
android.applicationVariants.all {
    val variant = this
    if (variant.buildType.name == "release") variant.outputs.all {
        val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val abi = out.filters.find { it.filterType == "ABI" }?.identifier ?: "universal"
        out.outputFileName = "kiko-companion-v${variant.versionName}-$abi${if (hasReleaseSigning) "" else "-unsigned"}.apk"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

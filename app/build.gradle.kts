import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = "") = localProps.getProperty(key, default)

android {
    namespace = "fr.pergens.hamacs"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.pergens.hamacs"
        // Android 8.0+ : couvre les Galaxy Tab A8 (Android 11 → 14) et des tablettes plus anciennes.
        minSdk = 26
        targetSdk = 35
        // Version donnée par le tag de la Release (-PappVersion=1.2.3, cf. release.yml), sinon celle-ci.
        // versionCode croissant tiré de la version (1.2.3 → 10203) : une mise à jour remplace l'app installée.
        val appVersion = (project.findProperty("appVersion") as String?)?.removePrefix("v") ?: "1.0.0"
        val parts = appVersion.split('.', '-').mapNotNull { it.toIntOrNull() } + listOf(0, 0, 0)
        versionCode = parts[0] * 10000 + parts[1] * 100 + parts[2]
        versionName = appVersion

    }

    // Clé de release : variables d'environnement (GitHub Actions) ou local.properties (release.store.file…).
    // Sans clé, la release est signée avec la clé de debug (installation de test uniquement).
    val releaseStore = System.getenv("RELEASE_STORE_FILE") ?: localProp("release.store.file").ifBlank { null }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = rootProject.file(releaseStore)
            storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: localProp("release.store.password")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: localProp("release.key.alias")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: localProp("release.key.password")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // Release : un APK par architecture (≈ 66 Mo pour arm64 au lieu de ≈ 143 Mo, les bibliothèques ML Kit pesant
    // 20 à 29 Mo par architecture) + un APK universel. Les builds de développement restent en un seul APK.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")

    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.6.1")

    // Détection de présence par la caméra frontale (analyse locale d'images basse résolution).
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")

    // Avatars : détourage sur l'appareil (services Google, sinon modèle « selfie » embarqué), orientation des
    // photos, QR code pour envoyer une photo depuis un téléphone.
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.zxing:core:3.5.3")

    // Detections zone storages: SMB 2/3 shares and NFS v3 exports (pure Java clients).
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("com.emc.ecs:nfs-client:1.1.0")
}

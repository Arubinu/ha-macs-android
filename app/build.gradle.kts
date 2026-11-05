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
    namespace = "fr.arubinu.hadash"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.arubinu.hadash"
        // Android 8.0+ : couvre les Galaxy Tab A8 (Android 11 → 14) et des tablettes plus anciennes.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEFAULT_HA_URL", "\"${localProp("ha.url", "http://homeassistant.local:8123")}\"")
        buildConfigField("String", "DEFAULT_HA_TOKEN", "\"${localProp("ha.token")}\"")
        buildConfigField("String", "DEFAULT_VOICE_URL", "\"${localProp("voice.url", "ws://homeassistant.local:8765/ws")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Fichiers personnels non publiés (configuration réelle, images sous licence) : `private/assets/` remplace
    // les fichiers du même nom de `src/main/assets/` (ex. dashboard.json) et en ajoute d'autres.
    sourceSets {
        getByName("debug").assets.srcDir(rootProject.file("private/assets"))
        getByName("release").assets.srcDir(rootProject.file("private/assets"))
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

    // Detections zone storages: SMB 2/3 shares and NFS v3 exports (pure Java clients).
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("com.emc.ecs:nfs-client:1.1.0")
}

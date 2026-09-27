plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)
}
android {
    // Keep Java/Kotlin bytecode target independent from the JDK used by Android Studio/Gradle.
    // Kotlin 2.2.10 does not target JVM 25; JVM 17 is sufficient for this Android app.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "de.example.timelapse"
    compileSdk = 37
    defaultConfig {
        applicationId = "de.example.timelapse"
        // 26 (Android 8.0) instead of 29: supports older devices that can't
        // be updated. Below 29, MediaStore has no scoped storage (see
        // PhotoCaptureHelper's legacy-storage branch) and foreground
        // service types don't exist yet (see CameraForegroundService /
        // DataSyncService's Build.VERSION.SDK_INT branches) - both are
        // handled explicitly rather than relying on AndroidX to paper over
        // the gap, since neither has a compat shim for this.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures {
        compose = true
    }
    buildTypes {
        release {
            // Verwendet das Standard-Debug-Zertifikat, damit die Release-APK 
            // ohne manuelles Signieren per ADB installiert werden kann.
            signingConfig = signingConfigs.getByName("debug")
            
            // Aktiviert R8 Code-Minifizierung und -Optimierung
            isMinifyEnabled = true
            // Entfernt ungenutzte Ressourcen aus der APK
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // Needed for androidx.lifecycle.compose.LocalLifecycleOwner (the
    // non-deprecated replacement for androidx.compose.ui.platform.LocalLifecycleOwner).
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")
}
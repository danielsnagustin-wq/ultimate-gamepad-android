import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ultimate.gamepad.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ultimate.gamepad.phone"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-etapa1"
    }
    // Firma de debug FIJA (2026-11-07): sin esto cada run de GitHub Actions
    // generaba un debug.keystore efímero nuevo y Android rechazaba instalar
    // la actualización encima ("Hay un problema con el archivo de la app").
    // El keystore vive en el repo como debug.keystore.b64 (base64; el
    // keystore de debug usa las credenciales públicas android/androiddebugkey
    // y NO es un secreto, nunca se usará para release); aquí se decodifica
    // en tiempo de configuración al build/ del módulo. Si el archivo no
    // está, se usa el debug por defecto de AGP (no rompe builds locales).
    val fixedDebugKeystore = project.layout.buildDirectory.file("debug.keystore").get().asFile
    val fixedDebugKeystoreB64 = rootProject.file("debug.keystore.b64")
    if (fixedDebugKeystoreB64.exists()) {
        fixedDebugKeystore.parentFile.mkdirs()
        fixedDebugKeystore.writeBytes(Base64.getDecoder().decode(fixedDebugKeystoreB64.readText().trim()))
    }
    signingConfigs {
        getByName("debug") {
            if (fixedDebugKeystore.exists()) {
                storeFile = fixedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "2.0.20" }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)
}

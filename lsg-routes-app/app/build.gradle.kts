import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cl.usach.diinf.lsg.routes"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.usach.diinf.lsg.routes"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        // URL base del backend: local.properties (ignorado por git) o -PBASE_URL en CI.
        // Por defecto, el backend local visto desde el emulador (10.0.2.2 = localhost del PC).
        val baseUrl = localProps.getProperty("BASE_URL")
            ?: (project.findProperty("BASE_URL") as String?)
            ?: "http://10.0.2.2:14141/"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        // Clave de MapTiler: local.properties (ignorado por git) o -PMAPTILER_API_KEY en CI.
        // Sin clave el build compila igual, pero el mapa no carga su estilo.
        val maptilerKey = localProps.getProperty("MAPTILER_API_KEY")
            ?: (project.findProperty("MAPTILER_API_KEY") as String?)
            ?: ""
        buildConfigField("String", "MAPTILER_API_KEY", "\"$maptilerKey\"")
    }

    buildTypes {
        release {
            // Habilitar minificacion y obfuscacion para release
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/LICENSE.md"
        resources.excludes += "META-INF/LICENSE-notice.md"
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation + Hilt
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Coroutines + Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Retrofit + OkHttp + Gson
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Security
    implementation(libs.security.crypto)

    // MapLibre
    implementation(libs.maplibre.android)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // WorkManager + Hilt-Work
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    // FusedLocationProvider
    implementation(libs.play.services.location)

    // Logging
    implementation(libs.timber)
    implementation(libs.material)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    // Pruebas instrumentadas
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

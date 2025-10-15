plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.frontendproyectoapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.frontendproyectoapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    
    // Fix for mergeDebugResources error
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.foundation.layout.android)


    // Retrofit
    implementation(libs.retrofit) // Retrofit
    implementation(libs.retrofit.converter.gson) // Retrofit Converter Gson
    implementation(libs.okhttp) // OkHttp

    // Gson for JSON parsing
    implementation(libs.gson)

    // Kotlin Coroutines for background tasks
    implementation(libs.coroutines.android)

    // navigation
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)

// Icons extended (importante para usar Icons.Filled.Restaurant)
    implementation(libs.material.icons.extended)
    implementation("io.coil-kt:coil-compose:2.4.0")


    // Lifecycle and viewModel
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.mediation.test.suite)
    implementation(libs.androidx.foundation.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.mpandroidchart)

    implementation ("com.github.mhiew:android-pdf-viewer:3.2.0-beta.3")

    // Google Gemini AI
    implementation("com.google.ai.client.generativeai:generativeai:0.8.0")

}
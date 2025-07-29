plugins {
    alias(libs.plugins.android.application)   // Android Gradle Plugin 8.4+ 권장
    alias(libs.plugins.kotlin.android)
    id("kotlin-kapt")
}

android {
    namespace   = "com.example.myapplication"

    /* ───────── Android API 레벨 ───────── */
    compileSdk  = 35           // ← API 35 Preview
    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk        = 24
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0"

        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    /* ───────── Glide ───────── */
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")   // ★ 추가
    /* ───────── Kotlin Coroutines ───────── */
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    /* ───────── Room 2.6.1 ───────── */
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    /* ───────── WorkManager 2.10.2 ───────── */
    // compileSdk 35 이상이므로 최신 버전 사용 가능
    implementation("androidx.work:work-runtime-ktx:2.10.2")

    /* ───────── AndroidX / Material 기본 ───────── */
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    /* ───────── 테스트 ───────── */
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

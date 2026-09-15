plugins {
    id("com.android.application")
}

android {
    namespace = "com.xueti.learn"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.xueti.learn"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "2.00"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    // 教材 PDF 文本抽取（全书学习：基于上传的 PDF 生成内容，减少幻觉）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // 统计页图表（柱状 / 折线 / 饼图 / 横向条形）
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
}

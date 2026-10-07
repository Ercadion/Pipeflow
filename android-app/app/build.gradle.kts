plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "io.github.ercadion.pipeflow"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.ercadion.pipeflow"
        minSdk = 26
        targetSdk = 34
        // 앱 버전 → android-app/version.properties 의 versionName (직접 수정). 코드에서는 BuildConfig.VERSION_NAME
        //   측정 폴더 이름·meta.json·결과에 자동 기록
        // versionCode(설치 시 새 버전 판단용 정수)는 자동: GitHub Actions 실행 번호 + 100 (PC 빌드는 50)
        val verProps = java.util.Properties().apply {
            rootProject.file("version.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
        }
        versionName = verProps.getProperty("versionName", "0.0.0").trim()
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100) ?: 50
        // 빌드한 커밋: GitHub Actions 는 GITHUB_SHA 환경변수 제공, PC 빌드는 "local"
        buildConfigField("String", "GIT_SHA", "\"${(System.getenv("GITHUB_SHA") ?: "local").take(7)}\"")
        ndk {
            // 실제 폰(arm64) + 에뮬레이터(x86_64)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // 데모 배포용 고정 키 (빌드마다 서명이 같아 덮어쓰기 설치 가능). 스토어 배포 시 별도 키로 교체
        create("demo") {
            storeFile = file("demo.keystore")
            storePassword = "pipeflow"
            keyAlias = "pipeflow"
            keyPassword = "pipeflow"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("demo")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demo")
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

chaquopy {
    defaultConfig {
        version = "3.10"
        pip {
            install("numpy")
        }
    }
}

dependencies {
    val camerax = "1.3.4"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}

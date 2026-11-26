plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.moritzbelli.gapmin"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.moritzbelli.gapmin"
        minSdk = 26
        targetSdk = 36
        // CI passes the version (see .github/workflows/android.yml)
        versionCode = (System.getenv("GAPMIN_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("GAPMIN_VERSION_NAME") ?: "0.2.0-dev"
    }

    // Release signing from environment (GitHub secrets). The same keystore must be used for
    // every release, otherwise Android refuses to install updates over the old app.
    val keystorePath = System.getenv("GAPMIN_KEYSTORE")
    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("GAPMIN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GAPMIN_KEY_ALIAS")
                keyPassword = System.getenv("GAPMIN_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Demo export shared with the web version
    sourceSets["main"].assets.srcDir("../../web/public")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")

    // Modern SQLite (UPSERT, JSON functions) on every Android version
    implementation("androidx.sqlite:sqlite:2.5.2")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    // Screenshot tests (Robolectric renders the Compose UI on the JVM)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

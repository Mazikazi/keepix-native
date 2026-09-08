plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Signing secrets never live in the tracked gradle.properties. Gradle merges
// ~/.gradle/gradle.properties automatically, so putting them there (or passing
// -PKEEPIX_... / env vars on CI) is picked up here with no further wiring.
// See docs/keystore-generation.md.
val releaseKeystoreFile = project.findProperty("KEEPIX_KEYSTORE_FILE") as String?
val releaseKeystorePassword = project.findProperty("KEEPIX_KEYSTORE_PASSWORD") as String?
val releaseKeyAlias = project.findProperty("KEEPIX_KEY_ALIAS") as String?
val releaseKeyPassword = project.findProperty("KEEPIX_KEY_PASSWORD") as String?
val hasReleaseSigning = releaseKeystoreFile != null && releaseKeystorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.sese.keepix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sese.keepix"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Created only when all four properties are present. This block is
        // evaluated while CONFIGURING the project -- for every task, including
        // assembleDebug -- so throwing here made a missing keystore break debug
        // builds and CI, not just release ones. The loud failure now lives in
        // the taskGraph check at the bottom of this file, which fires only when
        // a release artifact is actually requested.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Room's exported schema JSON, so migrations can be diffed and tested.
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }
    testOptions {
        unitTests {
            // JVM unit tests exercise production code (MediaRepository,
            // KeepixViewModel) that references android.jar constants whose
            // own static initializers call real platform methods (e.g.
            // MediaStore.Images.Media.EXTERNAL_CONTENT_URI). Without this,
            // any such reference throws instead of returning a stub value.
            isReturnDefaultValues = true
        }
    }
    lint {
        // lifecycle 2.9.0's NonNullableMutableLiveDataDetector is built
        // against a newer lint API than AGP 8.7.3 bundles, so it crashes with
        // IncompatibleClassChangeError instead of merely warning (verified by
        // running `./gradlew lintDebug` directly). This project uses
        // StateFlow exclusively and contains zero LiveData, so the check is
        // inapplicable here regardless.
        disable += "NullSafeMutableLiveData"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    
    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Fail loudly on an unsigned release -- but at execution-planning time, so it
// cannot break `assembleDebug`, `test`, or `lint` the way the old
// configuration-time throw did.
gradle.taskGraph.whenReady {
    val wantsReleaseArtifact = allTasks.any {
        it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease")
    }
    if (wantsReleaseArtifact && !hasReleaseSigning) {
        throw GradleException(
            "Release signing properties missing. Set KEEPIX_KEYSTORE_FILE, " +
            "KEEPIX_KEYSTORE_PASSWORD, KEEPIX_KEY_ALIAS and KEEPIX_KEY_PASSWORD in " +
            "~/.gradle/gradle.properties (NOT the tracked gradle.properties) or pass " +
            "them with -P. See docs/keystore-generation.md."
        )
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.bugzapperlabs.mycasts"
    compileSdk = 36

    // Set from CI via -PreleaseVersionName/-PreleaseVersionCode when a release tag is pushed
    // (issue #252); these defaults keep local assembleDebug/assembleRelease builds working
    // unchanged with no properties passed.
    val releaseVersionName = (project.findProperty("releaseVersionName") as String?) ?: "0.1.0"
    val releaseVersionCode = (project.findProperty("releaseVersionCode") as String?)?.toIntOrNull() ?: 1

    defaultConfig {
        applicationId = "com.bugzapperlabs.mycasts"
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    // Release signing (issue #252): populated from RELEASE_KEYSTORE_PATH/RELEASE_KEYSTORE_PASSWORD/
    // RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD env vars, which CI sets after decoding the keystore
    // secret. Left absent (rather than throwing) when those aren't set, so a local
    // `./gradlew assembleRelease` with no env vars still succeeds -- just produces an unsigned APK.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Distinct applicationId (issue #258) so a debug build installs alongside a
            // release-signed one instead of colliding on install -- same applicationId with a
            // different signing certificate is an install-time conflict Android refuses to
            // resolve, forcing an uninstall (and data loss) to switch between them otherwise.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "MyCasts Debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("RELEASE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true // exposes BuildConfig.VERSION_NAME for the About screen (issue #252)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Partial mitigation for the intermittent ViewModel test flakiness tracked in issue
            // #77 (kotlinx.coroutines.test.UncompletedCoroutinesError, always a different method,
            // always green on an immediate rerun): kotlinx-coroutines-test's own dead-man's-switch
            // defaults to 10s real wall-clock time for a background coroutine to finish, which a
            // resource-constrained CI runner (or this repo's own sandboxed dev environment, where
            // the same symptom has also been observed) can plausibly blow past under contention
            // even though nothing is actually hung. Raised well past what any of these tests should
            // ever legitimately need. Doesn't address the plain-AssertionError half of #77's
            // symptom (a StateFlow read racing its async update, not a stuck coroutine), so this is
            // a partial mitigation, not a confirmed fix.
            all { it.systemProperty("kotlinx.coroutines.test.default_timeout", "60s") }
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        getByName("test").resources.srcDirs("$projectDir/src/main/assets")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // issue #232: NavigableListDetailPaneScaffold/adaptive-navigation for tablet-landscape
    // list+detail panes. Not part of the Compose BOM's artifact set, so versioned explicitly.
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.core.splashscreen)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.room.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

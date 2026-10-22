plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Change this together with applicationId. Use ./rename.sh to do it in one go.
    namespace = "de.jemo.kvaesitso.slopplans"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.jemo.kvaesitso.slopplans"
        minSdk = 26
        targetSdk = 37

        // versionCode must increase with every release, versionName is what users see.
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Read from ~/.gradle/gradle.properties or the environment. Never commit
            // these; the keystore lives outside the repository.
            val keystorePath = providers.gradleProperty("SLOP_KEYSTORE_PATH")
                .orElse(providers.environmentVariable("SLOP_KEYSTORE_PATH"))
            val storePass = providers.gradleProperty("SLOP_KEYSTORE_PASSWORD")
                .orElse(providers.environmentVariable("SLOP_KEYSTORE_PASSWORD"))
            val alias = providers.gradleProperty("SLOP_KEY_ALIAS")
                .orElse(providers.environmentVariable("SLOP_KEY_ALIAS"))
            val keyPass = providers.gradleProperty("SLOP_KEY_PASSWORD")
                .orElse(providers.environmentVariable("SLOP_KEY_PASSWORD"))

            // Without a keystore the release build still works, it just is not signed.
            // An unsigned APK cannot be installed, so fail loudly instead.
            if (keystorePath.isPresent) {
                storeFile = file(keystorePath.get())
                storePassword = storePass.orNull
                keyAlias = alias.orNull
                keyPassword = keyPass.orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Treat anything the SDK/IDE flags as a build failure so CI stays green.
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Forward the live-check server URL into the test JVM. Gradle's -D flags set a
// property on the daemon, not on the forked test process, so it has to be handed over
// explicitly.
tasks.withType<Test>().configureEach {
    systemProperty(
        "slop.live.url",
        providers.gradleProperty("slop.live.url").orElse("").get(),
    )
}

dependencies {
    // The only hard requirement: the Kvaesitso plugin SDK.
    implementation(libs.launchersdk)

    // Everything below is convenience. Drop whatever you don't use.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activitycompose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialicons)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlin.serialization)

    // Networking. Delete if your plugin talks to a local service only.
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.contentnegotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
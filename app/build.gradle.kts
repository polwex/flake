plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.google.services)
}

android {
    namespace = "one.yago.sorchat.app"
    compileSdk = 37
    // Pinned to what the Nix-provided SDK ships, since it can't download extra components.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "one.yago.sorchat"
        // 29 for Opus voice notes (MediaRecorder OGG/OPUS).
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // For a local dev server: -Psorchat.serverUrl=http://10.0.2.2:8080 (the host as seen from the
        // emulator) or http://localhost:8080 on a USB device after `adb reverse tcp:8080 tcp:8080`.
        val serverUrl = providers.gradleProperty("sorchat.serverUrl").getOrElse("https://chat.urbit.men")
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST")
    }
}

room {
    // Checked-in schema history, needed to write migrations later.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.stream.webrtc)
    implementation(libs.androidx.core.telecom)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}

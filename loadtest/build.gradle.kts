import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("one.yago.sorchat.loadtest.LoadTestKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // Ktor logs through SLF4J; the tester prints its own output.
    runtimeOnly(libs.slf4j.nop)
}

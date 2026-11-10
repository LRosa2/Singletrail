import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: geometry, route preparation, maneuver detection, matching, ride state machine.
// No Android dependencies, so everything here is unit-testable and replayable on a plain JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

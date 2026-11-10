import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: safe GPX reader and writer (spec §8.4, §9 S2).
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
    // XmlPullParser API only at compile time; Android supplies it at runtime, tests use kxml2.
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

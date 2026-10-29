import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The sync itself, in plain Kotlin: no Android in it, so that it is tested on the computer.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnitPlatform()
}

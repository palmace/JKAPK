plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    // Fase 4a: compilador Kotlin embebido
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.24")

    // Fase 6: D8/R8 programatico
    implementation("com.android.tools:r8:8.3.37")

    // Fase 7b: firma de APK en Java puro
    implementation("com.android.tools.build:apksig:8.3.0")

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

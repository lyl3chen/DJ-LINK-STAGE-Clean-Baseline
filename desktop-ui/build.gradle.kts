plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.compose") version "1.6.11"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.deepsymmetry:beat-link:8.0.0")
}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageName = "dj-link-stage"
            packageVersion = "0.1.0"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.drewnoakes:metadata-extractor:2.18.0")

}

compose.desktop {
    application {
        mainClass = "MainKt"
        nativeDistributions {
            windows {
                iconFile.set(File("E:\\Programming\\ImageComposeOptimizator\\src\\main\\resources\\Logo.ico"))
            }
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "ImageOptimizator"
            packageVersion = "3.0.0"
        }
    }
}

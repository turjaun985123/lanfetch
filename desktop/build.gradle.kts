import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.lanfetch"
version = "1.0.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "com.lanfetch.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "lanfetch"
            packageVersion = "1.0.0"
            description = "LanFetch — download from your ISP's media servers, fast. Desktop edition."
            vendor = "NPC Automators"
            linux {
                packageName = "lanfetch"
                // Installed under /opt/lanfetch with a /usr/bin/lanfetch launcher
                // and a desktop entry (categories: Network / FileTransfer).
                appCategory = "Network"
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}

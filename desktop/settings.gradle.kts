pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // NOT the JetBrains Space compose-dev repo: its DNS failure on GitHub
        // runners fatally breaks dependency resolution. Release artifacts are
        // on the Portal / Maven Central.
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
rootProject.name = "lanfetch-desktop"

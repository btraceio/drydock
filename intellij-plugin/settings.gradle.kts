pluginManagement {
    // Needed to resolve the IntelliJ Platform Gradle plugin (and its
    // dependencies) from the Gradle Plugin Portal; mavenCentral as fallback
    // for transitive plugin dependencies.
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "drydock-intellij"

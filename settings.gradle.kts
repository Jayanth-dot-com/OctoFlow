pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "OctoFlow"
include(":app")
include(":core")
include(":llm")
include(":ui")
include(":accessibility")
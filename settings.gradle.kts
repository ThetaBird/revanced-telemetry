pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral(); google() } }
rootProject.name = "music-telemetry"
include(":patches", ":extensions:telemetry")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions the JDK 21 toolchain when it is not installed locally (CI and fresh machines).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bastion-mobile"

include(
    ":app",
    ":core:designsystem",
    ":core:domain",
    ":core:crypto",
    ":core:protocol",
    ":core:vpn",
    ":feature:onboarding",
    ":feature:vpn",
    ":feature:protection",
    ":feature:remote",
)
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

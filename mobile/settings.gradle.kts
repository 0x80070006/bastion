pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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

plugins {
    `kotlin-dsl`
}

group = "org.bastion.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "bastion.android.application"
            implementationClass = "org.bastion.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "bastion.android.library"
            implementationClass = "org.bastion.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "bastion.android.compose"
            implementationClass = "org.bastion.buildlogic.AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "bastion.jvm.library"
            implementationClass = "org.bastion.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}

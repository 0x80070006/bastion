package org.bastion.buildlogic

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension

/**
 * ktlint + detekt with warnings treated as errors. Generated sources (protobuf, tokens, KSP)
 * are excluded.
 */
internal fun Project.configureQuality() {
    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    extensions.configure<KtlintExtension> {
        version.set(libs.version("ktlint"))
        android.set(true)
        ignoreFailures.set(false)
        filter {
            exclude { element -> element.file.path.contains("build${java.io.File.separator}generated") }
            exclude("**/designsystem/tokens/Tokens.kt")
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom("src/main/kotlin", "src/test/kotlin", "src/androidTest/kotlin")
    }

    // Analyse for the project bytecode level, not for the JVM running Gradle.
    val jvmTarget = libs.version("jvmTarget")
    tasks.withType<Detekt>().configureEach { this.jvmTarget = jvmTarget }

    // detekt 1.23.x is compiled against Kotlin 2.0.21 and fails if Gradle resolves a newer
    // Kotlin compiler onto its own classpath.
    val detektKotlin = libs.version("detekt-kotlin")
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(detektKotlin)
        }
    }
}

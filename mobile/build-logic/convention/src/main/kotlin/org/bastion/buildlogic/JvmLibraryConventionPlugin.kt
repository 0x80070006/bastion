package org.bastion.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure Kotlin/JVM module (domain, crypto, protocol): no Android dependency, JUnit 5,
 * Kover coverage (ADR-0010).
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("org.jetbrains.kotlinx.kover")

        val jvmTarget = libs.version("jvmTarget")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(jvmTarget)
            targetCompatibility = JavaVersion.toVersion(jvmTarget)
        }
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
            compilerOptions {
                this.jvmTarget.set(JvmTarget.fromTarget(jvmTarget))
                allWarningsAsErrors.set(true)
            }
        }

        dependencies {
            add("testImplementation", platform(libs.lib("junit5-bom")))
            add("testImplementation", libs.lib("junit5-jupiter"))
            add("testImplementation", libs.lib("truth"))
            add("testRuntimeOnly", libs.lib("junit5-platform-launcher"))
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }

        configureQuality()
    }
}

package org.bastion.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/** Settings shared by every Android module (application and libraries). */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    val javaVersion = JavaVersion.toVersion(libs.version("jvmTarget"))
    android.apply {
        compileSdk = libs.int("compileSdk")
        defaultConfig.minSdk = libs.int("minSdk")
        compileOptions.sourceCompatibility = javaVersion
        compileOptions.targetCompatibility = javaVersion
        lint.apply {
            abortOnError = true
            warningsAsErrors = true
            checkDependencies = true
            // Version freshness is tracked by dedicated update PRs, not by lint on every build.
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        }
        packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*.md")
    }
}

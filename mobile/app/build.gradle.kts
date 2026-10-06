import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.bastion.android.application)
    alias(libs.plugins.bastion.android.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Product name and application id come from the single branding file (ADR-0002).
@Suppress("UNCHECKED_CAST")
val branding = JsonSlurper().parse(rootProject.file("../branding/product.json")) as Map<String, String>

android {
    namespace = "org.bastion.mobile"

    defaultConfig {
        applicationId = branding.getValue("applicationId")
        versionCode = 1
        versionName = "0.1.0"
        resValue("string", "product_name", branding.getValue("name"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(projects.core.crypto)
    implementation(projects.core.protocol)
    implementation(projects.core.vpn)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.vpn)
    implementation(projects.feature.protection)
    implementation(projects.feature.remote)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

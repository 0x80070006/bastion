import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.bastion.android.application)
    alias(libs.plugins.bastion.android.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Product name and application id come from the single branding file (ADR-0002).
@Suppress("UNCHECKED_CAST")
val branding = JsonSlurper().parse(rootProject.file("../branding/product.json")) as Map<String, String>

// Release signing material lives outside the repository (default: ~/.bastion-signing).
// Without it, release builds are produced unsigned.
val signingFile = providers.environmentVariable("BASTION_SIGNING_PROPERTIES")
    .orElse(providers.systemProperty("user.home").map { "$it/.bastion-signing/signing.properties" })
    .map { File(it) }
    .get()
val signing = Properties().apply {
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}

android {
    namespace = "org.bastion.mobile"

    if (!signing.isEmpty) {
        signingConfigs {
            create("release") {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = branding.getValue("applicationId")
        versionCode = 3
        versionName = "0.3.0"
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
        release {
            signingConfig = signingConfigs.findByName("release")
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

    implementation(projects.core.agent)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    // libsodium (ADR-0005): JNA must be the Android AAR (native dispatcher), not the JVM jar.
    implementation(libs.lazysodium.android) { exclude(group = "net.java.dev.jna") }
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

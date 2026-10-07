plugins {
    alias(libs.plugins.bastion.android.library)
    alias(libs.plugins.bastion.android.compose)
}

android {
    namespace = "org.bastion.feature.onboarding"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

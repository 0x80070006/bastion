plugins {
    alias(libs.plugins.bastion.android.library)
    alias(libs.plugins.bastion.android.compose)
}

android {
    namespace = "org.bastion.feature.vpn"
}

dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
}

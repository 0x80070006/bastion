plugins {
    alias(libs.plugins.bastion.android.library)
    alias(libs.plugins.bastion.android.compose)
}

android {
    namespace = "org.bastion.core.designsystem"
}

dependencies {
    // Material 3 is used as a technical base only; every visual value comes from tokens.
    api(libs.compose.material3)
}

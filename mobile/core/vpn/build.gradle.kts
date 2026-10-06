plugins {
    alias(libs.plugins.bastion.android.library)
}

android {
    namespace = "org.bastion.core.vpn"
}

dependencies {
    implementation(projects.core.domain)
}

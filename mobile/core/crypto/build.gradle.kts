plugins {
    alias(libs.plugins.bastion.jvm.library)
}

dependencies {
    implementation(projects.core.domain)
}

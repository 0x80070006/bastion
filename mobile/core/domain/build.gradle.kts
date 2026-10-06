plugins {
    alias(libs.plugins.bastion.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)
    testImplementation(libs.kotlinx.coroutines.test)
}

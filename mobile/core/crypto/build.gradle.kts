plugins {
    alias(libs.plugins.bastion.jvm.library)
}

dependencies {
    implementation(projects.core.domain)
    api(projects.core.protocol)
    // The adapter compiles against lazysodium-java; the app supplies lazysodium-android, whose
    // com.goterl.lazysodium.LazySodium class has identical signatures (ADR-0005).
    compileOnly(libs.lazysodium.java)
    testImplementation(libs.lazysodium.java)
}

tasks.withType<Test>().configureEach {
    val vectors = rootProject.file("../protocol/testvectors/v1.txt")
    inputs.file(vectors).withPropertyName("protocolVectors")
    systemProperty("bastion.vectors", vectors.absolutePath)
}

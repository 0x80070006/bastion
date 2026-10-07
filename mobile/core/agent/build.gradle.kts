plugins {
    alias(libs.plugins.bastion.jvm.library)
    alias(libs.plugins.protobuf)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}

dependencies {
    api(projects.core.crypto)
    api(projects.core.domain)
    compileOnly(libs.lazysodium.java)
    testImplementation(libs.lazysodium.java)
}

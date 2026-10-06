plugins {
    alias(libs.plugins.bastion.jvm.library)
    alias(libs.plugins.protobuf)
}

// Single source of truth: the repository-level protocol/ directory (ADR-0006).
sourceSets {
    main {
        proto {
            srcDir(rootProject.file("../protocol/proto"))
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
                register("kotlin") { option("lite") }
            }
        }
    }
}

// Generated code is not subject to explicit API mode or warnings-as-errors.
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=warning")
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    api(libs.protobuf.kotlin.lite)
}

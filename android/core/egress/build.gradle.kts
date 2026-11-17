import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:copy"))
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    compileOnly(libs.javax.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.javax.annotation)
    testImplementation(libs.grpc.inprocess)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.69.0"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                id("grpc")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("$rootDir/../wechat-egress/proto")
            include("egress.proto")
        }
    }
}

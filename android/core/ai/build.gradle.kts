plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:editor"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

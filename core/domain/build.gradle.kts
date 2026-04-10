plugins {
    alias(libs.plugins.kotlin.jvm)
    id("java-test-fixtures")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
}

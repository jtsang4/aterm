plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.jtsang4.aterm.tools.sshfixture.MainKt")
}

dependencies {
    implementation(libs.mina.sshd.common)
    implementation(libs.mina.sshd.core)
    testImplementation(libs.junit4)
}

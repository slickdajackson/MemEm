plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("org.json:json:20240303")
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.0")
}

application {
    mainClass.set("app.memem.rewrite.RewriteCliKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

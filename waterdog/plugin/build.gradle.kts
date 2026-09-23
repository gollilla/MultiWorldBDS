plugins {
    java
    // Pinned to the 8.3.x line (min Gradle 8.3) to match the gradle:8.10-jdk17 builder image
    // used by waterdog/Dockerfile; shadow 9.x requires Gradle 9.2+.
    id("com.gradleup.shadow") version "8.3.9"
}

group = "dev.bdswaterdogpe"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.waterdog.dev/main")
}

dependencies {
    compileOnly("dev.waterdog.waterdogpe:waterdog:2.0.4-SNAPSHOT")
    implementation(platform("software.amazon.awssdk:bom:2.46.7"))
    // urlConnectionClient avoids pulling in Netty, which WaterdogPE itself already bundles
    // under a different version — shading both risks classloading conflicts.
    implementation("software.amazon.awssdk:ecs") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
}

tasks.shadowJar {
    archiveFileName.set("waterdog-control.jar")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

plugins {
    java
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
}

tasks.jar {
    archiveFileName.set("waterdog-control.jar")
}

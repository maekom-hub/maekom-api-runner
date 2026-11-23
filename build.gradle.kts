// [v0.1.1] Maven Central BOM 호환을 위해 Kotlin 플러그인 버전을 2.1.0으로 상향
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.maekom.apirunner"
version = "0.4.5"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3")
        bundledPlugin("com.intellij.java")
    }

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jayway.jsonpath:json-path:2.9.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.1") {
        exclude(group = "org.jetbrains.kotlin")
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = "com.maekom.apirunner"
        name = "Maekom API Runner"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

tasks {
    named("buildSearchableOptions") {
        enabled = false
    }

    test {
        useJUnitPlatform()
    }
}

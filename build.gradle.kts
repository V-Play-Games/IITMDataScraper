plugins {
    kotlin("jvm") version "2.1.21"
}

group = "com.github.v-play-games"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.github.v-play-games:vjson:2.0.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}
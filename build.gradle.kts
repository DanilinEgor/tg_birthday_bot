plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.birthday"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.telegram:telegrambots:6.9.7.1")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation("org.postgresql:postgresql:42.7.1")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("com.h2database:h2:2.2.224")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("BirthdayBotKt")
}
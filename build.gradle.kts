plugins {
    kotlin("jvm") version "1.9.22"
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
}

application {
    mainClass.set("BirthdayBotKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "BirthdayBotKt"
    }
    from(configurations.runtimeClasspath.get().map {
        if (it.isDirectory) it else zipTree(it)
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
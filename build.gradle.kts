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
    implementation("org.postgresql:postgresql:42.7.1")
}

application {
    mainClass.set("BirthdayBotKt")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "BirthdayBotKt"
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}
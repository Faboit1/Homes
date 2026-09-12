plugins {
    java
}

group = "top.cheesesmp"
version = "1.1.0"
description = "Folia-native, dialog-driven homes for CheeseSMP"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    // Resolved at runtime by Paper from the `libraries` block in
    // paper-plugin.yml, so nothing has to be shaded into the jar.
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version, "description" to project.description)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.jar {
    archiveClassifier.set("")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

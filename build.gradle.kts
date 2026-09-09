plugins {
    java
    application
}

group = "com.sunmoon.bo"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Resolved from the composite build in settings.gradle.kts, not from a
    // repository — the version here is only what Gradle needs to match on.
    // Netty, Jackson, MyBatis and Bean Validation arrive through it as `api`
    // dependencies; Hikari, the Postgres driver and Flyway arrive at runtime.
    implementation("com.sunmoon:sun-moon-platform-core:0.1.0")

    // BO's own: password hashing is a BO concern, not a kernel one.
    implementation("at.favre.lib:bcrypt:0.10.2")

    runtimeOnly("ch.qos.logback:logback-classic:1.5.8")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.sunmoon.bo.BoBootstrap")
}

tasks.test {
    useJUnitPlatform()
}

plugins {
    kotlin("jvm") version "1.8.21"
    `java-library`
}

group = "com.grappenmaker"
version = "0.1"

java {
    registerFeature("reflect") { usingSourceSet(sourceSets["main"]) }
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.ow2.asm:asm:9.3")
    api("org.ow2.asm:asm-commons:9.3")
    api("org.ow2.asm:asm-util:9.3")
    "reflectApi"("org.jetbrains.kotlin:kotlin-reflect:1.8.21")
}

kotlin {
    jvmToolchain(11)
    explicitApi()
}
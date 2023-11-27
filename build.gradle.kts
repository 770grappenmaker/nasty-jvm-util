plugins {
    kotlin("jvm") version "1.9.20"
    `java-library`
}

group = "com.grappenmaker"
version = "0.1"

java {
    registerFeature("reflect") { usingSourceSet(sourceSets["main"]) }
}

configurations {
    getByName("reflectApi").extendsFrom(api.get())
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.ow2.asm:asm:9.5")
    api("org.ow2.asm:asm-commons:9.5")
    api("org.ow2.asm:asm-util:9.5")
    "reflectApi"("org.jetbrains.kotlin:kotlin-reflect:1.9.10")
}

kotlin {
    jvmToolchain(8)
    explicitApi()
}
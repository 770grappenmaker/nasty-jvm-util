plugins {
    kotlin("jvm") version "1.8.21"
}

group = "com.grappenmaker"
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    api("org.ow2.asm:asm:9.3")
    api("org.ow2.asm:asm-commons:9.3")
    api("org.ow2.asm:asm-util:9.3")
}

kotlin {
    jvmToolchain(11)
    explicitApi()
}
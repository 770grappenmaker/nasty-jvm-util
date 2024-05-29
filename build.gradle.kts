plugins {
    kotlin("jvm") version "2.0.0"
    `java-library`
}

group = "com.grappenmaker"
version = "0.1"

sourceSets {
    create("reflect") {
        java {
            srcDir("src/main/kotlin")
        }
    }
}

java {
    registerFeature("reflect") { usingSourceSet(sourceSets["reflect"]) }
}

configurations {
    getByName("reflectApi").extendsFrom(api.get())
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.ow2.asm:asm:9.7")
    api("org.ow2.asm:asm-commons:9.7")
    api("org.ow2.asm:asm-util:9.7")
    "reflectApi"("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
}

kotlin {
    jvmToolchain(8)
    explicitApi()
}
plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("org.jetbrains.kotlin.jvm") version "1.9.0"
}

group = "io.github.jose92"
version = "1.4"

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    compileOnly("com.android.tools.build:gradle:8.2.1")
}

gradlePlugin {
    plugins {
        create("mqttPlugin") {
            id = "io.github.jose92.mqtt"
            implementationClass = "io.github.jose92.MQTTPlugin"
        }
    }
}
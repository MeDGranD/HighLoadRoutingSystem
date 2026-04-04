plugins {
    kotlin("jvm") version "2.1.10"
}

group = "ru.ilyubarskiy.mai"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {

    // Osmosis для чтения и физической нарезки PBF
    implementation("org.openstreetmap.osmosis:osmosis-core:0.48.3")
    implementation("org.openstreetmap.osmosis:osmosis-pbf:0.48.3")
    implementation("org.openstreetmap.osmosis:osmosis-areafilter:0.48.3")
    implementation("org.openstreetmap.osmosis:osmosis-osm-binary:0.48.3")

    // FastUtil для супер-быстрых примитивных хэш-таблиц (Long -> Int)
    implementation("it.unimi.dsi:fastutil:8.5.12")

    // Jackson для JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    implementation("io.minio:minio:8.5.7")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}